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
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fabrice.vigie.VigieRuntime
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
 * mouvement + frames JPEG pour le flux), capture de photos et enregistrement
 * vidéo (déclenchable à distance). Lié au lifecycle du service → la
 * surveillance continue écran éteint / app en arrière-plan.
 */
class AnalysisEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    @Volatile private var recordingName: String? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val analyzer = MotionAnalyzer()

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val height = VigieRuntime.settings.value.analysisHeight
            val size = if (height >= 720) Size(1280, 720) else Size(640, 480)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .build()
            this.analysis = analysis
            analysis.setAnalyzer(cameraExecutor, analyzer)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // NE PAS unbindAll() : le Preview de l'activité est lié séparément.
            // Dégradation progressive : sur les vieux téléphones, 3 use cases en
            // parallèle (analyse + photo + vidéo) peuvent dépasser les capacités
            // → on retire la vidéo puis la photo, jamais l'analyse (flux vital).
            bindWithFallback(provider, analysis, imageCapture, videoCapture)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindWithFallback(
        provider: ProcessCameraProvider,
        analysis: ImageAnalysis,
        imageCapture: ImageCapture?,
        videoCapture: VideoCapture<Recorder>?,
    ) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val attempts = listOf(
            listOfNotNull(analysis, imageCapture, videoCapture),
            listOfNotNull(analysis, imageCapture),
            listOfNotNull(analysis),
        )
        for (attempt in attempts) {
            try {
                provider.bindToLifecycle(lifecycleOwner, selector, *attempt.toTypedArray())
                if (videoCapture != null && attempt.contains(videoCapture)) {
                    android.util.Log.i("VigieCam", "Binding complet (analyse + photo + vidéo)")
                } else if (attempt.size < 3) {
                    android.util.Log.w("VigieCam", "Binding réduit à ${attempt.size} use case(s) — vidéo indisponible")
                    // La vidéo n'a pas pu être liée : on la désactive proprement
                    this.videoCapture = null
                }
                return
            } catch (_: Exception) {
                android.util.Log.w("VigieCam", "Binding ${attempt.size} use case(s) échoué, tentative réduite")
            }
        }
        android.util.Log.e("VigieCam", "Aucun binding caméra possible")
    }

    fun stop() {
        try {
            recording?.stop()
        } catch (_: Exception) {
        }
        recording = null
        val useCases = listOfNotNull(analysis, imageCapture, videoCapture)
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

    // ---------- Vidéo ----------

    /** Démarre un enregistrement MP4 (sans audio). Retourne false si déjà en cours ou indisponible. */
    fun startVideoRecording(): Boolean {
        val vc = videoCapture
        if (vc == null || recording != null) return false
        val dir = File(context.filesDir, "videos").apply { mkdirs() }
        val file = File(dir, "vigie_${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file).build()
        return try {
            recording = vc.output.prepareRecording(context, options)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            recordingName = file.name
                            VigieRuntime.videoRecording.value = true
                        }
                        is VideoRecordEvent.Finalize -> {
                            recordingName = null
                            VigieRuntime.videoRecording.value = false
                            recording = null
                        }
                        else -> {}
                    }
                }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Arrête l'enregistrement en cours. Retourne le nom du fichier, sinon null. */
    fun stopVideoRecording(): String? {
        val rec = recording ?: return null
        val name = recordingName
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        recording = null
        recordingName = null
        VigieRuntime.videoRecording.value = false
        return name
    }

    fun isRecording(): Boolean = recording != null

    fun videoList(): List<Pair<String, Long>> {
        val dir = File(context.filesDir, "videos")
        return dir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.map { it.name to it.length() }
            ?.sortedByDescending { it.second }
            ?: emptyList()
    }

    fun videoFile(name: String): File? {
        val dir = File(context.filesDir, "videos")
        val f = File(dir, name)
        return if (f.exists() && f.canonicalPath.startsWith(dir.canonicalPath)) f else null
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
                    val jpeg = FrameExtractor.nv21ToJpeg(
                        nv21, image.width, image.height,
                        VigieRuntime.settings.value.jpegQuality,
                    )
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
