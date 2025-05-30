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
import com.example.navigatorforvisuallyimpaired.service.DepthCameraImageListener
import com.example.navigatorforvisuallyimpaired.service.DetectorListener
import com.example.navigatorforvisuallyimpaired.service.DetectorService
import com.example.navigatorforvisuallyimpaired.service.DetectorServiceTensorFlow
import com.example.navigatorforvisuallyimpaired.service.LocaleFileTranslator
import com.example.navigatorforvisuallyimpaired.service.Translator
import com.example.navigatorforvisuallyimpaired.service.VoicedObjectService
import com.example.navigatorforvisuallyimpaired.service.VoicedObjectServiceImpl
import com.example.tofcamera.DepthCameraService
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
    private var voicedObjectService: VoicedObjectService = VoicedObjectServiceImpl()
    private lateinit var depthCamera: DepthCameraService<CameraActivity>
    private lateinit var depthCameraThread: HandlerThread
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var depthImage: ShortArray
    private val depthImageLock = Any()
    private val sayLock = Any()
    private var canSay: Boolean = true

    private lateinit var binding: ActivityCameraBinding
    private var isSound: Boolean = true

    private lateinit var textToSpeech: TextToSpeech
    private val ttsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var translator: Translator = LocaleFileTranslator(this)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = DetectorServiceTensorFlow(baseContext, this) {
                toast(it)
            }
            detector?.restart(isGpu = true)
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
        bindListeners()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech!!.setLanguage(Locale("ru"))
            translator.locale = "ru"
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language not supported!")
            } else {
                textToSpeech.setSpeechRate(1.2f)
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

    private fun onSoundButtonPressed() {
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
        speakTextAsync(boundingBoxes)
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }

    private fun speakTextAsync(boundingBoxes: List<BoundingBox>) {
        if(canSay) {
            canSay = false
            ttsExecutor.execute {
                synchronized(sayLock) {
                    var voicedObjects = voicedObjectService.getVoicedObject(boundingBoxes)
                    voicedObjects.forEach {
                        var speech: String = translator.getLocaleString(it.objectName) +
                                " " + translator.getLocaleString(it.verticalDirection.name) +
                                " " + translator.getLocaleString(it.horizontalDirection.name)
                        textToSpeech.speak(speech, TextToSpeech.QUEUE_ADD, null, "")
                    }
                }
            }
            canSay = true
        }
    }

    override fun onNewImage(depthMap: ShortArray) {
        synchronized(depthImageLock) {
            depthImage = depthMap.clone()
        }
    }


}