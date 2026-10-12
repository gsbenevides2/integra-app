package br.dev.gui.integra.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Fala da Integra (mp3 do `/api/assistant/tts`) e o "plim" de quando ela começa a ouvir. */
object Speaker {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Busca a fala da resposta e toca. Cancelar a corrotina para a fala na hora (barge-in). */
    suspend fun speak(context: Context, text: String) {
        val mp3 = AssistantHttp.synthesize(text)
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "tts.mp3").apply { writeBytes(mp3) }
        }
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            fun release() = runCatching { player.reset(); player.release() }
            try {
                player.setAudioAttributes(attributes)
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { release(); if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    release()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Falha ao tocar a fala ($what/$extra)"))
                    true
                }
                player.prepare()
                player.start()
            } catch (e: Exception) {
                release()
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { release() }
        }
    }

    /** Dois tons curtos (660 e 880 Hz), como o `playChime` do web. */
    fun chime() {
        runCatching {
            val rate = 22_050
            val total = (rate * 0.3).toInt()
            val pcm = ShortArray(total)
            for (i in 0 until total) {
                val t = i.toDouble() / rate
                val envelope = 0.15 * exp(-t * 15.0)
                var v = sin(2 * PI * 660 * t)
                if (t >= 0.1) v += sin(2 * PI * 880 * t)
                pcm[i] = (v * envelope * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(total * 2)
                .build()
            track.write(pcm, 0, total)
            track.setNotificationMarkerPosition(total - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }
    }
}
