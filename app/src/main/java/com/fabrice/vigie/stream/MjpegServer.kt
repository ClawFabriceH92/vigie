package com.fabrice.vigie.stream

import android.util.Base64
import com.fabrice.vigie.camera.CameraBridge
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
    private val userProvider: () -> String,
    private val passwordProvider: () -> String,
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8080
        private const val BOUNDARY = "frame"
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
            "/video/start" -> videoStartResponse()
            "/video/stop" -> videoStopResponse()
            "/video/list" -> videoListResponse()
            "/video/download" -> videoDownloadResponse(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    // ---------- Vidéo (contrôle à distance) ----------

    private fun videoStartResponse(): Response {
        val ok = CameraBridge.videoStartRequested?.invoke() ?: false
        return newFixedLengthResponse(
            if (ok) Response.Status.OK else Response.Status.CONFLICT,
            "text/plain; charset=utf-8",
            if (ok) "Enregistrement démarré" else "Déjà en cours ou indisponible",
        )
    }

    private fun videoStopResponse(): Response {
        val name = CameraBridge.videoStopRequested?.invoke()
        return if (name != null) {
            newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "Enregistrement arrêté : $name")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Aucun enregistrement en cours")
        }
    }

    private fun videoListResponse(): Response {
        val videos = CameraBridge.videoListProvider?.invoke() ?: emptyList()
        val html = buildString {
            append("<html><head><meta charset='utf-8'><title>Vigie — vidéos</title>")
            append("<style>body{background:#0A1F38;color:#FAF6EF;font-family:sans-serif;padding:16px}a{color:#E3B75C}li{margin:6px 0}</style></head><body>")
            append("<h1>🎥 Vidéos enregistrées</h1>")
            if (videos.isEmpty()) {
                append("<p>Aucune vidéo pour l'instant.</p>")
            } else {
                append("<ul>")
                for ((name, size) in videos) {
                    val mb = "%.1f".format(size / 1_048_576.0)
                    append("<li><a href=\"/video/download?name=${java.net.URLEncoder.encode(name, "UTF-8")}\">$name</a> — $mb Mo</li>")
                }
                append("</ul>")
            }
            append("<p><a href=\"/video/start\">▶ Démarrer un enregistrement</a> · <a href=\"/video/stop\">⏹ Arrêter</a> · <a href=\"/\">← Retour au flux</a></p>")
            append("</body></html>")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun videoDownloadResponse(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull() ?: ""
        val file = CameraBridge.videoFileProvider?.invoke(name)
        if (file == null || !file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Fichier introuvable")
        }
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "video/mp4",
            java.io.FileInputStream(file),
            file.length(),
        )
        resp.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val password = passwordProvider()
        val expected = "Basic " + Base64.encodeToString("${userProvider()}:$password".toByteArray(), Base64.NO_WRAP)
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
              <title>Vigie — flux + intercom</title>
              <style>
                body { background:#0A1F38; color:#FAF6EF; font-family:sans-serif; margin:0; display:flex; flex-direction:column; align-items:center; padding:12px; }
                h1 { color:#C9972B; margin:8px 0; }
                img { max-width:100%; height:auto; border-radius:10px; }
                .info { color:#B8C7DA; font-size:14px; }
                .controls { display:flex; gap:10px; margin:12px 0; align-items:center; flex-wrap:wrap; justify-content:center; }
                button { background:#C9972B; color:#0A1F38; border:none; border-radius:12px; padding:14px 18px; font-size:16px; font-weight:bold; cursor:pointer; }
                button:active { background:#E3B75C; }
                button.off { background:#444; color:#aaa; }
                button.listening { background:#2E7D32; color:#fff; }
                .status { color:#81C784; font-size:13px; min-height:18px; }
                .status.err { color:#E57373; }
              </style>
            </head>
            <body>
              <h1>👁 Vigie</h1>
              <img src="/stream" alt="Flux Vigie">
              <p class="info">Flux temps réel — protégé par mot de passe</p>
              <div class="controls">
                <button id="talkBtn" class="off" disabled>🔇 Parler</button>
                <button id="listenBtn" class="off" disabled>🔈 Écouter</button>
              </div>
              <p class="status" id="status">Connexion intercom…</p>
              <script>
                const TOKEN = ${jsonToken()};
                const WS_URL = "ws://" + location.hostname + ":8081/?token=" + encodeURIComponent(TOKEN);
                const SAMPLE_RATE = 16000;
                let ws = null;
                let ctx = null;
                let micStream = null;
                let talkNode = null;
                let listenNode = null;
                const queue = [];

                const statusEl = document.getElementById("status");
                const talkBtn = document.getElementById("talkBtn");
                const listenBtn = document.getElementById("listenBtn");

                function setStatus(text, err) {
                  statusEl.textContent = text;
                  statusEl.className = "status" + (err ? " err" : "");
                }

                ws = new WebSocket(WS_URL);
                ws.binaryType = "arraybuffer";
                ws.onopen = () => {
                  setStatus("✅ Intercom connecté — maintenez « Parler » pour diffuser, « Écouter » pour entendre la pièce");
                  talkBtn.disabled = false; listenBtn.disabled = false;
                  talkBtn.classList.remove("off"); listenBtn.classList.remove("off");
                };
                ws.onclose = () => {
                  setStatus("❌ Intercom déconnecté", true);
                  talkBtn.disabled = true; listenBtn.disabled = true;
                  talkBtn.classList.add("off"); listenBtn.classList.add("off");
                };
                ws.onerror = () => setStatus("⚠ Erreur WebSocket", true);
                ws.onmessage = (ev) => {
                  if (!listenBtn.classList.contains("listening")) return;
                  const data = new Int16Array(ev.data);
                  const f32 = new Float32Array(data.length);
                  for (let i = 0; i < data.length; i++) f32[i] = data[i] / 32768;
                  queue.push(f32);
                };

                // ---- Parler (push-to-talk) ----
                async function startTalk() {
                  if (!ws || ws.readyState !== 1) return;
                  if (!ctx) ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
                  await ctx.resume();
                  micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
                  const source = ctx.createMediaStreamSource(micStream);
                  talkNode = ctx.createScriptProcessor(2048, 1, 1);
                  talkNode.onaudioprocess = (e) => {
                    if (ws.readyState !== 1) return;
                    const input = e.inputBuffer.getChannelData(0);
                    const int16 = new Int16Array(input.length);
                    for (let i = 0; i < input.length; i++) {
                      const s = Math.max(-1, Math.min(1, input[i]));
                      int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                    }
                    ws.send(int16.buffer);
                  };
                  source.connect(talkNode);
                  talkNode.connect(ctx.destination);
                  talkBtn.textContent = "🎙 Parler (relâchez pour arrêter)";
                  talkBtn.classList.add("listening");
                }
                function stopTalk() {
                  if (talkNode) { try { talkNode.disconnect(); } catch (e) {} talkNode = null; }
                  if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null; }
                  talkBtn.textContent = "🎙 Parler";
                  talkBtn.classList.remove("listening");
                }
                talkBtn.addEventListener("pointerdown", (e) => { e.preventDefault(); startTalk().catch(() => setStatus("⚠ Micro refusé — autorisez le micro dans le navigateur", true)); });
                talkBtn.addEventListener("pointerup", stopTalk);
                talkBtn.addEventListener("pointerleave", stopTalk);
                talkBtn.addEventListener("contextmenu", (e) => e.preventDefault());

                // ---- Écouter ----
                function toggleListen() {
                  if (listenBtn.classList.contains("listening")) {
                    listenBtn.classList.remove("listening");
                    listenBtn.textContent = "🔈 Écouter";
                    if (listenNode) { try { listenNode.disconnect(); } catch (e) {} listenNode = null; }
                    queue.length = 0;
                    return;
                  }
                  if (!ctx) ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
                  ctx.resume();
                  listenNode = ctx.createScriptProcessor(2048, 0, 1);
                  listenNode.onaudioprocess = (e) => {
                    const out = e.outputBuffer.getChannelData(0);
                    let written = 0;
                    while (written < out.length && queue.length > 0) {
                      const chunk = queue[0];
                      const n = Math.min(chunk.length, out.length - written);
                      out.set(chunk.subarray(0, n), written);
                      written += n;
                      if (n < chunk.length) queue[0] = chunk.subarray(n);
                      else queue.shift();
                    }
                  };
                  listenNode.connect(ctx.destination);
                  listenBtn.classList.add("listening");
                  listenBtn.textContent = "🔊 Écoute en cours (cliquez pour couper)";
                }
                listenBtn.addEventListener("click", toggleListen);
              </script>
            </body>
            </html>
        """.trimIndent()
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun jsonToken(): String {
        val token = passwordProvider()
        val sb = StringBuilder("\"")
        for (c in token) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                else -> sb.append(c)
            }
        }
        return sb.append("\"").toString()
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
