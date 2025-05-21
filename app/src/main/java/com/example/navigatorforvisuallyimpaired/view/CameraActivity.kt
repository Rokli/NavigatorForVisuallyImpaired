package com.example.navigatorforvisuallyimpaired.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.navigatorforvisuallyimpaired.Constants.LABELS_PATH
import com.example.navigatorforvisuallyimpaired.Constants.MODEL_PATH
import com.example.navigatorforvisuallyimpaired.databinding.ActivityCameraBinding
import com.example.navigatorforvisuallyimpaired.entity.BoundingBox
import com.example.navigatorforvisuallyimpaired.entity.Direction
import com.example.navigatorforvisuallyimpaired.entity.SpeechObject
import com.example.navigatorforvisuallyimpaired.service.DepthCameraImageListener
import com.example.navigatorforvisuallyimpaired.service.DetectorListener
import com.example.navigatorforvisuallyimpaired.service.DetectorService
import com.example.tofcamera.DepthCameraService
import com.google.ar.core.dependencies.e
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraActivity : ComponentActivity(), DetectorListener, DepthCameraImageListener,
    TextToSpeech.OnInitListener {

    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: DetectorService? = null
    private lateinit var depthCamera: DepthCameraService<CameraActivity>
    private lateinit var depthCameraThread: HandlerThread
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var depthImage: ShortArray
    private val depthImageLock = Any()
    private val sayLock = Any()


    private lateinit var binding: ActivityCameraBinding
    private var isSound: Boolean = true

    private lateinit var textToSpeech: TextToSpeech
    private val ttsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastSpokenTime: Long = 0
    private val speechCooldownMs = 2000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = DetectorService(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        textToSpeech = TextToSpeech(this, this)

        if (allPermissionsGranted()) {
//            depthCamera = DepthCameraService(this, false )
//            startCameraThread()
//            depthCamera.open()
            depthImage = ShortArray(640 * 640);
            for (index in depthImage.indices) {
                depthImage[index] = (1000..10000).random().toShort()
            }
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        detector?.restart(isGpu = true)
        bindListeners()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech!!.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language not supported!")
            } else {
                textToSpeech.setSpeechRate(0.8f)
            }
        }
    }

    private fun startCameraThread() {
        depthCameraThread = HandlerThread("Camera")
        depthCameraThread.start()

        depthCamera.handler = Handler(depthCameraThread.looper)
    }

    private fun bindListeners() {
        binding.backButton.setOnClickListener { onBackButtonPressed() }
        binding.sound.setOnClickListener { onSoundButtonPressed() }
    }

    @Override
    override fun onBackPressed() {
        onBackButtonPressed()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            synchronized(depthImageLock) {
                detector?.detect(rotatedBitmap, depthImage)
            }
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun onBackButtonPressed() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun onSoundButtonPressed(){
        isSound = !isSound
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        imageAnalyzer?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        try {
            if (!cameraExecutor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                cameraExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            cameraExecutor.shutdownNow()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::cameraExecutor.isInitialized && cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
            speechBoundingObject(boundingBoxes)
        }
    }

    fun getObjectToSpeech(boundingBoxes: List<BoundingBox>): List<SpeechObject> {
        var leftBoxes = mutableListOf<SpeechObject>()
        var middleBoxes = mutableListOf<SpeechObject>()
        var rightBoxes = mutableListOf<SpeechObject>()
        boundingBoxes.forEach {
            if ((it.x1 + it.x2) / 2 <= 0.33) {
                leftBoxes.add(SpeechObject(Direction.LEFT, it.className, (it.x1 + it.x2) / 2))
            } else if ((it.x1 + it.x2) / 2 > 0.33 && (it.x1 + it.x2) / 2 <= 0.66) {
                middleBoxes.add(SpeechObject(Direction.MIDDLE, it.className, (it.x1 + it.x2) / 2))
            } else {
                rightBoxes.add(SpeechObject(Direction.RIGHT, it.className, (it.x1 + it.x2) / 2))
            }
        }
        leftBoxes = leftBoxes.sortedByDescending { it.xm }.toMutableList()
        middleBoxes = middleBoxes.sortedByDescending { it.xm }.toMutableList()
        rightBoxes = rightBoxes.sortedBy { it.xm }.toMutableList()
        val top3Left = leftBoxes.take(2)
        val top5Middle = middleBoxes.take(3)
        val bottom3Right = rightBoxes.take(2)

        return top3Left + top5Middle + bottom3Right
    }

    private fun speakTextAsync(speechObject: List<SpeechObject>) {
        ttsExecutor.execute {
            synchronized(sayLock) {
                var speech = speechObject.joinToString("                   ") { "${it.text} ${it.direction.name}" }
                textToSpeech.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "")
            }
        }
    }

    private fun speechBoundingObject(boundingBoxes: List<BoundingBox>){
        if(isSound) {
            val currentTime = System.currentTimeMillis()
            val speechObject = getObjectToSpeech(boundingBoxes)

            if (currentTime - lastSpokenTime < speechCooldownMs) {
                return
            }
            lastSpokenTime = currentTime
            speakTextAsync(speechObject)
        }

    }

    override fun onNewImage(depthMap: ShortArray) {
        synchronized(depthImageLock) {
            depthImage = depthMap.clone()
        }
    }


}