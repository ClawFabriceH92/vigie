package com.fabrice.vigie

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.vigie.camera.CameraBridge
import com.fabrice.vigie.data.EventStore
import com.fabrice.vigie.data.SettingsStore
import com.fabrice.vigie.security.PinHasher
import com.fabrice.vigie.stream.MjpegServer
import com.fabrice.vigie.trust.NetworkScanner
import com.fabrice.vigie.trust.TrustMonitor
import com.fabrice.vigie.trust.TrustedDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SurveillanceMode { STARTING, DISARMED, ARMING, ARMED }

/**
 * Cœur de Vigie : PIN, réglages, mode confiance (scan réseau), détection de
 * mouvement, rafale de photos locale, serveur de flux.
 */
class SurveillanceViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val trustedDevices = TrustedDevices(application)
    private val eventStore = EventStore(application)
    private val server = MjpegServer { settings.value.streamPassword }

    // ---------- État exposé ----------

    val settings = MutableStateFlow(settingsStore.load())
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

    // ---------- État interne ----------

    private var lastTrustSeenMs = 0L
    private var lastMotionMs = 0L
    private var manualOverride: Boolean? = null
    @Volatile private var nextScanAt = 0L
    private var pendingPhotos = 0

    init {
        locked.value = settings.value.pinHash.isNotEmpty()
        CameraBridge.onMotionScore = { score -> onMotionScore(score) }
        CameraBridge.onJpegFrame = { jpeg -> server.publish(jpeg) }
        CameraBridge.onBurstPhoto = { file -> onBurstPhoto(file) }
        refreshLocalIp()
        refreshStats()
        try {
            server.start(10_000, false)
            streamRunning.value = true
        } catch (_: Exception) {
            streamRunning.value = false
        }
        viewModelScope.launch { scanLoop() }
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

    // ---------- Navigation ----------

    fun setScreen(s: Int) {
        screen.value = s
    }

    // ---------- Réglages ----------

    fun updateSettings(s: SettingsStore.Settings) {
        settings.value = s
        settingsStore.save(s)
        if (s.trustEnabled) nextScanAt = 0L // rescan immédiat
        recomputeMode()
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

    private fun scanLoop() {
        viewModelScope.launch {
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

    // ---------- Périphériques de confiance ----------

    /** Scan immédiat + refresh de l'écran confiance. */
    fun rescanNow() {
        nextScanAt = 0L
    }

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

    /** Annulation de sécurité : si le burst n'aboutit pas, on débloque après 30 s. */
    fun checkBurstTimeout() {
        if (burstActive.value && pendingPhotos > 0 &&
            System.currentTimeMillis() - lastMotionMs > 30_000
        ) {
            burstActive.value = false
            pendingPhotos = 0
        }
    }

    // ---------- Réseau / flux ----------

    fun refreshLocalIp() {
        localIp.value = withContextSafe { NetworkScanner.detectSubnet()?.first }
    }

    private fun <T> withContextSafe(block: () -> T): T? {
        var result: T? = null
        try {
            runCatching { result = block() }
        } catch (_: Exception) {
        }
        return result
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

    override fun onCleared() {
        server.stop()
        super.onCleared()
    }
}
