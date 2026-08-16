package com.fabrice.vigie

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.fabrice.vigie.camera.CameraBridge
import com.fabrice.vigie.data.EventStore
import com.fabrice.vigie.data.SettingsStore
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
        locked.value = settings.value.pinHash.isNotEmpty()

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
        server = MjpegServer { settings.value.streamPassword }
        try {
            server?.start(10_000, false)
            streamRunning.value = true
        } catch (_: Exception) {
            streamRunning.value = false
        }
    }

    fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        streamRunning.value = false
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
        val present = devices.filter { it.mac.isNotEmpty() && trustedDevices.isTrusted(it.mac) }
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

    fun lock() {
        if (isPinSet()) locked.value = true
    }

    // ---------- Réglages ----------

    fun updateSettings(s: SettingsStore.Settings) {
        settings.value = s
        settingsStore.save(s)
        if (s.trustEnabled) nextScanAt = 0L
        recomputeMode()
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
            while (isActive) {
                if (settings.value.autoUpdate) checkForUpdates()
                // revérifie toutes les 6 h si l'app reste ouverte
                delay(6 * 60 * 60 * 1000L)
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
            }
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

    fun removeTrustedDevice(mac: String) {
        trustedDevices.remove(mac)
        nextScanAt = 0L
    }

    fun isTrusted(mac: String): Boolean = trustedDevices.isTrusted(mac)

    // ---------- Détection de mouvement ----------

    private fun onMotionScore(score: Float) {
        if (mode.value != SurveillanceMode.ARMED) return
        if (burstActive.value) return
        val now = System.currentTimeMillis()
        if (now - lastMotionMs < settings.value.cooldownSec * 1000L) return
        if (score <= settings.value.motionThreshold) return
        lastMotionMs = now
        triggerBurst(score)
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
