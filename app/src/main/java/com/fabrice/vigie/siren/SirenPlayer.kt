package com.fabrice.vigie.siren

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sirène embarquée : génère une onde sinusoïdale modulée (montée/descente de
 * fréquence) sur le haut-parleur. Déclenchable à distance via /siren/on.
 */
object SirenPlayer {

    private val playing = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    private const val SAMPLE_RATE = 22050
    private const val MAX_SECONDS = 30
    private const val TAG = "VigieSiren"

    fun isPlaying(): Boolean = playing.get()

    fun start(context: Context) {
        if (!playing.compareAndSet(false, true)) return
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            manager?.let {
                it.isSpeakerphoneOn = true
                val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                it.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
        } catch (_: Exception) {
        }
        thread = Thread({ loop() }, "vigie-siren").apply { start() }
    }

    fun stop() {
        if (!playing.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                (minBuf * 4).coerceAtLeast(8192),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (_: Exception) {
            null
        }
        if (t == null) {
            playing.set(false)
            return
        }
        track = t
        try {
            t.play()
        } catch (_: Exception) {
        }
        val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val start = System.currentTimeMillis()
        var phase = 0.0
        try {
            while (playing.get() && System.currentTimeMillis() - start < MAX_SECONDS * 1000L) {
                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                // Sirène classique : monte 600→1200 Hz puis redescend
                val sweep = (elapsed % 2.0)
                val freq = if (sweep < 1.0) 600 + 600 * sweep else 1200 - 600 * (sweep - 1.0)
                for (i in buf.indices) {
                    phase += 2.0 * Math.PI * freq / SAMPLE_RATE
                    // Pulse + crête douce pour l'audibilité
                    val amp = 0.7f
                    buf[i] = (Math.sin(phase) * amp * Short.MAX_VALUE).toInt().toShort()
                }
                val written = t.write(buf, 0, buf.size)
                if (written <= 0) break
            }
        } catch (_: Exception) {
        }
        playing.set(false)
        try {
            t.stop()
        } catch (_: Exception) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
        if (track === t) track = null
    }
}
