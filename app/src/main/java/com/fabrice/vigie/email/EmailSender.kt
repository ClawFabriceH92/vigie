package com.fabrice.vigie.email

import com.fabrice.vigie.data.SettingsStore
import java.io.File
import java.util.Properties
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Envoi d'email SMTP (JavaMail) avec photos en pièces jointes.
 * Utilisé par l'option « envoi automatique des photos par email ».
 */
object EmailSender {

    private const val TAG = "VigieEmail"

    /** Envoie un email de test (texte seul) pour valider la config SMTP. Retourne true si envoyé. */
    fun sendTestEmail(settings: SettingsStore.Settings): Boolean {
        if (settings.smtpHost.isBlank() || settings.smtpUser.isBlank()) return false
        val recipient = settings.emailRecipient.trim()
        if (recipient.isEmpty()) return false
        return try {
            val props = Properties().apply {
                put("mail.smtp.host", settings.smtpHost)
                put("mail.smtp.port", settings.smtpPort.toString())
                put("mail.smtp.auth", "true")
                if (settings.smtpSsl) {
                    put("mail.smtp.ssl.enable", "true")
                } else {
                    put("mail.smtp.starttls.enable", "true")
                }
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "20000")
            }
            val session = Session.getInstance(props)
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(settings.smtpUser))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
                subject = "Test Vigie — configuration SMTP OK"
                setText("Ceci est un test de la configuration SMTP de Vigie. Si tu reçois ce message, l'envoi automatique fonctionnera.")
            }
            Transport.send(msg)
            android.util.Log.i(TAG, "Email de test envoyé à $recipient")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Échec test email : ${e.message}")
            false
        }
    }

    /** Envoie les photos d'un événement à l'adresse configurée. Retourne true si envoyé. */
    fun sendEventPhotos(settings: SettingsStore.Settings, photos: List<File>, eventLabel: String): Boolean {
        if (!settings.autoEmail || settings.smtpHost.isBlank() || settings.smtpUser.isBlank()) return false
        val recipient = settings.emailRecipient.trim()
        if (recipient.isEmpty() || photos.isEmpty()) return false
        return try {
            val props = Properties().apply {
                put("mail.smtp.host", settings.smtpHost)
                put("mail.smtp.port", settings.smtpPort.toString())
                put("mail.smtp.auth", "true")
                if (settings.smtpSsl) {
                    put("mail.smtp.ssl.enable", "true")
                } else {
                    put("mail.smtp.starttls.enable", "true")
                }
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "20000")
            }
            val session = Session.getInstance(props)
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(settings.smtpUser))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
                subject = "Vigie — $eventLabel"
                setText("Événement détecté par Vigie : $eventLabel\n\nPhotos jointes (${photos.size}).\n\n— Vigie")
            }
            val multipart = MimeMultipart()
            val textPart = MimeBodyPart()
            textPart.setText("Événement détecté par Vigie : $eventLabel — ${photos.size} photo(s) jointe(s).")
            multipart.addBodyPart(textPart)
            for ((i, f) in photos.withIndex()) {
                if (i >= 6) break // limite raisonnable de pièces jointes
                if (!f.exists()) continue
                val att = MimeBodyPart()
                att.attachFile(f)
                att.fileName = "vigie_${System.currentTimeMillis()}_$i.jpg"
                multipart.addBodyPart(att)
            }
            msg.setContent(multipart)
            Transport.send(msg)
            android.util.Log.i(TAG, "Email envoyé à $recipient (${photos.size} photos)")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Échec envoi email : ${e.message}")
            false
        }
    }
}
