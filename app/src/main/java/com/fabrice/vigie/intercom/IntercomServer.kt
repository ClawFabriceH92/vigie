package com.fabrice.vigie.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intercom bidirectionnel embarqué (WebSocket, port 8081).
 *
 * - Le téléphone Vigie enregistre le micro (AudioRecord) et diffuse les chunks
 *   PCM 16 kHz mono à tous les clients connectés → « entendre la pièce ».
 * - Les chunks PCM reçus d'un client sont joués sur le haut-parleur
 *   (AudioTrack) → « parler vers la pièce ».
 *
 * Protégé par le même mot de passe que le flux MJPEG, passé en query string
 * `?token=xxx` (le navigateur ne peut pas mettre d'header sur WebSocket).
 */
class IntercomServer(
    private val context: Context,
    private val tokenProvider: () -> String,
) : NanoWSD(PORT) {

    private val clients = CopyOnWriteArrayList<IntercomSocket>()
    private val recordActive = AtomicBoolean(false)
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var muted = false
    private var recordThread: Thread? = null

    companion object {
        const val PORT = 8081
        private const val SAMPLE_RATE = 16000
        private const val TAG = "VigieIntercom"
    }

    // ---------- Arrêt ----------

    fun shutdown() {
        stopListening()
        try {
            stop()
        } catch (_: Exception) {
        }
        clients.forEach { it.closeQuietly() }
        clients.clear()
    }

    /** Mute côté périphérique Vigie : coupe le haut-parleur sans couper la connexion. */
    fun setMuted(value: Boolean) {
        muted = value
        val track = audioTrack
        if (value) {
            try {
                track?.pause()
            } catch (_: Exception) {
            }
        } else {
            ensureMaxVolume()
            try {
                track?.play()
            } catch (_: Exception) {
            }
        }
    }

    fun isMuted(): Boolean = muted

    /** Volume haut-parleur au maximum + haut-parleur forcé (appelé à la connexion et au unmute). */
    private fun ensureMaxVolume() {
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            manager.isSpeakerphoneOn = true
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
        } catch (_: Exception) {
        }
    }

    private fun startListeningIfNeeded() {
        if (clients.isNotEmpty() && recordActive.compareAndSet(false, true)) {
            recordThread = Thread({ recordLoop() }, "vigie-intercom-record").apply { start() }
        }
    }

    private fun stopListening() {
        recordActive.set(false)
        recordThread?.interrupt()
        recordThread = null
    }

    private fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 2).coerceAtLeast(8192),
            )
        } catch (_: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            recordActive.set(false)
            return
        }
        try {
            record.startRecording()
            val buf = ByteArray(1024)
            while (recordActive.get() && clients.isNotEmpty()) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val payload = ByteArray(n)
                System.arraycopy(buf, 0, payload, 0, n)
                for (c in clients) {
                    try {
                        c.send(payload)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            recordActive.set(false)
        }
    }

    // ---------- WebSocket ----------

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        val token = handshake.parameters["token"]?.firstOrNull() ?: ""
        return IntercomSocket(handshake, token)
    }

    private inner class IntercomSocket(
        handshake: NanoHTTPD.IHTTPSession,
        private val token: String,
    ) : NanoWSD.WebSocket(handshake) {

        override fun onOpen() {
            if (token != tokenProvider()) {
                try {
                    close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "token invalide", false)
                } catch (_: Exception) {
                }
                return
            }
            clients.add(this)
            startListeningIfNeeded()
            if (!muted) ensureMaxVolume()
            Log.i(TAG, "Client intercom connecté (${clients.size} au total)")
        }

        override fun onMessage(frame: NanoWSD.WebSocketFrame) {
            if (frame.opCode != NanoWSD.WebSocketFrame.OpCode.Binary) return
            val payload = frame.binaryPayload ?: return
            if (payload.isEmpty()) return
            playOnSpeaker(payload)
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            clients.remove(this)
            if (clients.isEmpty()) stopListening()
        }

        override fun onPong(frame: NanoWSD.WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            clients.remove(this)
            if (clients.isEmpty()) stopListening()
        }

        fun closeQuietly() {
            try {
                close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "serveur arrêté", true)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Sortie audio (haut-parleur) ----------

    @Synchronized
    private fun playOnSpeaker(bytes: ByteArray) {
        if (muted) return
        var track = audioTrack
        if (track == null) {
            track = createSpeakerTrack() ?: return
            audioTrack = track
        }
        try {
            track.write(bytes, 0, bytes.size)
        } catch (_: Exception) {
            // flux interrompu → recrée le track au prochain appel
            try {
                track.release()
            } catch (_: Exception) {
            }
            audioTrack = null
        }
    }

    private fun createSpeakerTrack(): AudioTrack? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Force le haut-parleur (le téléphone Vigie reste branché, pièce audible)
        try {
            manager?.isSpeakerphoneOn = true
        } catch (_: Exception) {
        }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return try {
            track.play()
            track
        } catch (_: Exception) {
            try {
                track.release()
            } catch (_: Exception) {
            }
            null
        }
    }
}
