package com.fabrice.vigie.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.fabrice.vigie.MainActivity
import com.fabrice.vigie.SurveillanceMode
import com.fabrice.vigie.VigieRuntime
import com.fabrice.vigie.R

/**
 * Service foreground (type camera) : la caméra + la logique de surveillance
 * tournent ici, indépendamment de l'activité. L'app peut être en arrière-plan
 * ou l'écran éteint : la détection et le flux continuent.
 */
class VigieService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "vigie_surveillance"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.fabrice.vigie.STOP"

        fun start(context: Context) {
            val intent = Intent(context, VigieService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VigieService::class.java))
        }
    }

    private var engine: AnalysisEngine? = null
    private val notifHandler = Handler(Looper.getMainLooper())
    private val notifTicker = object : Runnable {
        override fun run() {
            updateNotification()
            refreshDiagnostics()
            notifHandler.postDelayed(this, 5_000)
        }
    }

    private fun refreshDiagnostics() {
        val e = engine ?: return
        VigieRuntime.diagFrameCount.value = e.diagFrameCount
        VigieRuntime.diagLastFrameAtMs.value = e.diagLastFrameAtMs
        VigieRuntime.diagBinding.value = e.diagBinding
        VigieRuntime.diagError.value = e.diagError
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        VigieRuntime.init(this)
        startInForeground()

        engine = AnalysisEngine(this, this).also { it.start() }
        CameraBridge.burstCaptureRequested = { engine?.runBurst() }
        CameraBridge.videoStartRequested = { engine?.startVideoRecording() ?: false }
        CameraBridge.videoStopRequested = { engine?.stopVideoRecording() }
        CameraBridge.videoListProvider = { engine?.videoList() ?: emptyList() }
        CameraBridge.videoFileProvider = { name -> engine?.videoFile(name) }
        CameraBridge.photosListProvider = {
            VigieRuntime.listEvents().flatMap { ev ->
                ev.photos.map { Triple(ev.id, it, ev.timestamp) }
            }
        }
        CameraBridge.photoFileProvider = { eventId, name ->
            val f = VigieRuntime.photoFile(eventId, name)
            if (f.exists()) f else null
        }
        CameraBridge.photoDeleteRequested = { eventId, name ->
            val f = VigieRuntime.photoFile(eventId, name)
            if (f.exists()) f.delete() else false
        }
        CameraBridge.torchRequested = { on -> engine?.setTorch(on) ?: false }
        CameraBridge.zoomRequested = { factor -> engine?.zoomBy(factor) ?: false }
        CameraBridge.zoomResetRequested = { engine?.resetZoom() ?: false }
        CameraBridge.sirenStartRequested = {
            com.fabrice.vigie.siren.SirenPlayer.start(this)
            true
        }
        CameraBridge.sirenStopRequested = {
            com.fabrice.vigie.siren.SirenPlayer.stop()
            true
        }
        CameraBridge.photosClearRequested = {
            com.fabrice.vigie.VigieRuntime.deleteAllEvents()
        }
        CameraBridge.videosClearRequested = {
            com.fabrice.vigie.VigieRuntime.deleteAllVideos()
        }
        CameraBridge.statusProvider = {
            val battery = com.fabrice.vigie.power.PowerMonitorReceiver.batteryLevel(this)
            val res = if (VigieRuntime.settings.value.analysisHeight >= 720) "1280×720" else "640×480"
            val clients = VigieRuntime.streamClients.value
            "{\"battery\":$battery,\"resolution\":\"$res\",\"clients\":$clients,\"recording\":${VigieRuntime.videoRecording.value}}"
        }
        com.fabrice.vigie.power.PowerMonitorReceiver.register(this)
        VigieRuntime.serviceRunning.value = true
        notifHandler.post(notifTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        notifHandler.removeCallbacks(notifTicker)
        CameraBridge.burstCaptureRequested = null
        CameraBridge.videoStartRequested = null
        CameraBridge.videoStopRequested = null
        CameraBridge.videoListProvider = null
        CameraBridge.videoFileProvider = null
        CameraBridge.photosListProvider = null
        CameraBridge.photoFileProvider = null
        CameraBridge.photoDeleteRequested = null
        CameraBridge.torchRequested = null
        CameraBridge.zoomRequested = null
        CameraBridge.zoomResetRequested = null
        CameraBridge.sirenStartRequested = null
        CameraBridge.sirenStopRequested = null
        CameraBridge.photosClearRequested = null
        CameraBridge.videosClearRequested = null
        CameraBridge.statusProvider = null
        engine?.stop()
        engine = null
        VigieRuntime.serviceRunning.value = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, VigieService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Vigie — surveillance active")
            .setContentText(modeLabel())
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Ouvrir", openPending)
            .addAction(0, "Arrêter", stopPending)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun modeLabel(): String = when (VigieRuntime.mode.value) {
        SurveillanceMode.ARMED -> "🟢 Armé — détection active"
        SurveillanceMode.DISARMED -> "⚪ Désarmé"
        SurveillanceMode.ARMING -> "⏳ Armement dans ${VigieRuntime.armingRemainingSec.value}s"
        SurveillanceMode.STARTING -> "Démarrage…"
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Surveillance",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "État de la surveillance Vigie"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
