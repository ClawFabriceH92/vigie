package com.fabrice.vigie

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.fabrice.vigie.data.EventStore
import com.fabrice.vigie.data.SettingsStore
import com.fabrice.vigie.trust.NetworkScanner
import com.fabrice.vigie.trust.TrustedDevices
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Proxy mince vers [VigieRuntime] : garde l'interface utilisée par l'UI,
 * mais toute la logique et l'état vivent dans le runtime global (survit à
 * l'activité, alimenté par le service foreground).
 */
class SurveillanceViewModel(application: Application) : AndroidViewModel(application) {

    // L'état vit dans VigieRuntime : init idempotent au cas où le service
    // n'a pas encore démarré.
    init {
        VigieRuntime.init(application)
    }

    val settings: StateFlow<SettingsStore.Settings> get() = VigieRuntime.settings
    val locked: StateFlow<Boolean> get() = VigieRuntime.locked
    val screen: StateFlow<Int> get() = VigieRuntime.screen
    val mode: StateFlow<SurveillanceMode> get() = VigieRuntime.mode
    val isManual: StateFlow<Boolean> get() = VigieRuntime.isManual
    val armingRemainingSec: StateFlow<Long> get() = VigieRuntime.armingRemainingSec
    val trustedPresent: StateFlow<List<NetworkScanner.Device>> get() = VigieRuntime.trustedPresent
    val lastScanDevices: StateFlow<List<NetworkScanner.Device>> get() = VigieRuntime.lastScanDevices
    val lastScanAt: StateFlow<Long?> get() = VigieRuntime.lastScanAt
    val isScanning: StateFlow<Boolean> get() = VigieRuntime.isScanning
    val lastEvent: StateFlow<EventStore.Event?> get() = VigieRuntime.lastEvent
    val burstActive: StateFlow<Boolean> get() = VigieRuntime.burstActive
    val burstTargetDir: StateFlow<File?> get() = VigieRuntime.burstTargetDir
    val localIp: StateFlow<String?> get() = VigieRuntime.localIp
    val streamRunning: StateFlow<Boolean> get() = VigieRuntime.streamRunning
    val eventCount: StateFlow<Int> get() = VigieRuntime.eventCount
    val eventsSizeMb: StateFlow<Float> get() = VigieRuntime.eventsSizeMb
    val serviceRunning: StateFlow<Boolean> get() = VigieRuntime.serviceRunning

    // PIN
    fun isPinSet(): Boolean = VigieRuntime.isPinSet()
    fun setPin(pin: String) = VigieRuntime.setPin(pin)
    fun changePin(oldPin: String, newPin: String): Boolean = VigieRuntime.changePin(oldPin, newPin)
    fun verifyPin(pin: String): Boolean = VigieRuntime.verifyPin(pin)
    fun lock() = VigieRuntime.lock()

    // Navigation
    fun setScreen(s: Int) = VigieRuntime.screen.let { it.value = s }

    // Réglages
    fun updateSettings(s: SettingsStore.Settings) = VigieRuntime.updateSettings(s)

    // Mode
    fun setManualMode(armed: Boolean) = VigieRuntime.setManualMode(armed)
    fun setAutoMode() = VigieRuntime.setAutoMode()
    fun rescanNow() = VigieRuntime.rescanNow()

    // Confiance
    fun trustedDevicesList(): List<TrustedDevices.TrustedDevice> = VigieRuntime.trustedDevicesList()
    fun addTrustedDevice(mac: String, name: String) = VigieRuntime.addTrustedDevice(mac, name)
    fun removeTrustedDevice(mac: String) = VigieRuntime.removeTrustedDevice(mac)
    fun isTrusted(mac: String): Boolean = VigieRuntime.isTrusted(mac)

    // Journal
    fun listEvents(): List<EventStore.Event> = VigieRuntime.listEvents()
    fun photoFile(eventId: String, photoName: String): File = VigieRuntime.photoFile(eventId, photoName)
    fun deleteEvent(eventId: String) = VigieRuntime.deleteEvent(eventId)
    fun deleteAllEvents() = VigieRuntime.deleteAllEvents()
}
