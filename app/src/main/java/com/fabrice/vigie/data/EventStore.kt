package com.fabrice.vigie.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des événements de mouvement : un dossier par événement dans
 * filesDir/events/<yyyyMMdd_HHmmss>/ contenant les photos et event.json.
 */
class EventStore(private val context: Context) {

    data class Event(
        val id: String,           // nom du dossier = horodatage
        val timestamp: Long,
        val score: Float,
        val photos: List<String>, // noms de fichiers
        val mode: String,         // "armé" / "manuel"
        val video: String? = null, // nom du MP4 si capture vidéo
    )

    private val root: File get() = File(context.filesDir, "events")

    fun eventsDir(): File = root.apply { mkdirs() }

    /** Crée un dossier d'événement et retourne son chemin + l'Event. */
    fun createEvent(score: Float, mode: String): Pair<File, Event> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(root, stamp)
        dir.mkdirs()
        val event = Event(id = stamp, timestamp = System.currentTimeMillis(), score = score, photos = emptyList(), mode = mode)
        writeMeta(dir, event)
        return dir to event
    }

    /** Ajoute une photo à un événement (retourne le fichier créé). */
    fun addPhoto(dir: File, index: Int): File {
        val f = File(dir, "photo_${index.toString().padStart(2, '0')}.jpg")
        return f
    }

    private fun writeMeta(dir: File, event: Event) {
        val json = JSONObject()
            .put("id", event.id)
            .put("timestamp", event.timestamp)
            .put("score", event.score.toDouble())
            .put("mode", event.mode)
        File(dir, "event.json").writeText(json.toString())
    }

    fun listEvents(): List<Event> {
        val dir = root
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            val meta = File(d, "event.json")
            val json = if (meta.exists()) {
                try { JSONObject(meta.readText()) } catch (_: Exception) { null }
            } else null
            val photos = d.listFiles()?.filter { it.extension == "jpg" }?.map { it.name }?.sorted() ?: emptyList()
            Event(
                id = d.name,
                timestamp = json?.optLong("timestamp") ?: 0L,
                score = json?.optDouble("score", 0.0)?.toFloat() ?: 0f,
                photos = photos,
                mode = json?.optString("mode", "") ?: "",
                video = json?.optString("video", "")?.takeIf { it.isNotEmpty() },
            )
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun photoFile(eventId: String, photoName: String): File = File(File(root, eventId), photoName)

    /** Associe une vidéo à un événement existant (après fin d'enregistrement). */
    fun setEventVideo(eventId: String, videoName: String) {
        val dir = File(root, eventId)
        val meta = File(dir, "event.json")
        if (!meta.exists()) return
        try {
            val json = JSONObject(meta.readText())
            json.put("video", videoName)
            meta.writeText(json.toString())
        } catch (_: Exception) {
        }
    }

    fun deleteEvent(eventId: String) {
        File(root, eventId).deleteRecursively()
    }

    /** Supprime les événements plus vieux que [maxAgeMs]. */
    fun pruneOlderThan(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && now - d.lastModified() > maxAgeMs) d.deleteRecursively()
        }
    }

    fun totalSizeBytes(): Long = root.listFiles()?.sumOf { it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } } ?: 0L
}
