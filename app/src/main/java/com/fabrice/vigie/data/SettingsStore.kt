package com.fabrice.vigie.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages de l'application (persistés en SharedPreferences).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vigie_settings", Context.MODE_PRIVATE)

    data class Settings(
        val pinHash: String = "",
        val streamPassword: String = "vigie",
        val motionThreshold: Int = 14,        // seuil de mouvement (0..100)
        val burstCount: Int = 3,              // photos par événement
        val burstIntervalMs: Long = 800,      // intervalle entre photos (ms)
        val cooldownSec: Int = 30,            // silence après un événement (s)
        val trustScanIntervalSec: Int = 60,   // période de scan réseau (s)
        val trustDisarmDelaySec: Int = 120,   // délai sans confiance avant armement (s)
        val trustEnabled: Boolean = true,     // mode confiance actif
        val autoUpdate: Boolean = true,       // vérifier + installer les MAJ automatiquement
        val emailRecipient: String = "",      // destinataire pour l'envoi des photos par email
        val streamUser: String = "vigie",     // nom d'utilisateur pour l'accès distant
        val jpegQuality: Int = 80,            // qualité du flux MJPEG (50..95)
        val analysisHeight: Int = 480,        // hauteur d'analyse/flux (480 ou 720)
        val streamPort: Int = 8080,           // port du serveur HTTP (flux + contrôle)
        val screenTimeoutMin: Int = 0,        // 0 = écran toujours allumé ; sinon extinction après X min
        val batteryAlertThreshold: Int = 20,  // % de batterie déclenchant l'alerte (0 = désactivé)
        val motionCaptureMode: String = "photos", // "photos" (rafale) ou "video" (MP4 + son)
        val motionVideoDurationSec: Int = 15, // durée d'enregistrement vidéo sur mouvement (s)
        val photoTimestamp: Boolean = true,   // horodatage en surimpression sur les photos
        val retentionDays: Int = 0,           // 0 = garder indéfiniment ; sinon purge après X jours
        val autoEmail: Boolean = false,       // envoi auto des photos par email après un événement
        val smtpHost: String = "",
        val smtpPort: Int = 587,
        val smtpUser: String = "",
        val smtpPassword: String = "",
        val smtpSsl: Boolean = true,
    )

    fun load(): Settings = Settings(
        pinHash = prefs.getString("pin_hash", "") ?: "",
        streamPassword = prefs.getString("stream_password", "vigie") ?: "vigie",
        motionThreshold = prefs.getInt("motion_threshold", 14),
        burstCount = prefs.getInt("burst_count", 3),
        burstIntervalMs = prefs.getLong("burst_interval_ms", 800),
        cooldownSec = prefs.getInt("cooldown_sec", 30),
        trustScanIntervalSec = prefs.getInt("trust_scan_interval_sec", 60),
        trustDisarmDelaySec = prefs.getInt("trust_disarm_delay_sec", 120),
        trustEnabled = prefs.getBoolean("trust_enabled", true),
        autoUpdate = prefs.getBoolean("auto_update", true),
        emailRecipient = prefs.getString("email_recipient", "") ?: "",
        streamUser = prefs.getString("stream_user", "vigie") ?: "vigie",
        jpegQuality = prefs.getInt("jpeg_quality", 80),
        analysisHeight = prefs.getInt("analysis_height", 480),
        streamPort = prefs.getInt("stream_port", 8080),
        screenTimeoutMin = prefs.getInt("screen_timeout_min", 0),
        batteryAlertThreshold = prefs.getInt("battery_alert_threshold", 20),
        motionCaptureMode = prefs.getString("motion_capture_mode", "photos") ?: "photos",
        motionVideoDurationSec = prefs.getInt("motion_video_duration_sec", 15),
        photoTimestamp = prefs.getBoolean("photo_timestamp", true),
        retentionDays = prefs.getInt("retention_days", 0),
        autoEmail = prefs.getBoolean("auto_email", false),
        smtpHost = prefs.getString("smtp_host", "") ?: "",
        smtpPort = prefs.getInt("smtp_port", 587),
        smtpUser = prefs.getString("smtp_user", "") ?: "",
        smtpPassword = prefs.getString("smtp_password", "") ?: "",
        smtpSsl = prefs.getBoolean("smtp_ssl", true),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString("pin_hash", s.pinHash)
            .putString("stream_password", s.streamPassword)
            .putInt("motion_threshold", s.motionThreshold)
            .putInt("burst_count", s.burstCount)
            .putLong("burst_interval_ms", s.burstIntervalMs)
            .putInt("cooldown_sec", s.cooldownSec)
            .putInt("trust_scan_interval_sec", s.trustScanIntervalSec)
            .putInt("trust_disarm_delay_sec", s.trustDisarmDelaySec)
            .putBoolean("trust_enabled", s.trustEnabled)
            .putBoolean("auto_update", s.autoUpdate)
            .putString("email_recipient", s.emailRecipient)
            .putString("stream_user", s.streamUser)
            .putInt("jpeg_quality", s.jpegQuality)
            .putInt("analysis_height", s.analysisHeight)
            .putInt("stream_port", s.streamPort)
            .putInt("screen_timeout_min", s.screenTimeoutMin)
            .putInt("battery_alert_threshold", s.batteryAlertThreshold)
            .putString("motion_capture_mode", s.motionCaptureMode)
            .putInt("motion_video_duration_sec", s.motionVideoDurationSec)
            .putBoolean("photo_timestamp", s.photoTimestamp)
            .putInt("retention_days", s.retentionDays)
            .putBoolean("auto_email", s.autoEmail)
            .putString("smtp_host", s.smtpHost)
            .putInt("smtp_port", s.smtpPort)
            .putString("smtp_user", s.smtpUser)
            .putString("smtp_password", s.smtpPassword)
            .putBoolean("smtp_ssl", s.smtpSsl)
            .apply()
    }
}
