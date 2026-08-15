package com.fabrice.vigie.stream

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Serveur HTTP embarqué : flux MJPEG protégé par mot de passe (Basic Auth).
 *
 * - `/`          → page HTML simple avec <img src="/stream">
 * - `/stream`    → flux MJPEG (multipart/x-mixed-replace)
 * - `/snapshot`  → JPEG de la dernière frame
 *
 * Alimenté par [publish] depuis le pipeline caméra.
 */
class MjpegServer(
    private val passwordProvider: () -> String,
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8080
        private const val BOUNDARY = "frame"
        private const val USER = "vigie"
    }

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var frameSeq: Long = 0L

    private val listeners = CopyOnWriteArrayList<MjpegWriter>()

    // ---------- Alimentation ----------

    fun publish(jpeg: ByteArray) {
        latestJpeg = jpeg
        frameSeq++
        for (w in listeners) w.onFrame()
    }

    fun isRunning(): Boolean = isAlive

    // ---------- HTTP ----------

    override fun serve(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            val resp = newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "text/plain; charset=utf-8",
                "Accès refusé — mot de passe requis",
            )
            resp.addHeader("WWW-Authenticate", "Basic realm=\"Vigie\"")
            return resp
        }
        return when (session.uri) {
            "/", "/index.html" -> pageResponse()
            "/stream" -> streamResponse()
            "/snapshot" -> snapshotResponse()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val password = passwordProvider()
        val expected = "Basic " + Base64.encodeToString("$USER:$password".toByteArray(), Base64.NO_WRAP)
        val auth = session.headers["authorization"] ?: return false
        return auth == expected
    }

    private fun pageResponse(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="fr">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Vigie — flux</title>
              <style>
                body { background:#0A1F38; color:#FAF6EF; font-family:sans-serif; margin:0; display:flex; flex-direction:column; align-items:center; }
                h1 { color:#C9972B; }
                img { max-width:100%; height:auto; }
                .info { color:#B8C7DA; font-size:14px; }
              </style>
            </head>
            <body>
              <h1>👁 Vigie</h1>
              <img src="/stream" alt="Flux Vigie">
              <p class="info">Flux temps réel — protégé par mot de passe</p>
            </body>
            </html>
        """.trimIndent()
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun streamResponse(): Response {
        val writer = MjpegWriter()
        listeners.add(writer)
        val resp = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            writer.inputStream,
        )
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun snapshotResponse(): Response {
        val jpeg = latestJpeg
        if (jpeg == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Pas encore de frame")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(jpeg), jpeg.size.toLong())
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    // ---------- Writer par client ----------

    private inner class MjpegWriter : Runnable {
        private val output = PipedOutputStream()
        val inputStream = PipedInputStream(output, 128 * 1024)
        private var lastSeq = 0L
        @Volatile private var running = true
        private val thread = Thread(this, "mjpeg-writer").apply { start() }

        override fun run() {
            try {
                while (running) {
                    val seq = frameSeq
                    val jpeg = latestJpeg
                    if (jpeg == null || seq == lastSeq) {
                        Thread.sleep(80)
                        continue
                    }
                    lastSeq = seq
                    val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                    output.write(header.toByteArray(Charsets.ISO_8859_1))
                    output.write(jpeg)
                    output.write("\r\n".toByteArray())
                    output.flush()
                }
            } catch (_: IOException) {
                // client déconnecté
            } catch (_: Exception) {
                // pipe fermé ou interruption
            } finally {
                running = false
                listeners.remove(this)
                try { output.close() } catch (_: Exception) {}
            }
        }

        fun onFrame() { /* le thread relit frameSeq à chaque itération */ }

        fun close() {
            running = false
            thread.interrupt()
        }
    }
}
