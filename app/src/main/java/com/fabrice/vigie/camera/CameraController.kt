package com.fabrice.vigie.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.util.Size
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pont caméra → application : les callbacks sont enregistrés par le
 * SurveillanceViewModel et appelés depuis l'analyzer.
 */
object CameraBridge {
    @Volatile var onMotionScore: ((Float) -> Unit)? = null
    @Volatile var onJpegFrame: ((ByteArray) -> Unit)? = null
    @Volatile var onBurstPhoto: ((File) -> Unit)? = null
    @Volatile var burstCaptureRequested: (() -> Unit)? = null
}

/**
 * Contrôleur CameraX : preview + analyse d'images (détection mouvement +
 * frames JPEG pour le flux) + capture de photos en rafale.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val analyzer = MotionAnalyzer()

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .build()
            analysis.setAnalyzer(cameraExecutor, analyzer)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                imageCapture,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    /** Capture une photo vers [target]. */
    fun capturePhoto(target: File, onResult: (Boolean) -> Unit) {
        val ic = imageCapture
        if (ic == null) {
            onResult(false)
            return
        }
        val opts = ImageCapture.OutputFileOptions.Builder(target).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onResult(true)
            }

            override fun onError(exc: ImageCaptureException) {
                onResult(false)
            }
        })
    }

    /** Analyse chaque frame : score de mouvement + JPEG pour le flux (~5 fps). */
    private inner class MotionAnalyzer : ImageAnalysis.Analyzer {
        private var previous: MotionDetector.GrayFrame? = null
        private var lastJpegMs = 0L
        private var frameCount = 0

        override fun analyze(image: ImageProxy) {
            try {
                frameCount++
                val yPlane = image.planes[0]
                val y = FrameExtractor.extractY(yPlane.buffer, yPlane.rowStride, image.height)
                val frame = MotionDetector.downsample(y, image.width, image.height, yPlane.rowStride)
                val prev = previous
                if (prev != null) {
                    val score = MotionDetector.diffScore(frame, prev)
                    // N'analyser le mouvement qu'à ~10 fps max (1 frame sur ~3)
                    if (frameCount % 3 == 0) {
                        CameraBridge.onMotionScore?.invoke(score)
                    }
                }
                previous = frame

                // Flux MJPEG : throttlé
                val now = SystemClock.elapsedRealtime()
                if (now - lastJpegMs >= 200) {
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]
                    val nv21 = FrameExtractor.yuv420ToNv21(
                        yPlane.buffer, uPlane.buffer, vPlane.buffer,
                        image.width, image.height,
                        yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride,
                    )
                    val jpeg = FrameExtractor.nv21ToJpeg(nv21, image.width, image.height, 50)
                    if (jpeg != null) {
                        CameraBridge.onJpegFrame?.invoke(jpeg)
                    }
                    lastJpegMs = now
                }
            } catch (_: Exception) {
                // une frame malformée ne doit jamais faire planter l'analyzer
            } finally {
                image.close()
            }
        }
    }
}
