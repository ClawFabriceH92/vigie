package com.fabrice.vigie.trust

import android.content.Context
import android.content.SharedPreferences

/**
 * Périphériques de confiance : liste des appareils qui représentent
 * « l'utilisateur est là » (téléphone, montre, PC, tablette…).
 *
 * Chaque entrée est identifiée par une MAC (`M:<mac>`) — cas normal — ou par
 * une IP (`I:<ip>`) quand la MAC est masquée (Android 10+, MAC aléatoire).
 * Les anciennes entrées sans préfixe sont traitées comme des MAC.
 */
class TrustedDevices(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE)

    data class TrustedDevice(
        val id: String,
        val name: String,
        val byIp: Boolean,
    ) {
        val mac: String get() = if (byIp) "" else id
        val ip: String get() = if (byIp) id else ""
    }

    fun list(): List<TrustedDevice> {
        val raw = prefs.getStringSet("devices", emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf('|')
            if (idx > 0) {
                val rawId = entry.substring(0, idx)
                val name = entry.substring(idx + 1)
                when {
                    rawId.startsWith("M:") -> TrustedDevice(rawId.removePrefix("M:"), name, byIp = false)
                    rawId.startsWith("I:") -> TrustedDevice(rawId.removePrefix("I:"), name, byIp = true)
                    else -> TrustedDevice(rawId, name, byIp = false)
                }
            } else null
        }.sortedBy { it.name.lowercase() }
    }

    fun isTrusted(mac: String): Boolean {
        val norm = NetworkScanner.normalizeMac(mac)
        if (norm.isEmpty()) return false
        return prefs.getStringSet("devices", emptySet())?.any {
            val rawId = it.substringBefore('|')
            !rawId.startsWith("I:") && NetworkScanner.normalizeMac(rawId.removePrefix("M:")) == norm
        } == true
    }

    fun isTrustedIp(ip: String): Boolean {
        if (ip.isEmpty()) return false
        return prefs.getStringSet("devices", emptySet())?.any {
            it.substringBefore('|').removePrefix("I:") == ip && it.startsWith("I:")
        } == true
    }

    /** Vrai si l'appareil (MAC ou IP) est de confiance. */
    fun isDeviceTrusted(mac: String, ip: String): Boolean =
        (mac.isNotEmpty() && isTrusted(mac)) || (ip.isNotEmpty() && isTrustedIp(ip))

    fun add(mac: String, name: String) {
        val norm = NetworkScanner.normalizeMac(mac)
        if (norm.isEmpty()) return
        val set = prefs.getStringSet("devices", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { !it.startsWith("I:") && NetworkScanner.normalizeMac(it.substringBefore('|').removePrefix("M:")) == norm }
        set.add("M:$norm|${name.ifBlank { norm }}")
        prefs.edit().putStringSet("devices", set).apply()
    }

    fun addByIp(ip: String, name: String) {
        if (ip.isEmpty()) return
        val set = prefs.getStringSet("devices", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.startsWith("I:") && it.substringBefore('|').removePrefix("I:") == ip }
        set.add("I:$ip|${name.ifBlank { ip }}")
        prefs.edit().putStringSet("devices", set).apply()
    }

    fun remove(mac: String) {
        val norm = NetworkScanner.normalizeMac(mac)
        val set = prefs.getStringSet("devices", emptySet())?.toMutableSet() ?: return
        set.removeAll { !it.startsWith("I:") && NetworkScanner.normalizeMac(it.substringBefore('|').removePrefix("M:")) == norm }
        prefs.edit().putStringSet("devices", set).apply()
    }

    fun removeByIp(ip: String) {
        val set = prefs.getStringSet("devices", emptySet())?.toMutableSet() ?: return
        set.removeAll { it.startsWith("I:") && it.substringBefore('|').removePrefix("I:") == ip }
        prefs.edit().putStringSet("devices", set).apply()
    }

    fun count(): Int = prefs.getStringSet("devices", emptySet())?.size ?: 0
}
