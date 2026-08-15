package com.fabrice.vigie.camera

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pipeline caméra du service foreground : analyse d'images (détection de
 * mouvement + frames JPEG pour le flux) + capture de photos. Lié au lifecycle
 * du service → la surveillance continue écran éteint / app en arrière-plan.
 */
class AnalysisEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val analyzer = MotionAnalyzer()

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .build()
            this.analysis = analysis
            analysis.setAnalyzer(cameraExecutor, analyzer)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // NE PAS unbindAll() : le Preview de l'activité est lié séparément.
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis,
                imageCapture,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        val useCases = listOfNotNull(analysis, imageCapture)
        if (useCases.isNotEmpty()) {
            cameraProvider?.unbind(*useCases.toTypedArray())
        }
        cameraExecutor.shutdown()
    }

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

    /** Rafale : N photos espacées dans le dossier courant de l'événement. */
    fun runBurst() {
        val dir = com.fabrice.vigie.VigieRuntime.burstTargetDir.value ?: return
        val count = com.fabrice.vigie.VigieRuntime.settings.value.burstCount
        val interval = com.fabrice.vigie.VigieRuntime.settings.value.burstIntervalMs
        fun next(i: Int) {
            if (i >= count) return
            val f = File(dir, "photo_${(i + 1).toString().padStart(2, '0')}.jpg")
            capturePhoto(f) { _ ->
                CameraBridge.onBurstPhoto?.invoke(f)
                scope.launch {
                    delay(interval)
                    next(i + 1)
                }
            }
        }
        next(0)
    }

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
                    if (frameCount % 3 == 0) {
                        CameraBridge.onMotionScore?.invoke(score)
                    }
                }
                previous = frame

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
