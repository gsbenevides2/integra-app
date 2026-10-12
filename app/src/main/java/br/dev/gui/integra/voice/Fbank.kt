package br.dev.gui.integra.voice

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filterbank "fbank80" compatível com o Kaldi, a entrada que o modelo WeSpeaker
 * espera. É a tradução literal, com os MESMOS parâmetros, do `fbank.ts` do
 * assistente web (`public/components/VoiceAssistantContext/fbank.ts`), para que
 * uma voz cadastrada num dispositivo verifique no outro. Mantenha os dois em
 * sincronia.
 */
object Fbank {
    const val SAMPLE_RATE = 16000
    const val NUM_BINS = 80
    private const val FRAME_LENGTH_MS = 25
    private const val FRAME_SHIFT_MS = 10
    private const val PREEMPH = 0.97
    private const val LOW_FREQ = 20.0
    /** 0 = Nyquist (sampleRate / 2). */
    private const val HIGH_FREQ = 0.0
    private val EPSILON = Math.exp(-50.0)

    private fun mel(freqHz: Double): Double = 1127.0 * ln(1.0 + freqHz / 700.0)

    private fun roundUpToPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p *= 2
        return p
    }

    private fun poveyWindow(length: Int): DoubleArray {
        val w = DoubleArray(length)
        for (i in 0 until length) {
            val hann = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (length - 1))
            w[i] = hann.pow(0.85)
        }
        return w
    }

    private class Bank(val offset: Int, val weights: DoubleArray)

    private fun melBanks(fftSize: Int, sampleRate: Int): Array<Bank> {
        val numFftBins = fftSize / 2
        val nyquist = sampleRate / 2.0
        val highFreq = if (HIGH_FREQ <= 0.0) nyquist else HIGH_FREQ
        val melLow = mel(LOW_FREQ)
        val melHigh = mel(highFreq)
        val melDelta = (melHigh - melLow) / (NUM_BINS + 1)
        val fftBinWidth = sampleRate.toDouble() / fftSize

        return Array(NUM_BINS) { bin ->
            val leftMel = melLow + bin * melDelta
            val centerMel = melLow + (bin + 1) * melDelta
            val rightMel = melLow + (bin + 2) * melDelta
            var offset = -1
            val weights = ArrayList<Double>()
            for (i in 0 until numFftBins) {
                val m = mel(fftBinWidth * i)
                if (m <= leftMel || m >= rightMel) continue
                val weight =
                    if (m <= centerMel) (m - leftMel) / (centerMel - leftMel)
                    else (rightMel - m) / (rightMel - centerMel)
                if (offset < 0) offset = i
                weights.add(weight)
            }
            Bank(if (offset < 0) 0 else offset, weights.toDoubleArray())
        }
    }

    /** FFT iterativa radix-2, in-place, sobre [re]/[im]. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    class Features(val data: FloatArray, val frames: Int, val bins: Int)

    /**
     * Calcula fbank80 de uma fala. [samples] é mono em [-1, 1] a 16 kHz. Retorna
     * um FloatArray achatado de `frames * 80`, row-major, pronto para o ONNX.
     */
    fun compute(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): Features {
        val frameLength = Math.round(FRAME_LENGTH_MS / 1000.0 * sampleRate).toInt()
        val frameShift = Math.round(FRAME_SHIFT_MS / 1000.0 * sampleRate).toInt()
        val fftSize = roundUpToPowerOfTwo(frameLength)
        val window = poveyWindow(frameLength)
        val banks = melBanks(fftSize, sampleRate)
        val bins = NUM_BINS

        val frames =
            if (samples.size < frameLength) 0
            else 1 + (samples.size - frameLength) / frameShift
        val out = FloatArray(frames * bins)

        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val frame = DoubleArray(frameLength)

        for (f in 0 until frames) {
            val start = f * frameShift
            // Escala para a faixa int16 (o WeSpeaker multiplica por 1<<15 antes do fbank).
            for (i in 0 until frameLength) frame[i] = samples[start + i] * 32768.0
            // Remove o nível DC.
            var mean = 0.0
            for (i in 0 until frameLength) mean += frame[i]
            mean /= frameLength
            for (i in 0 until frameLength) frame[i] -= mean
            // Pré-ênfase (o Kaldi usa a primeira amostra no índice 0).
            for (i in frameLength - 1 downTo 1) frame[i] -= PREEMPH * frame[i - 1]
            frame[0] -= PREEMPH * frame[0]
            // Janela + zero pad.
            re.fill(0.0)
            im.fill(0.0)
            for (i in 0 until frameLength) re[i] = frame[i] * window[i]

            fft(re, im)

            val base = f * bins
            for (b in 0 until bins) {
                val bank = banks[b]
                var energy = 0.0
                for (k in bank.weights.indices) {
                    val idx = bank.offset + k
                    val power = re[idx] * re[idx] + im[idx] * im[idx]
                    energy += bank.weights[k] * power
                }
                out[base + b] = ln(max(energy, EPSILON)).toFloat()
            }
        }

        // CMN: subtrai a média de cada dimensão ao longo dos frames.
        if (frames > 0) {
            for (b in 0 until bins) {
                var mean = 0.0
                for (f in 0 until frames) mean += out[f * bins + b]
                mean /= frames
                val m = mean.toFloat()
                for (f in 0 until frames) out[f * bins + b] -= m
            }
        }

        return Features(out, frames, bins)
    }

    /** L2-normaliza um vetor, para que o cosseno seja um produto escalar. */
    fun normalize(vector: FloatArray): FloatArray {
        var norm = 0.0
        for (v in vector) norm += v * v
        val n = (sqrt(norm)).toFloat().let { if (it == 0f) 1f else it }
        return FloatArray(vector.size) { vector[it] / n }
    }

    /** Cosseno de dois vetores já L2-normalizados (um produto escalar). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val length = minOf(a.size, b.size)
        var dot = 0.0
        for (i in 0 until length) dot += a[i] * b[i]
        return dot.toFloat()
    }
}
