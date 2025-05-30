package com.example.navigatorforvisuallyimpaired.service

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.navigatorforvisuallyimpaired.Constants
import com.example.navigatorforvisuallyimpaired.entity.BoundingBox
import com.example.navigatorforvisuallyimpaired.service.MetaData.extractNamesFromLabelFile
import com.example.navigatorforvisuallyimpaired.service.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer

class DetectorServiceTensorFlow(
    private val context: Context,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) : DetectorService {
    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        val model = FileUtil.loadMappedFile(context, Constants.MODEL_PATH)
        labels.addAll(extractNamesFromMetadata(model))

        if (labels.isEmpty()) {
            labels.addAll(extractNamesFromLabelFile(context, Constants.LABELS_PATH))
        }

        interpreter = createInterpreter(model)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        Log.d("TFLite", "Input shape: ${inputShape?.joinToString()}")
        Log.d("TFLite", "Output shape: ${outputShape?.joinToString()}")
        Log.d("TFLite", "Labels size: ${labels.size}")

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]
            if (tensorWidth == 3 && inputShape.size >= 4) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null && outputShape.size >= 3) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }
    }

    private fun createInterpreter(model: MappedByteBuffer): Interpreter {
        val options = Interpreter.Options()
        val compatList = CompatibilityList()

        try {
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                options.addDelegate(GpuDelegate(delegateOptions))
                Log.d("TFLite", "Using GPU delegate")
            } else {
                options.setNumThreads(4)
                Log.d("TFLite", "GPU not supported, using CPU")
            }
        } catch (e: Exception) {
            Log.e("TFLite", "GPU delegate init failed: ${e.message}")
            options.setNumThreads(4)
        }

        return try {
            Interpreter(model, options)
        } catch (e: Exception) {
            Log.e("TFLite", "Interpreter creation failed: ${e.message}")
            throw e
        }
    }

    override fun restart(isGpu: Boolean) {
        interpreter.close()
        val model = FileUtil.loadMappedFile(context, Constants.MODEL_PATH)
        interpreter = createInterpreter(model)
    }

    override fun close() {
        interpreter.close()
    }

    override fun detect(frame: Bitmap, depthImage: ShortArray) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            Log.e("TFLite", "Invalid tensor shape configuration")
            return
        }

        var inferenceTime = SystemClock.uptimeMillis()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
        val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val output =
            TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)

        try {
            interpreter.run(imageBuffer, output.buffer)
        } catch (e: Exception) {
            Log.e("TFLite", "Interpreter run failed: ${e.message}")
            return
        }

        val bestBoxes = bestBox(output.floatArray, depthImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes == null) {
            detectorListener.onEmptyDetect()
        } else {
            detectorListener.onDetect(bestBoxes, inferenceTime)
        }
    }

    private fun bestBox(array: FloatArray, depthImage: ShortArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j

            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                if (maxIdx >= labels.size) continue
                val clsName = labels[maxIdx]

                val cx = array[c]
                val cy = array[c + numElements]
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w / 2F)
                val y1 = cy - (h / 2F)
                val x2 = cx + (w / 2F)
                val y2 = cy + (h / 2F)

                if (x1 !in 0f..1f || y1 !in 0f..1f || x2 !in 0f..1f || y2 !in 0f..1f) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, classNumber = maxIdx, className = clsName,
                        distance = calculateDistance(cx, cy, depthImage),
                        maxConf
                    )
                )
            }
        }

        return if (boundingBoxes.isEmpty()) null else applyNMS(boundingBoxes)
    }

    private fun calculateDistance(cx: Float, cy: Float, depthImage: ShortArray): Float {
        val depthWidth = 640
        val depthHeight = 640
        val centerX = (cx * depthWidth).toInt()
        val centerY = (cy * depthHeight).toInt()
        val index = centerY * depthWidth + centerX

        if (index < 0 || index >= depthImage.size) {
            return -1f
        }

        return depthImage[index] / 1000f
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.removeAt(0)
            selectedBoxes.add(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) iterator.remove()
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
        private const val IOU_THRESHOLD = 0.5F
    }
}
