package br.dev.gui.integra.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val TAG = "Vad"

/** Detecção de fim de fala por energia, igual ao `VAD` do `transcriber.ts` (ajustada para sala quieta). */
object Vad {
    /** Piso do limiar de fala (RMS de um sinal -1..1). */
    const val MIN_THRESHOLD = 0.015f
    /** A fala precisa ser esta quantidade de vezes mais alta que o ruído medido. */
    const val NOISE_FACTOR = 3f
    /** Depois que a fala começa, um bloco ainda conta como fala acima de limiar * este fator (histerese). */
    const val CONTINUE_FACTOR = 0.6f
    /** Áudio alto contínuo necessário para contar como fala. */
    const val SPEECH_START_MS = 150
    /** Silêncio após a fala que encerra a frase. */
    const val SPEECH_END_MS = 900
    /** Limite de uma gravação. */
    const val MAX_UTTERANCE_MS = 20_000
}

enum class ListenPhase { Speech, Transcribing }

/** RMS de um bloco de amostras, em -1..1. */
private fun rms(samples: ShortArray): Float {
    var sum = 0.0
    for (s in samples) {
        val v = s / 32768.0
        sum += v * v
    }
    return sqrt(sum / samples.size).toFloat()
}

/**
 * Acompanha uma gravação bloco a bloco e diz quando a fala começou e quando terminou.
 * O ruído de fundo é medido só antes da fala: durante ela, o nível dos blocos não entra na
 * estimativa (senão o limiar subia a cada pausa e a fala passava a ser lida como silêncio).
 */
private class Endpointer(private val onSpeechStart: () -> Unit) {
    var elapsedMs = 0.0
        private set
    var heardSpeech = false
        private set
    private var loudMs = 0.0
    private var lastVoiceMs = 0.0
    private var noise = Vad.MIN_THRESHOLD / Vad.NOISE_FACTOR

    /** Processa um bloco. Devolve true quando a frase acabou (silêncio depois da fala). */
    fun feed(samples: ShortArray): Boolean {
        val chunkMs = samples.size * 1000.0 / AudioInput.SAMPLE_RATE
        elapsedMs += chunkMs
        val level = rms(samples)
        val threshold = maxOf(Vad.MIN_THRESHOLD, noise * Vad.NOISE_FACTOR)
        val limit = if (heardSpeech) threshold * Vad.CONTINUE_FACTOR else threshold
        val voiced = level > limit
        Log.d(TAG, "t=%.0f nivel=%.4f limite=%.4f ruido=%.4f fala=%b voz=%b"
            .format(elapsedMs, level, limit, noise, heardSpeech, voiced))

        if (voiced) {
            loudMs += chunkMs
            lastVoiceMs = elapsedMs
            if (!heardSpeech && loudMs >= Vad.SPEECH_START_MS) {
                heardSpeech = true
                Log.i(TAG, "início da fala em %.0f ms (limiar %.4f)".format(elapsedMs, threshold))
                onSpeechStart()
            }
        } else {
            loudMs = 0.0
            if (!heardSpeech) noise = noise * 0.95f + level * 0.05f
        }
        if (heardSpeech && elapsedMs - lastVoiceMs >= Vad.SPEECH_END_MS) {
            Log.i(TAG, "fim da fala em %.0f ms (última voz em %.0f)".format(elapsedMs, lastVoiceMs))
            return true
        }
        return false
    }
}

object Recorder {
    /**
     * Grava uma fala (até o silêncio), transcreve no servidor (Scribe) e devolve o texto, ou "" se
     * ninguém falou dentro de [silenceTimeoutMs]. Cancelar a corrotina para a gravação.
     */
    suspend fun listenOnce(context: Context, silenceTimeoutMs: Long, onPhase: (ListenPhase) -> Unit): String {
        val capture = capture(context, silenceTimeoutMs) { onPhase(ListenPhase.Speech) }
        if (!capture.complete) return ""
        onPhase(ListenPhase.Transcribing)
        return AssistantHttp.transcribe(encodeWav(capture.chunks))
    }

    /**
     * Grava uma fala curta (até o silêncio) e devolve as amostras cruas mono em
     * [-1, 1], para o cadastro de voz (embedding de locutor, não transcrição).
     * Lança [NoSpeechException] se ninguém falar dentro de [silenceTimeoutMs].
     */
    suspend fun captureOnce(context: Context, silenceTimeoutMs: Long): FloatArray {
        val capture = capture(context, silenceTimeoutMs) {}
        if (!capture.complete) throw NoSpeechException()
        val total = capture.chunks.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (chunk in capture.chunks) for (s in chunk) out[offset++] = s / 32768f
        return out
    }

    private class Capture(val chunks: List<ShortArray>, val complete: Boolean)

    /** Escuta até o fim da frase ([complete]=true) ou até o timeout sem fala ([complete]=false). */
    private suspend fun capture(context: Context, silenceTimeoutMs: Long, onSpeechStart: () -> Unit): Capture {
        val queue = Channel<ShortArray>(Channel.UNLIMITED)
        val listener = AudioInput.Listener { samples, count -> queue.trySend(samples.copyOf(count)) }
        if (!AudioInput.add(context, listener)) throw IOException("Microfone indisponível")
        val recorded = ArrayList<ShortArray>()
        val endpointer = Endpointer(onSpeechStart)
        var complete = false
        try {
            while (true) {
                val samples = queue.receive()
                recorded += samples
                if (endpointer.feed(samples)) { complete = true; break }
                if (!endpointer.heardSpeech && endpointer.elapsedMs >= silenceTimeoutMs) break
                if (endpointer.elapsedMs >= Vad.MAX_UTTERANCE_MS) { complete = true; break }
            }
        } finally {
            AudioInput.remove(listener)
            queue.close()
        }
        return Capture(recorded, complete)
    }

    /** WAV PCM 16 bits mono a 16 kHz (`wav.ts`). */
    fun encodeWav(chunks: List<ShortArray>): ByteArray {
        val count = chunks.sumOf { it.size }
        val rate = AudioInput.SAMPLE_RATE
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }
        val out = ByteArrayOutputStream(44 + count * 2)
        out.write(header.array())
        val buffer = ByteBuffer.allocate(4096 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in chunks) for (s in chunk) {
            if (!buffer.hasRemaining()) { out.write(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putShort(s)
        }
        out.write(buffer.array(), 0, buffer.position())
        return out.toByteArray()
    }
}
