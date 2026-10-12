package br.dev.gui.integra.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Um microfone só, compartilhado por quem está ouvindo (o "Ei Integra" e a gravação do pedido), como
 * o `microphone.ts` do web: abrir outro a cada fala perdia as primeiras sílabas ditas logo após o
 * "plim". Abre no primeiro ouvinte e fecha quando o último sai. Áudio mono, 16 kHz, PCM 16 bits.
 */
object AudioInput {
    private const val TAG = "AudioInput"
    const val SAMPLE_RATE = 16_000
    /** Amostras por bloco entregue (100 ms). */
    private const val CHUNK = SAMPLE_RATE / 10

    /** Recebe cada bloco; [samples] é reaproveitado, então copie se for guardar. */
    fun interface Listener {
        fun onAudio(samples: ShortArray, count: Int)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var record: AudioRecord? = null
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var thread: Thread? = null
    @Volatile private var running = false

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Começa a entregar áudio para [listener]. Falso se o microfone não abriu. */
    @Synchronized
    fun add(context: Context, listener: Listener): Boolean {
        listeners.add(listener)
        if (running) return true
        if (open(context)) return true
        listeners.remove(listener)
        return false
    }

    @Synchronized
    fun remove(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) close()
    }

    @SuppressLint("MissingPermission")
    private fun open(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            // VOICE_RECOGNITION: sem o processamento de chamada (que piora o reconhecimento); o
            // cancelamento de eco é ligado à parte, para a voz da própria Integra não atrapalhar.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2 * 4),
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord", e)
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        effects = buildList {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; add(it) }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.let { it.enabled = true; add(it) }
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startRecording", e)
            effects.forEach { it.release() }
            r.release()
            return false
        }
        record = r
        running = true
        thread = Thread({ loop(r) }, "audio-input").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Microfone aberto")
        return true
    }

    private fun loop(r: AudioRecord) {
        val buffer = ShortArray(CHUNK)
        while (running) {
            val n = r.read(buffer, 0, buffer.size)
            if (n <= 0) {
                if (n < 0) Log.w(TAG, "read=$n")
                continue
            }
            for (l in listeners) {
                try {
                    l.onAudio(buffer, n)
                } catch (e: Exception) {
                    Log.w(TAG, "listener", e)
                }
            }
        }
    }

    private fun close() {
        if (!running) return
        running = false
        val r = record
        record = null
        runCatching { r?.stop() }
        thread?.join(500)
        thread = null
        effects.forEach { it.release() }
        effects = emptyList()
        r?.release()
        Log.i(TAG, "Microfone fechado")
    }
}
