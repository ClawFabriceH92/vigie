package com.fabrice.vigie

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.fabrice.vigie.camera.CameraBridge
import com.fabrice.vigie.data.EventStore
import com.fabrice.vigie.data.SettingsStore
import com.fabrice.vigie.intercom.IntercomServer
import com.fabrice.vigie.security.PinHasher
import com.fabrice.vigie.stream.MjpegServer
import com.fabrice.vigie.trust.NetworkScanner
import com.fabrice.vigie.trust.TrustMonitor
import com.fabrice.vigie.trust.TrustedDevices
import com.fabrice.vigie.update.AutoUpdater
import com.fabrice.vigie.update.UpdateChecker
import com.fabrice.vigie.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SurveillanceMode { STARTING, DISARMED, ARMING, ARMED }

/**
 * État global + logique de Vigie, indépendant de l'UI et du cycle de vie de
 * l'activité. Initialisé par [VigieService] (foreground) → la surveillance
 * continue écran éteint / app en arrière-plan.
 */
object VigieRuntime {

    private lateinit var appContext: Context
    private lateinit var settingsStore: SettingsStore
    private lateinit var trustedDevices: TrustedDevices
    private lateinit var eventStore: EventStore
    private var server: MjpegServer? = null
    private var intercom: IntercomServer? = null
    private var scope: CoroutineScope? = null

    // ---------- État exposé ----------

    val settings = MutableStateFlow(SettingsStore.Settings())
    val locked = MutableStateFlow(false)
    val screen = MutableStateFlow(0) // 0 accueil, 1 réglages, 2 confiance, 3 journal
    val mode = MutableStateFlow(SurveillanceMode.STARTING)
    val isManual = MutableStateFlow(false)
    val armingRemainingSec = MutableStateFlow(0L)
    val trustedPresent = MutableStateFlow<List<NetworkScanner.Device>>(emptyList())
    val lastScanDevices = MutableStateFlow<List<NetworkScanner.Device>>(emptyList())
    val lastScanAt = MutableStateFlow<Long?>(null)
    val isScanning = MutableStateFlow(false)
    val lastEvent = MutableStateFlow<EventStore.Event?>(null)
    val burstActive = MutableStateFlow(false)
    val burstTargetDir = MutableStateFlow<File?>(null)
    val localIp = MutableStateFlow<String?>(null)
    val streamRunning = MutableStateFlow(false)
    val eventCount = MutableStateFlow(0)
    val eventsSizeMb = MutableStateFlow(0f)
    val serviceRunning = MutableStateFlow(false)
    val intercomMuted = MutableStateFlow(false)
    val videoRecording = MutableStateFlow(false)
    val streamClients = MutableStateFlow(0)
    val diagFrameCount = MutableStateFlow(0L)
    val diagLastFrameAtMs = MutableStateFlow(0L)
    val diagBinding = MutableStateFlow("pas démarré")
    val diagError = MutableStateFlow<String?>(null)
    val intercomRunning = MutableStateFlow(false)
    val intercomPort = MutableStateFlow(8081)
    val intercomError = MutableStateFlow<String?>(null)

    // ---------- Interne ----------

    private var lastTrustSeenMs = 0L
    private var lastMotionMs = 0L
    private var manualOverride: Boolean? = null
    @Volatile private var nextScanAt = 0L
    private var pendingPhotos = 0

    private val initialized: Boolean get() = ::settingsStore.isInitialized

    /** Initialisation unique (idempotente) — appelée par le service foreground. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        settingsStore = SettingsStore(appContext)
        trustedDevices = TrustedDevices(appContext)
        eventStore = EventStore(appContext)
        settings.value = settingsStore.load()
        // PIN par défaut 0000 : au 1er lancement on ne demande pas de créer un code,
        // on verrouille avec 0000 (l'utilisateur peut le changer dans Réglages).
        if (settings.value.pinHash.isEmpty()) {
            val s = settings.value.copy(pinHash = PinHasher.hash("0000"))
            settings.value = s
            settingsStore.save(s)
            locked.value = false
        } else {
            locked.value = settings.value.pinHash.isNotEmpty()
        }

        CameraBridge.onMotionScore = { score -> onMotionScore(score) }
        CameraBridge.onJpegFrame = { jpeg -> server?.publish(jpeg) }
        CameraBridge.onBurstPhoto = { file -> onBurstPhoto(file) }

        startServer()
        refreshLocalIp()
        refreshStats()
        startScanLoop()
        startUpdateLoop()
    }

    private fun startServer() {
        val port = settings.value.streamPort.coerceIn(1024, 65535)
        val intercomPortCandidate = if (port + 1 <= 65535) port + 1 else port - 1
        server = MjpegServer(
            port = port,
            userProvider = { settings.value.streamUser },
            passwordProvider = { settings.value.streamPassword },
            intercomPortProvider = { intercomPort.value },
        )
        server?.onClientsChanged = { count ->
            streamClients.value = count
            notifyStreamClients(count)
        }
        CameraBridge.isStreamActive = { server?.isStreaming() == true }
        try {
            server?.start(10_000, false)
            streamRunning.value = true
        } catch (_: Exception) {
            streamRunning.value = false
        }
        // Intercom : démarre indépendamment ; cherche un port libre si le port
        // prévu est déjà pris (ex: un autre service sur le réseau).
        intercomError.value = null
        var started = false
        var candidate = intercomPortCandidate
        repeat(5) {
            val ic = IntercomServer(appContext, { settings.value.streamPassword }, candidate)
            try {
                ic.start()
                intercom = ic
                intercomPort.value = candidate
                intercomRunning.value = true
                started = true
                return@repeat
            } catch (e: Exception) {
                intercomError.value = "Échec port $candidate : ${e.message}"
                candidate = if (candidate + 1 <= 65535) candidate + 1 else 1024
            }
        }
        if (!started) {
            intercom = null
            intercomRunning.value = false
        }
    }

    fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        streamRunning.value = false
        streamClients.value = 0
        CameraBridge.isStreamActive = null
        try {
            intercom?.shutdown()
        } catch (_: Exception) {
        }
        intercom = null
        intercomRunning.value = false
    }

    private fun startScanLoop() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (settings.value.trustEnabled && now >= nextScanAt) {
                    isScanning.value = true
                    val devices = withContext(Dispatchers.IO) { NetworkScanner.scan() }
                    isScanning.value = false
                    lastScanAt.value = System.currentTimeMillis()
                    updateTrust(devices)
                    nextScanAt = SystemClock.elapsedRealtime() + settings.value.trustScanIntervalSec * 1000L
                }
                recomputeMode()
                delay(5_000)
            }
        }
    }

    private fun updateTrust(devices: List<NetworkScanner.Device>) {
        lastScanDevices.value = devices
        val present = devices.filter { trustedDevices.isDeviceTrusted(it.mac, it.ip) }
        trustedPresent.value = present
        if (present.isNotEmpty()) {
            lastTrustSeenMs = System.currentTimeMillis()
        }
    }

    private fun recomputeMode() {
        if (manualOverride != null) {
            mode.value = if (manualOverride == true) SurveillanceMode.ARMED else SurveillanceMode.DISARMED
            return
        }
        if (!settings.value.trustEnabled) {
            mode.value = SurveillanceMode.DISARMED
            return
        }
        val decision = TrustMonitor.decide(
            trustedPresent = trustedPresent.value.map { it.mac },
            lastTrustSeenMs = lastTrustSeenMs,
            nowMs = System.currentTimeMillis(),
            disarmDelayMs = settings.value.trustDisarmDelaySec * 1000L,
        )
        mode.value = when (decision.state) {
            TrustMonitor.ArmState.DISARMED -> SurveillanceMode.DISARMED
            TrustMonitor.ArmState.ARMING -> SurveillanceMode.ARMING
            TrustMonitor.ArmState.ARMED -> SurveillanceMode.ARMED
        }
        armingRemainingSec.value = decision.secondsUntilArmed
    }

    // ---------- PIN ----------

    fun isPinSet(): Boolean = settings.value.pinHash.isNotEmpty()

    fun setPin(pin: String) {
        val s = settings.value.copy(pinHash = PinHasher.hash(pin))
        settings.value = s
        settingsStore.save(s)
        locked.value = false
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!PinHasher.verify(oldPin, settings.value.pinHash)) return false
        setPin(newPin)
        return true
    }

    fun verifyPin(pin: String): Boolean {
        if (!PinHasher.verify(pin, settings.value.pinHash)) return false
        locked.value = false
        return true
    }

    /** Déverrouillage sans PIN (biométrie réussie). */
    fun unlock() {
        locked.value = false
    }

    fun lock() {
        if (isPinSet()) locked.value = true
    }

    // ---------- Réglages ----------

    fun updateSettings(s: SettingsStore.Settings) {
        val portChanged = s.streamPort != settings.value.streamPort
        settings.value = s
        settingsStore.save(s)
        if (s.trustEnabled) nextScanAt = 0L
        recomputeMode()
        if (portChanged) {
            // Redémarre les serveurs avec le nouveau port
            stopServer()
            startServer()
        }
    }

    // ---------- Mise à jour ----------
    enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, ERROR }

    data class UpdateUiState(
        val status: UpdateStatus = UpdateStatus.IDLE,
        val info: UpdateInfo? = null,
        val message: String? = null,
    )

    val updateState = MutableStateFlow(UpdateUiState())

    private var updateLoopStarted = false

    private fun startUpdateLoop() {
        if (updateLoopStarted) return
        updateLoopStarted = true
        val s = scope ?: return
        s.launch {
            // Vérification immédiate au lancement
            if (settings.value.autoUpdate) checkForUpdates()
            while (isActive) {
                val now = java.util.Calendar.getInstance()
                val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = now.get(java.util.Calendar.MINUTE)
                if (settings.value.autoUpdate && hour == 14 && minute == 0) {
                    checkForUpdates()
                    // évite de re-déclencher plusieurs fois dans la même minute
                    delay(61_000)
                } else {
                    // toutes les 30 s on surveille l'heure (léger) ; vérif réseau toutes les 6 h en secours
                    delay(30_000)
                }
            }
        }
    }

    /** Vérifie GitHub Releases ; télécharge automatiquement si [installIfAvailable] et permission OK. */
    fun checkForUpdates(installIfAvailable: Boolean = settings.value.autoUpdate) {
        if (updateState.value.status == UpdateStatus.CHECKING) return
        updateState.value = UpdateUiState(UpdateStatus.CHECKING)
        val s = scope ?: return
        s.launch {
            val info = withContext(Dispatchers.IO) { UpdateChecker.latestWithApk() }
            if (info == null) {
                updateState.value = UpdateUiState(UpdateStatus.ERROR, message = "Vérification impossible (réseau ?)")
                return@launch
            }
            val current = BuildConfig.VERSION_NAME
            if (UpdateChecker.compareVersions(info.versionName, current) <= 0) {
                updateState.value = UpdateUiState(UpdateStatus.UP_TO_DATE, info = info)
                return@launch
            }
            updateState.value = UpdateUiState(UpdateStatus.AVAILABLE, info = info)
            if (installIfAvailable && AutoUpdater.canRequestInstalls(appContext)) {
                if (AutoUpdater.download(appContext, info.downloadUrl)) {
                    updateState.value = UpdateUiState(UpdateStatus.DOWNLOADING, info = info)
                }
            } else if (installIfAvailable) {
                // Permission d'installation manquante → notifie pour que l'utilisateur l'accorde
                notifyUpdatePermissionNeeded(info)
            }
        }
    }

    /** Notification : une mise à jour est dispo mais il faut autoriser l'installation. */
    private fun notifyUpdatePermissionNeeded(info: UpdateInfo) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                "vigie_updates",
                "Mises à jour",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Mises à jour automatiques de Vigie"
            }
            nm.createNotificationChannel(channel)

            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                appContext,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(appContext, "vigie_updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("⬆ Mise à jour Vigie v${info.versionName} disponible")
                .setContentText("Touchez pour autoriser l'installation, puis la mise à jour s'installera automatiquement.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(1, notification)
        } catch (_: Exception) {
        }
    }

    /** Force le téléchargement (bouton manuel), ouvre les réglages système si permission manquante. */
    fun installUpdate() {
        val info = updateState.value.info ?: return
        if (!AutoUpdater.canRequestInstalls(appContext)) {
            AutoUpdater.openInstallSettings(appContext)
            return
        }
        if (AutoUpdater.download(appContext, info.downloadUrl)) {
            updateState.value = UpdateUiState(UpdateStatus.DOWNLOADING, info = info)
        }
    }

    // Intercom
    fun setIntercomMuted(muted: Boolean) {
        intercomMuted.value = muted
        try {
            intercom?.setMuted(muted)
        } catch (_: Exception) {
        }
    }

    // ---------- Vidéo (déclenchable à distance) ----------

    fun startVideoRecording(): Boolean = CameraBridge.videoStartRequested?.invoke() ?: false
    fun stopVideoRecording(): String? = CameraBridge.videoStopRequested?.invoke()
    fun videoList(): List<Pair<String, Long>> = CameraBridge.videoListProvider?.invoke() ?: emptyList()
    fun videoFile(name: String): File? = CameraBridge.videoFileProvider?.invoke(name)

    /** Notification Android : nombre de personnes connectées au flux. */
    private fun notifyStreamClients(count: Int) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                "vigie_stream",
                "Stream",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Personnes connectées au flux vidéo"
            }
            nm.createNotificationChannel(channel)
            if (count <= 0) {
                nm.cancel(2)
                return
            }
            val notification = NotificationCompat.Builder(appContext, "vigie_stream")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("📡 Stream en cours")
                .setContentText(
                    if (count == 1) "1 personne connectée au flux"
                    else "$count personnes connectées au flux"
                )
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(2, notification)
        } catch (_: Exception) {
        }
    }

    // ---------- Mode / confiance ----------

    fun setManualMode(armed: Boolean) {
        manualOverride = armed
        isManual.value = true
        recomputeMode()
    }

    fun setAutoMode() {
        manualOverride = null
        isManual.value = false
        nextScanAt = 0L
        recomputeMode()
    }

    fun rescanNow() {
        nextScanAt = 0L
    }

    // ---------- Périphériques de confiance ----------

    fun trustedDevicesList(): List<TrustedDevices.TrustedDevice> = trustedDevices.list()

    fun addTrustedDevice(mac: String, name: String) {
        trustedDevices.add(mac, name)
        nextScanAt = 0L
    }

    fun addTrustedDeviceByIp(ip: String, name: String) {
        trustedDevices.addByIp(ip, name)
        nextScanAt = 0L
    }

    fun removeTrustedDevice(mac: String) {
        trustedDevices.remove(mac)
        nextScanAt = 0L
    }

    fun removeTrustedDeviceByIp(ip: String) {
        trustedDevices.removeByIp(ip)
        nextScanAt = 0L
    }

    fun isTrusted(mac: String): Boolean = trustedDevices.isTrusted(mac)

    fun isTrustedIp(ip: String): Boolean = trustedDevices.isTrustedIp(ip)

    fun isDeviceTrusted(mac: String, ip: String): Boolean = trustedDevices.isDeviceTrusted(mac, ip)

    // ---------- Détection de mouvement ----------

    private fun onMotionScore(score: Float) {
        if (mode.value != SurveillanceMode.ARMED) return
        if (burstActive.value || videoRecording.value) return
        val now = System.currentTimeMillis()
        if (now - lastMotionMs < settings.value.cooldownSec * 1000L) return
        if (score <= settings.value.motionThreshold) return
        lastMotionMs = now
        if (settings.value.motionCaptureMode == "video") {
            triggerVideo(score)
        } else {
            triggerBurst(score)
        }
    }

    /** Mode vidéo : enregistre MP4 + son pendant [motionVideoDurationSec]. */
    private fun triggerVideo(score: Float) {
        val (dir, event) = eventStore.createEvent(score, "mouvement")
        burstTargetDir.value = dir
        lastEvent.value = event
        val ok = CameraBridge.videoStartRequested?.invoke() ?: false
        if (!ok) {
            // Repli photos si la vidéo est indisponible
            triggerBurst(score)
            return
        }
        val s = scope ?: return
        s.launch {
            delay(settings.value.motionVideoDurationSec * 1000L)
            if (videoRecording.value) {
                val name = CameraBridge.videoStopRequested?.invoke()
                if (name != null) {
                    eventStore.setEventVideo(event.id, name)
                }
            }
        }
    }

    private fun triggerBurst(score: Float) {
        val (dir, event) = eventStore.createEvent(score, "mouvement")
        burstTargetDir.value = dir
        lastEvent.value = event
        pendingPhotos = 0
        burstActive.value = true
        CameraBridge.burstCaptureRequested?.invoke()
    }

    private fun onBurstPhoto(file: File) {
        pendingPhotos++
        if (pendingPhotos >= settings.value.burstCount) {
            burstActive.value = false
            burstTargetDir.value = null
            pendingPhotos = 0
            refreshStats()
        }
    }

    // ---------- Réseau / flux ----------

    fun refreshLocalIp() {
        try {
            localIp.value = NetworkScanner.detectSubnet()?.first
        } catch (_: Exception) {
        }
    }

    // ---------- Journal ----------

    fun listEvents(): List<EventStore.Event> = eventStore.listEvents()

    fun photoFile(eventId: String, photoName: String): File = eventStore.photoFile(eventId, photoName)

    fun deleteEvent(eventId: String) {
        eventStore.deleteEvent(eventId)
        refreshStats()
    }

    fun deleteAllEvents() {
        eventStore.eventsDir().listFiles()?.forEach { it.deleteRecursively() }
        refreshStats()
    }

    private fun refreshStats() {
        val events = eventStore.listEvents()
        eventCount.value = events.size
        eventsSizeMb.value = eventStore.totalSizeBytes() / (1024f * 1024f)
    }
}
