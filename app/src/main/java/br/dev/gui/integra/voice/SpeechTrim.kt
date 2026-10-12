package br.dev.gui.integra.voice

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Acha a fala dentro de uma gravação, para o modelo de locutor embutir a voz e
 * não o silêncio ou o ruído da sala ao redor. Igual ao `speechTrim.ts` do web:
 * as amostras do cadastro e a checagem do "Ei Integra" passam por aqui, então
 * as duas comparam o mesmo tipo de áudio (o silêncio também puxa a CMN do
 * fbank para a sala).
 */
object SpeechTrim {
    /** Quadro de análise do envelope de energia. */
    const val FRAME_MS = 20
    /** Pausas até isto ficam na mesma fala (o intervalo em "Ei … Integra"). */
    const val MAX_GAP_MS = 400
    /** Áudio mantido de cada lado da fala, para não cortar o começo e o fim. */
    const val PAD_MS = 120
    /** Este quantil das energias dos quadros é tomado como o ruído de fundo. */
    const val NOISE_QUANTILE = 0.2

    class Segment(
        /** A fala com a margem, copiada da gravação. */
        val samples: FloatArray,
        /** Quanto dela tem voz, em milissegundos (sem as pausas). */
        val voicedMs: Double,
    )

    /**
     * O último trecho de fala de [samples], com as pausas menores que [MAX_GAP_MS] incorporadas,
     * ou null quando nada passa do ruído. O último porque, no "Ei Integra", é a frase que acabou de
     * ser reconhecida; uma amostra do cadastro tem uma fala só de qualquer jeito.
     */
    fun lastSpeech(samples: FloatArray, sampleRate: Int): Segment? {
        val frame = max(1, (FRAME_MS / 1000.0 * sampleRate).roundToInt())
        val frames = samples.size / frame
        if (frames == 0) return null

        val levels = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (i in f * frame until (f + 1) * frame) sum += samples[i] * samples[i]
            levels[f] = sqrt(sum / frame)
        }
        val sorted = levels.copyOf().also { it.sort() }
        val noise = sorted[floor((frames - 1) * NOISE_QUANTILE).toInt()]
        val threshold = max(Vad.MIN_THRESHOLD, noise * Vad.NOISE_FACTOR)

        var end = frames - 1
        while (end >= 0 && levels[end] <= threshold) end--
        if (end < 0) return null

        val maxGap = (MAX_GAP_MS.toDouble() / FRAME_MS).roundToInt()
        var start = end
        var voiced = 0
        var gap = 0
        var f = end
        while (f >= 0 && gap <= maxGap) {
            if (levels[f] > threshold) {
                start = f
                voiced++
                gap = 0
            } else {
                gap++
            }
            f--
        }

        val pad = (PAD_MS / 1000.0 * sampleRate).roundToInt()
        val from = max(0, start * frame - pad)
        val to = min(samples.size, (end + 1) * frame + pad)
        return Segment(samples.copyOfRange(from, to), voiced * frame * 1000.0 / sampleRate)
    }
}
