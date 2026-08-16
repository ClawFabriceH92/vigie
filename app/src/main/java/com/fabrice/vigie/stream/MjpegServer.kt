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
    private val port: Int,
    private val userProvider: () -> String,
    private val passwordProvider: () -> String,
    private val intercomPortProvider: () -> Int,
) : NanoHTTPD(port) {

    companion object {
        private const val BOUNDARY = "frame"
    }

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var frameSeq: Long = 0L

    private val listeners = CopyOnWriteArrayList<MjpegWriter>()

    /** Appelé quand le nombre de clients du flux change (pour l'indicateur UI). */
    @Volatile var onClientsChanged: ((Int) -> Unit)? = null

    fun clientCount(): Int = listeners.size

    fun isStreaming(): Boolean = listeners.isNotEmpty()

    fun lastFrameAtMs(): Long = lastFrameTimestamp

    @Volatile private var lastFrameTimestamp = 0L

    // ---------- Alimentation ----------

    fun publish(jpeg: ByteArray) {
        latestJpeg = jpeg
        frameSeq++
        lastFrameTimestamp = System.currentTimeMillis()
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
        // Normalise l'URI : ignore le slash final et la query (ex: /stream/, /stream?t=1)
        val uri = session.uri.trimEnd('/').substringBefore('?')
        return when (uri) {
            "", "/index.html" -> pageResponse()
            "/stream" -> streamResponse()
            "/snapshot" -> snapshotResponse()
            "/video/start" -> videoStartResponse()
            "/video/stop" -> videoStopResponse()
            "/video/list" -> videoListResponse()
            "/video/download" -> videoDownloadResponse(session)
            "/photos" -> photosResponse()
            "/photo" -> photoResponse(session)
            "/photo/delete" -> photoDeleteResponse(session)
            "/photos/json" -> photosJsonResponse()
            "/photos/clear" -> photosClearResponse()
            "/videos/json" -> videosJsonResponse()
            "/videos/clear" -> videosClearResponse()
            "/siren/on" -> sirenResponse(true)
            "/siren/off" -> sirenResponse(false)
            "/torch/on" -> torchResponse(true)
            "/torch/off" -> torchResponse(false)
            "/zoom/in" -> zoomResponse(1.4f)
            "/zoom/out" -> zoomResponse(1f / 1.4f)
            "/zoom/reset" -> zoomResponse(0f)
            "/status" -> statusResponse()
            else -> {
                android.util.Log.w("VigieHttp", "404 sur ${session.method}:${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 — page introuvable")
            }
        }
    }

    // ---------- Flash / Zoom / Sirène à distance ----------

    private fun torchResponse(on: Boolean): Response {
        val ok = CameraBridge.torchRequested?.invoke(on) ?: false
        return newFixedLengthResponse(
            if (ok) Response.Status.OK else Response.Status.SERVICE_UNAVAILABLE,
            "text/plain; charset=utf-8",
            if (ok) (if (on) "Flash allumé" else "Flash éteint") else "Flash indisponible",
        )
    }

    private fun sirenResponse(on: Boolean): Response {
        val ok = if (on) {
            CameraBridge.sirenStartRequested?.invoke() ?: false
        } else {
            CameraBridge.sirenStopRequested?.invoke() ?: false
        }
        return newFixedLengthResponse(
            if (ok) Response.Status.OK else Response.Status.SERVICE_UNAVAILABLE,
            "text/plain; charset=utf-8",
            if (ok) (if (on) "Sirène activée" else "Sirène arrêtée") else "Sirène indisponible",
        )
    }

    private fun zoomResponse(factor: Float): Response {
        val ok = if (factor == 0f) {
            CameraBridge.zoomResetRequested?.invoke() ?: false
        } else {
            CameraBridge.zoomRequested?.invoke(factor) ?: false
        }
        return newFixedLengthResponse(
            if (ok) Response.Status.OK else Response.Status.SERVICE_UNAVAILABLE,
            "text/plain; charset=utf-8",
            if (ok) "Zoom OK" else "Zoom indisponible",
        )
    }

    // ---------- Statut (batterie / résolution) ----------

    private fun statusResponse(): Response {
        val json = CameraBridge.statusProvider?.invoke()
            ?: "{\"battery\":-1,\"resolution\":\"?\",\"clients\":0,\"recording\":false}"
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    // ---------- Photos (visualisation / suppression à distance) ----------

    private fun photosJsonResponse(): Response {
        val photos = CameraBridge.photosListProvider?.invoke() ?: emptyList()
        val sb = StringBuilder("[")
        for ((i, p) in photos.withIndex()) {
            if (i > 0) sb.append(",")
            val (eventId, name, ts) = p
            sb.append("{\"event\":\"${jsonEsc(eventId)}\",\"name\":\"${jsonEsc(name)}\",\"ts\":$ts,")
            sb.append("\"url\":\"/photo?event=${java.net.URLEncoder.encode(eventId, "UTF-8")}&name=${java.net.URLEncoder.encode(name, "UTF-8")}\"}")
        }
        sb.append("]")
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun photosClearResponse(): Response {
        val n = CameraBridge.photosClearRequested?.invoke() ?: 0
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "Photos supprimées : $n")
    }

    private fun videosJsonResponse(): Response {
        val videos = CameraBridge.videoListProvider?.invoke() ?: emptyList()
        val sb = StringBuilder("[")
        for ((i, v) in videos.withIndex()) {
            if (i > 0) sb.append(",")
            val (name, size) = v
            sb.append("{\"name\":\"${jsonEsc(name)}\",\"size\":$size,\"url\":\"/video/download?name=${java.net.URLEncoder.encode(name, "UTF-8")}\"}")
        }
        sb.append("]")
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun videosClearResponse(): Response {
        val n = CameraBridge.videosClearRequested?.invoke() ?: 0
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "Vidéos supprimées : $n")
    }

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun photosResponse(): Response {
        val photos = CameraBridge.photosListProvider?.invoke() ?: emptyList()
        val html = buildString {
            append("<html><head><meta charset='utf-8'><title>Vigie — photos</title>")
            append("<style>body{background:#0A1F38;color:#FAF6EF;font-family:sans-serif;padding:16px}a{color:#E3B75C}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px}.card{background:#16294a;border-radius:10px;padding:8px;text-align:center}.card img{max-width:100%;border-radius:6px}.card .del{color:#E57373;font-size:13px}.menu{margin-bottom:12px}.menu a{background:#16294a;text-decoration:none;padding:8px 14px;border-radius:10px;font-weight:bold;margin-right:6px}</style></head><body>")
            append("<div class='menu'><a href='/'>📺 Flux</a><a href='/video/list'>🎥 Vidéos</a><a href='/photos'>📷 Photos</a></div>")
            append("<h1>📷 Photos des événements</h1>")
            if (photos.isEmpty()) {
                append("<p>Aucune photo enregistrée.</p>")
            } else {
                append("<div class='grid'>")
                for ((eventId, name, ts) in photos) {
                    val date = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE)
                        .format(java.util.Date(ts))
                    append("<div class='card'><img src='/photo?event=${java.net.URLEncoder.encode(eventId, "UTF-8")}&name=${java.net.URLEncoder.encode(name, "UTF-8")}'>")
                    append("<p>$date</p>")
                    append("<a class='del' href='/photo/delete?event=${java.net.URLEncoder.encode(eventId, "UTF-8")}&name=${java.net.URLEncoder.encode(name, "UTF-8")}'>🗑 Supprimer</a>")
                    append("</div>")
                }
                append("</div>")
            }
            append("</body></html>")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun photoResponse(session: IHTTPSession): Response {
        val eventId = session.parameters["event"]?.firstOrNull() ?: ""
        val name = session.parameters["name"]?.firstOrNull() ?: ""
        val file = CameraBridge.photoFileProvider?.invoke(eventId, name)
        if (file == null || !file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Photo introuvable")
        }
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            java.io.FileInputStream(file),
            file.length(),
        )
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun photoDeleteResponse(session: IHTTPSession): Response {
        val eventId = session.parameters["event"]?.firstOrNull() ?: ""
        val name = session.parameters["name"]?.firstOrNull() ?: ""
        val ok = CameraBridge.photoDeleteRequested?.invoke(eventId, name) ?: false
        return if (ok) {
            val redirect = newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                "<html><head><meta http-equiv='refresh' content='0;url=/photos'></head><body>Supprimé — retour aux photos</body></html>",
            )
            redirect
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Photo introuvable")
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
            append("<style>body{background:linear-gradient(160deg,#0A1F38,#0D2B4E);color:#FAF6EF;font-family:'Segoe UI',sans-serif;padding:16px}a{color:#E3B75C}.menu{margin-bottom:12px}.menu a{background:rgba(255,255,255,.07);text-decoration:none;padding:8px 14px;border-radius:10px;font-weight:600;margin-right:6px}.menu a.active{background:#C9972B;color:#0A1F38}.card{background:#122a4d;border-radius:12px;padding:14px;margin:8px 0;display:flex;justify-content:space-between;align-items:center;border:1px solid rgba(255,255,255,.08)}.btn{background:#C9972B;color:#0A1F38;text-decoration:none;padding:8px 14px;border-radius:8px;font-weight:700}</style></head><body>")
            append("<div class='menu'><a href='/'>📺 Flux</a><a href='/video/list' class='active'>🎥 Vidéos</a><a href='/photos'>📷 Photos</a></div>")
            append("<h1>🎥 Vidéos enregistrées</h1>")
            if (videos.isEmpty()) {
                append("<p>Aucune vidéo pour l'instant.</p>")
            } else {
                for ((name, size) in videos) {
                    val mb = "%.1f".format(size / 1_048_576.0)
                    append("<div class='card'><span>$name — $mb Mo</span><a class='btn' href=\"/video/download?name=${java.net.URLEncoder.encode(name, "UTF-8")}\">⬇ Télécharger</a></div>")
                }
            }
            append("<p style='margin-top:14px'><a class='btn' href='/video/start'>▶ Démarrer un enregistrement</a> &nbsp; <a class='btn' href='/video/stop'>⏹ Arrêter</a></p>")
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
        val html = """            <!DOCTYPE html>
            <html lang="fr">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Vigie — contrôle à distance</title>
              <style>
                :root { --bg:#0A1F38; --bg2:#0D2B4E; --card:#122a4d; --gold:#C9972B; --gold2:#E3B75C; --cream:#FAF6EF; --muted:#B8C7DA; --red:#C62828; --green:#2E7D32; }
                * { box-sizing:border-box; }
                body { background:linear-gradient(160deg, var(--bg) 0%, var(--bg2) 100%); color:var(--cream); font-family:'Segoe UI',system-ui,sans-serif; margin:0; display:flex; flex-direction:column; align-items:center; padding:16px; min-height:100vh; }
                h1 { color:var(--gold); margin:10px 0 4px; font-size:24px; letter-spacing:.5px; text-shadow:0 2px 8px rgba(0,0,0,.4); }
                .menu { display:flex; gap:8px; margin:14px 0; flex-wrap:wrap; justify-content:center; }
                .menu a, .menubtn { background:rgba(255,255,255,.07); color:var(--gold2); text-decoration:none; padding:9px 16px; border-radius:12px; font-size:14px; font-weight:600; border:1px solid rgba(255,255,255,.08); transition:all .15s; backdrop-filter:blur(4px); cursor:pointer; }
                .menu a:hover, .menubtn:hover { background:rgba(255,255,255,.14); transform:translateY(-1px); }
                .menu a.active { background:linear-gradient(135deg, var(--gold), var(--gold2)); color:var(--bg); border-color:transparent; box-shadow:0 4px 14px rgba(201,151,43,.35); }
                .stream-wrap { background:#000; border-radius:16px; padding:6px; box-shadow:0 10px 40px rgba(0,0,0,.5), 0 0 0 1px rgba(255,255,255,.06); max-width:100%; }
                img { max-width:100%; height:auto; border-radius:12px; display:block; }
                .info { color:var(--muted); font-size:14px; margin:10px 0 4px; }
                .controls { display:flex; gap:10px; margin:14px 0; align-items:center; flex-wrap:wrap; justify-content:center; }
                button { background:linear-gradient(135deg, var(--gold), var(--gold2)); color:var(--bg); border:none; border-radius:12px; padding:13px 18px; font-size:15px; font-weight:700; cursor:pointer; transition:transform .12s, box-shadow .12s, background .2s; box-shadow:0 4px 12px rgba(0,0,0,.25); }
                button:hover { transform:translateY(-1px); box-shadow:0 6px 18px rgba(0,0,0,.35); }
                button:active { transform:translateY(1px); }
                button:disabled { opacity:.5; cursor:default; transform:none; }
                button.off { background:rgba(255,255,255,.10); color:var(--muted); }
                button.listening { background:linear-gradient(135deg, #1B5E20, var(--green)); color:#fff; }
                button.rec { background:linear-gradient(135deg, #B71C1C, var(--red)); color:#fff; }
                .status { color:#81C784; font-size:13px; min-height:20px; text-align:center; max-width:90%; }
                .status.err { color:#E57373; }
                .infobar { background:rgba(255,255,255,.07); border:1px solid rgba(255,255,255,.08); border-radius:10px; padding:7px 16px; color:var(--muted); font-size:13px; font-weight:600; margin:10px 0 0; letter-spacing:.3px; }
                .infobar .b-low { color:#E57373; }
                .flash-ind { display:inline-block; width:10px; height:10px; border-radius:50%; background:#555; margin-right:6px; vertical-align:middle; }
                .flash-ind.on { background:#FFD54F; box-shadow:0 0 8px #FFD54F; }
                .panel { background:rgba(255,255,255,.06); border:1px solid rgba(255,255,255,.08); border-radius:14px; padding:16px; margin-top:16px; width:94%; max-width:900px; }
                .panel h2 { color:var(--gold); margin:0 0 10px; font-size:18px; }
                .hidden { display:none; }
                .panel .thumb { width:120px; height:90px; object-fit:cover; border-radius:8px; border:1px solid rgba(255,255,255,.15); }
                .panel .row { display:flex; align-items:center; gap:12px; padding:8px 0; border-bottom:1px solid rgba(255,255,255,.06); flex-wrap:wrap; }
                .panel .row .meta { flex:1; font-size:13px; color:var(--muted); }
                .panel a.dl { color:var(--gold2); font-weight:600; text-decoration:none; }
                button.danger { background:linear-gradient(135deg, #B71C1C, var(--red)); color:#fff; margin-top:12px; }
              </style>
            </head>
            <body>
              <h1>👁 Vigie — contrôle à distance</h1>
              <div class="menu">
                <a href="/" class="active">📺 Flux</a>
                <button class="menubtn" id="navVideos">🎬 Voir la galerie de vidéos</button>
                <button class="menubtn" id="navPhotos">🖼 Voir la galerie de photos</button>
                <a href="/snapshot" target="_blank">📸 Prendre un snapshot</a>
              </div>
              <div class="stream-wrap">
                <img src="/stream" alt="Flux Vigie" id="liveImg">
              </div>
              <div class="infobar" id="infobar">🔋 -- · 📺 -- · 👥 --</div>
              <p class="info">Flux temps réel — protégé par mot de passe</p>
              <div class="controls">
                <button id="recBtn">🎥 Enregistrer</button>
                <button id="flashBtn">⚡ Flash</button>
                <button id="zoomInBtn">🔍 Zoom +</button>
                <button id="zoomOutBtn">🔍 Zoom −</button>
                <button id="sirenBtn">🚨 Sirène</button>
                <button id="talkBtn" class="off" disabled>🔇 Parler</button>
                <button id="listenBtn" class="off" disabled>🔈 Écouter</button>
              </div>
              <p class="status" id="status">Connexion intercom…</p>
              <div id="videosPanel" class="panel hidden">
                <h2>🎬 Galerie de vidéos</h2>
                <div id="videosList"></div>
                <button id="clearVideos" class="danger">🗑 Supprimer toutes les vidéos</button>
              </div>
              <div id="photosPanel" class="panel hidden">
                <h2>🖼 Galerie de photos</h2>
                <div id="photosList"></div>
                <button id="clearPhotos" class="danger">🗑 Supprimer toutes les photos</button>
              </div>
              <script>
                const TOKEN = ${jsonToken()};
                const WS_PORT = ${intercomPortProvider()};
                const WS_URL = "ws://" + location.hostname + ":" + WS_PORT + "/?token=" + encodeURIComponent(TOKEN);
                const SAMPLE_RATE = 16000;
                let ws = null;
                let ctx = null;
                let micStream = null;
                let talkNode = null;
                let listenNode = null;
                let recording = false;
                let flashOn = false;
                const queue = [];

                const statusEl = document.getElementById("status");
                const talkBtn = document.getElementById("talkBtn");
                const listenBtn = document.getElementById("listenBtn");
                const recBtn = document.getElementById("recBtn");
                const flashBtn = document.getElementById("flashBtn");
                const zoomInBtn = document.getElementById("zoomInBtn");
                const zoomOutBtn = document.getElementById("zoomOutBtn");

                function setStatus(text, err) {
                  statusEl.textContent = text;
                  statusEl.className = "status" + (err ? " err" : "");
                }

                // ---- Flash à distance ----
                async function toggleFlash() {
                  flashBtn.disabled = true;
                  try {
                    const r = await fetch(flashOn ? "/torch/off" : "/torch/on");
                    flashOn = r.ok ? !flashOn : flashOn;
                    if (flashOn) {
                      flashBtn.innerHTML = '<span class="flash-ind on"></span>⚡ Flash ON';
                      flashBtn.classList.add("rec");
                      setStatus("⚡ Flash allumé");
                    } else {
                      flashBtn.innerHTML = '<span class="flash-ind"></span>⚡ Flash';
                      flashBtn.classList.remove("rec");
                      setStatus("Flash éteint");
                    }
                  } catch (e) {
                    setStatus("⚠ Erreur flash", true);
                  } finally {
                    flashBtn.disabled = false;
                  }
                }
                flashBtn.addEventListener("click", toggleFlash);

                // ---- Sirène à distance ----
                let sirenOn = false;
                const sirenBtn = document.getElementById("sirenBtn");
                sirenBtn.addEventListener("click", async () => {
                  sirenBtn.disabled = true;
                  try {
                    const r = await fetch(sirenOn ? "/siren/off" : "/siren/on");
                    if (r.ok) {
                      sirenOn = !sirenOn;
                      if (sirenOn) {
                        sirenBtn.textContent = "🚨 Sirène ON";
                        sirenBtn.classList.add("rec");
                        setStatus("🚨 Sirène activée");
                      } else {
                        sirenBtn.textContent = "🚨 Sirène";
                        sirenBtn.classList.remove("rec");
                        setStatus("Sirène arrêtée");
                      }
                    }
                  } catch (e) {
                    setStatus("⚠ Erreur sirène", true);
                  } finally {
                    sirenBtn.disabled = false;
                  }
                });

                // ---- Panneaux vidéos / photos (sans quitter la page) ----
                const videosPanel = document.getElementById("videosPanel");
                const photosPanel = document.getElementById("photosPanel");
                const navVideos = document.getElementById("navVideos");
                const navPhotos = document.getElementById("navPhotos");

                function fmtSize(bytes) {
                  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + " Mo";
                  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " Ko";
                  return bytes + " o";
                }
                function fmtDate(ts) {
                  const d = new Date(ts);
                  return d.toLocaleDateString("fr-FR") + " " + d.toLocaleTimeString("fr-FR", {hour: "2-digit", minute: "2-digit"});
                }

                async function loadVideos() {
                  try {
                    const r = await fetch("/videos/json");
                    const videos = await r.json();
                    const el = document.getElementById("videosList");
                    if (videos.length === 0) { el.innerHTML = "<p>Aucune vidéo.</p>"; return; }
                    el.innerHTML = videos.map(v =>
                      "<div class='row'><span class='meta'>🎥 " + v.name + " — " + fmtSize(v.size) + "</span>" +
                      "<a class='dl' href='" + v.url + "'>⬇ Télécharger</a></div>"
                    ).join("");
                  } catch (e) { document.getElementById("videosList").innerHTML = "<p>Erreur chargement.</p>"; }
                }

                async function loadPhotos() {
                  try {
                    const r = await fetch("/photos/json");
                    const photos = await r.json();
                    const el = document.getElementById("photosList");
                    if (photos.length === 0) { el.innerHTML = "<p>Aucune photo.</p>"; return; }
                    el.innerHTML = photos.map(p =>
                      "<div class='row'><img class='thumb' src='" + p.url + "' loading='lazy'>" +
                      "<span class='meta'>📷 " + fmtDate(p.ts) + "</span>" +
                      "<a class='dl' href='" + p.url + "' target='_blank'>🔍 Ouvrir</a>" +
                      "<a class='dl' href='/photo/delete?event=" + encodeURIComponent(p.event) + "&name=" + encodeURIComponent(p.name) + "' onclick='return confirm(\"Supprimer cette photo ?\")'>🗑</a></div>"
                    ).join("");
                  } catch (e) { document.getElementById("photosList").innerHTML = "<p>Erreur chargement.</p>"; }
                }

                navVideos.addEventListener("click", () => {
                  videosPanel.classList.toggle("hidden");
                  if (!videosPanel.classList.contains("hidden")) loadVideos();
                });
                navPhotos.addEventListener("click", () => {
                  photosPanel.classList.toggle("hidden");
                  if (!photosPanel.classList.contains("hidden")) loadPhotos();
                });

                document.getElementById("clearVideos").addEventListener("click", async () => {
                  if (!confirm("Supprimer TOUTES les vidéos ? Action irréversible.")) return;
                  await fetch("/videos/clear");
                  loadVideos();
                });
                document.getElementById("clearPhotos").addEventListener("click", async () => {
                  if (!confirm("Supprimer TOUTES les photos ? Action irréversible.")) return;
                  await fetch("/photos/clear");
                  loadPhotos();
                });

                // ---- Statut périodique (batterie / résolution / clients) ----
                async function refreshStatus() {
                  try {
                    const r = await fetch("/status");
                    const s = await r.json();
                    const infobar = document.getElementById("infobar");
                    const batt = s.battery >= 0 ? s.battery + "%" : "--";
                    const battClass = s.battery >= 0 && s.battery <= 20 ? ' class="b-low"' : "";
                    infobar.innerHTML = '🔋 <span' + battClass + '>' + batt + '</span> · 📺 ' + s.resolution + ' · 👥 ' + s.clients + (s.recording ? ' · 🔴 ENREGISTREMENT' : '');
                  } catch (e) {}
                }
                refreshStatus();
                setInterval(refreshStatus, 5000);

                // ---- Zoom à distance ----
                zoomInBtn.addEventListener("click", async () => {
                  try { await fetch("/zoom/in"); } catch (e) {}
                });
                zoomOutBtn.addEventListener("click", async () => {
                  try { await fetch("/zoom/out"); } catch (e) {}
                });

                // ---- Enregistrement vidéo à distance ----
                async function toggleRecording() {
                  recBtn.disabled = true;
                  try {
                    if (!recording) {
                      const r = await fetch("/video/start");
                      recording = r.ok;
                      if (recording) {
                        recBtn.textContent = "⏹ Arrêter l'enregistrement";
                        recBtn.classList.add("rec");
                        setStatus("🔴 Enregistrement vidéo en cours…");
                      } else {
                        setStatus("⚠ Impossible de démarrer (déjà en cours ?)", true);
                      }
                    } else {
                      const r = await fetch("/video/stop");
                      recording = false;
                      recBtn.textContent = "🎥 Enregistrer";
                      recBtn.classList.remove("rec");
                      setStatus("⏹ Enregistrement arrêté — voir 🎥 Vidéos");
                    }
                  } catch (e) {
                    setStatus("⚠ Erreur contrôle vidéo", true);
                  } finally {
                    recBtn.disabled = false;
                  }
                }
                recBtn.addEventListener("click", toggleRecording);

                ws = new WebSocket(WS_URL);
                ws.binaryType = "arraybuffer";
                ws.onopen = () => {
                  setStatus("✅ Intercom connecté — maintenez « Parler » pour diffuser, « Écouter » pour entendre la pièce");
                  talkBtn.disabled = false; listenBtn.disabled = false;
                  talkBtn.classList.remove("off"); listenBtn.classList.remove("off");
                };
                ws.onclose = (ev) => {
                  setStatus("❌ Intercom déconnecté (" + (ev.code || "?") + ")", true);
                  talkBtn.disabled = true; listenBtn.disabled = true;
                  talkBtn.classList.add("off"); listenBtn.classList.add("off");
                };
                ws.onerror = () => setStatus("⚠ Erreur WebSocket — vérifiez le port intercom (Diagnostic)", true);
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
        // Encodage Base64URL (pas de caractères réservés dans une URL/query string)
        val b64 = android.util.Base64.encodeToString(token.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val urlSafe = b64.replace('+', '-').replace('/', '_').trimEnd('=')
        val sb = StringBuilder("\"")
        for (c in urlSafe) {
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
        onClientsChanged?.invoke(listeners.size)
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
                onClientsChanged?.invoke(listeners.size)
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
