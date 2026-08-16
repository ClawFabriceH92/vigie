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
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var cameraInfo: androidx.camera.core.CameraInfo? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    @Volatile private var recordingName: String? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val analyzer = MotionAnalyzer()

    // ---------- Diagnostics ----------
    @Volatile var diagFrameCount: Long = 0
    @Volatile var diagLastFrameAtMs: Long = 0
    @Volatile var diagBinding: String = "pas démarré"
    @Volatile var diagError: String? = null

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

            // La vidéo n'est PAS liée au démarrage : sur les vieux téléphones,
            // cumuler 3 use cases (analyse + photo + vidéo) faisait échouer tout
            // le binding → flux vide. Elle est liée dynamiquement pendant
            // l'enregistrement uniquement (voir startVideoRecording).
            buildVideoCapture()

            // NE PAS unbindAll() : le Preview de l'activité est lié séparément.
            // Liens analysis + photo : la combinaison qui fonctionnait avant la vidéo.
            bindAnalysisAndPhoto(provider)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun buildVideoCapture() {
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
        } catch (e: Exception) {
            diagError = "Échec création VideoCapture : ${e.message}"
            android.util.Log.e("VigieCam", diagError!!)
            videoCapture = null
        }
    }

    /** Lie analyse + photo ; en cas d'échec, analyse seule. */
    private fun bindAnalysisAndPhoto(provider: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val attempts = listOf(
            listOfNotNull(analysis, imageCapture),
            listOfNotNull(analysis),
        )
        for (attempt in attempts) {
            try {
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, *attempt.toTypedArray())
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                diagBinding = if (attempt.size == 2) "analyse + photo" else "analyse seule"
                android.util.Log.i("VigieCam", "Binding : $diagBinding")
                return
            } catch (e: Exception) {
                diagError = "Binding ${attempt.size} use case(s) : ${e.message}"
                android.util.Log.w("VigieCam", diagError!!)
            }
        }
        diagBinding = "échec total"
    }

    /** Lie analyse + vidéo (remplace photo pendant l'enregistrement). */
    private fun bindAnalysisAndVideo(provider: ProcessCameraProvider): Boolean {
        val a = analysis ?: return false
        val v = videoCapture ?: return false
        return try {
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                a,
                v,
            )
            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo
            diagBinding = "analyse + vidéo"
            true
        } catch (e: Exception) {
            diagError = "Binding vidéo : ${e.message}"
            android.util.Log.e("VigieCam", diagError!!)
            false
        }
    }

    // ---------- Contrôles caméra (zoom / flash) ----------

    fun setTorch(on: Boolean): Boolean {
        val cc = cameraControl ?: return false
        return try {
            cc.enableTorch(on).get()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun zoomBy(factor: Float): Boolean {
        val cc = cameraControl ?: return false
        val info = cameraInfo ?: return false
        return try {
            val state = info.zoomState.value ?: return false
            val current = state.zoomRatio
            val next = (current * factor).coerceIn(1f, state.maxZoomRatio)
            cc.setZoomRatio(next).get()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resetZoom(): Boolean {
        val cc = cameraControl ?: return false
        return try {
            cc.setZoomRatio(1f).get()
            true
        } catch (_: Exception) {
            false
        }
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
                if (VigieRuntime.settings.value.photoTimestamp) {
                    stampTimestamp(target)
                }
                onResult(true)
            }

            override fun onError(exc: ImageCaptureException) {
                onResult(false)
            }
        })
    }

    /** Dessine le jour + heure en surimpression (bas de l'image). */
    private fun stampTimestamp(file: File) {
        try {
            val src = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return
            val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            src.recycle()
            val canvas = android.graphics.Canvas(bmp)
            val text = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE)
                .format(java.util.Date())
            val size = bmp.width / 28f
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = size
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
            }
            val y = bmp.height - bmp.height / 40f
            val x = bmp.width / 60f
            canvas.drawText(text, x, y, paint)
            val out = java.io.FileOutputStream(file)
            try {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            } finally {
                out.close()
                bmp.recycle()
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Vidéo ----------

    /** Démarre un enregistrement MP4 avec son. Retourne false si déjà en cours ou indisponible. */
    fun startVideoRecording(): Boolean {
        val vc = videoCapture
        val provider = cameraProvider
        if (vc == null || recording != null || provider == null) return false
        // Lie analyse + vidéo (remplace photo temporairement)
        if (!bindAnalysisAndVideo(provider)) return false
        val dir = File(context.filesDir, "videos").apply { mkdirs() }
        val file = File(dir, "vigie_${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file).build()
        return try {
            val prepared = vc.output.prepareRecording(context, options)
            // Active le son (micro) — la permission RECORD_AUDIO est déjà demandée au lancement
            val withAudio = try {
                prepared.withAudioEnabled()
            } catch (_: Exception) {
                prepared
            }
            recording = withAudio
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
                            // Retour au binding analyse + photo
                            bindAnalysisAndPhoto(provider)
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
                diagFrameCount++
                diagLastFrameAtMs = System.currentTimeMillis()
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
                val streamOn = CameraBridge.isStreamActive?.invoke() == true
                if (streamOn && now - lastJpegMs >= 200) {
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
