package br.dev.gui.integra.voice

import android.content.Context
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Detecção de fim de fala por energia, igual ao `VAD` do `transcriber.ts` (ajustada para sala quieta). */
object Vad {
    /** Piso do limiar de fala (RMS de um sinal -1..1). */
    const val MIN_THRESHOLD = 0.015f
    /** A fala precisa ser esta quantidade de vezes mais alta que o ruído medido. */
    const val NOISE_FACTOR = 3f
    /** Áudio alto contínuo necessário para contar como fala. */
    const val SPEECH_START_MS = 150
    /** Silêncio após a fala que encerra a frase. */
    const val SPEECH_END_MS = 1200
    /** Limite de uma gravação. */
    const val MAX_UTTERANCE_MS = 20_000
}

enum class ListenPhase { Speech, Transcribing }

object Recorder {
    /**
     * Grava uma fala (até o silêncio), transcreve no servidor (Scribe) e devolve o texto, ou "" se
     * ninguém falou dentro de [silenceTimeoutMs]. Cancelar a corrotina para a gravação.
     */
    suspend fun listenOnce(context: Context, silenceTimeoutMs: Long, onPhase: (ListenPhase) -> Unit): String {
        val chunks = Channel<ShortArray>(Channel.UNLIMITED)
        val listener = AudioInput.Listener { samples, count -> chunks.trySend(samples.copyOf(count)) }
        if (!AudioInput.add(context, listener)) throw IOException("Microfone indisponível")
        val recorded = ArrayList<ShortArray>()
        var record = false
        try {
            var elapsedMs = 0.0
            var loudMs = 0.0
            var heardSpeech = false
            var lastVoiceMs = 0.0
            var noise = Vad.MIN_THRESHOLD / Vad.NOISE_FACTOR
            while (true) {
                val samples = chunks.receive()
                recorded += samples
                val chunkMs = samples.size * 1000.0 / AudioInput.SAMPLE_RATE
                elapsedMs += chunkMs
                val level = rms(samples)
                val threshold = maxOf(Vad.MIN_THRESHOLD, noise * Vad.NOISE_FACTOR)
                if (level > threshold) {
                    loudMs += chunkMs
                    lastVoiceMs = elapsedMs
                    if (!heardSpeech && loudMs >= Vad.SPEECH_START_MS) {
                        heardSpeech = true
                        onPhase(ListenPhase.Speech)
                    }
                } else {
                    if (!heardSpeech) loudMs = 0.0
                    noise = noise * 0.95f + level * 0.05f
                }
                if (heardSpeech && elapsedMs - lastVoiceMs >= Vad.SPEECH_END_MS) { record = true; break }
                if (!heardSpeech && elapsedMs >= silenceTimeoutMs) break
                if (elapsedMs >= Vad.MAX_UTTERANCE_MS) { record = true; break }
            }
        } finally {
            AudioInput.remove(listener)
            chunks.close()
        }
        if (!record) return ""
        onPhase(ListenPhase.Transcribing)
        return AssistantHttp.transcribe(encodeWav(recorded))
    }

    /**
     * Grava uma fala curta (até o silêncio) e devolve as amostras cruas mono em
     * [-1, 1], para o cadastro de voz (embedding de locutor, não transcrição).
     * Lança [NoSpeechException] se ninguém falar dentro de [silenceTimeoutMs].
     */
    suspend fun captureOnce(context: Context, silenceTimeoutMs: Long): FloatArray {
        val chunks = Channel<ShortArray>(Channel.UNLIMITED)
        val listener = AudioInput.Listener { samples, count -> chunks.trySend(samples.copyOf(count)) }
        if (!AudioInput.add(context, listener)) throw IOException("Microfone indisponível")
        val recorded = ArrayList<ShortArray>()
        var record = false
        try {
            var elapsedMs = 0.0
            var loudMs = 0.0
            var heardSpeech = false
            var lastVoiceMs = 0.0
            var noise = Vad.MIN_THRESHOLD / Vad.NOISE_FACTOR
            while (true) {
                val samples = chunks.receive()
                recorded += samples
                val chunkMs = samples.size * 1000.0 / AudioInput.SAMPLE_RATE
                elapsedMs += chunkMs
                val level = rms(samples)
                val threshold = maxOf(Vad.MIN_THRESHOLD, noise * Vad.NOISE_FACTOR)
                if (level > threshold) {
                    loudMs += chunkMs
                    lastVoiceMs = elapsedMs
                    if (!heardSpeech && loudMs >= Vad.SPEECH_START_MS) heardSpeech = true
                } else {
                    if (!heardSpeech) loudMs = 0.0
                    noise = noise * 0.95f + level * 0.05f
                }
                if (heardSpeech && elapsedMs - lastVoiceMs >= Vad.SPEECH_END_MS) { record = true; break }
                if (!heardSpeech && elapsedMs >= silenceTimeoutMs) break
                if (elapsedMs >= Vad.MAX_UTTERANCE_MS) { record = true; break }
            }
        } finally {
            AudioInput.remove(listener)
            chunks.close()
        }
        if (!record) throw NoSpeechException()
        val total = recorded.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (chunk in recorded) for (s in chunk) out[offset++] = s / 32768f
        return out
    }

    private fun rms(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / samples.size).toFloat()
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
