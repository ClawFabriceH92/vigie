package com.fabrice.vigie.trust

import android.content.Context
import android.content.SharedPreferences

/**
 * Périphériques de confiance : liste des MAC adresses qui représentent
 * « l'utilisateur est là » (téléphone, montre, PC, tablette…).
 */
class TrustedDevices(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE)

    data class TrustedDevice(
        val mac: String,
        val name: String,
    )

    fun list(): List<TrustedDevice> {
        val raw = prefs.getStringSet("devices", emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf('|')
            if (idx > 0) TrustedDevice(entry.substring(0, idx), entry.substring(idx + 1)) else null
        }.sortedBy { it.name.lowercase() }
    }

    fun isTrusted(mac: String): Boolean {
        val norm = NetworkScanner.normalizeMac(mac)
        if (norm.isEmpty()) return false
        return prefs.getStringSet("devices", emptySet())?.any {
            it.substringBefore('|') == norm
        } == true
    }

    fun add(mac: String, name: String) {
        val norm = NetworkScanner.normalizeMac(mac)
        if (norm.isEmpty()) return
        val set = prefs.getStringSet("devices", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.substringBefore('|') == norm }
        set.add("$norm|${name.ifBlank { norm }}")
        prefs.edit().putStringSet("devices", set).apply()
    }

    fun remove(mac: String) {
        val norm = NetworkScanner.normalizeMac(mac)
        val set = prefs.getStringSet("devices", emptySet())?.toMutableSet() ?: return
        set.removeAll { it.substringBefore('|') == norm }
        prefs.edit().putStringSet("devices", set).apply()
    }

    fun count(): Int = prefs.getStringSet("devices", emptySet())?.size ?: 0
}
