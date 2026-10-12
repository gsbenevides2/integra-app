package br.dev.gui.integra.voice

/** Ninguém falou antes do tempo acabar: quem chamou pode simplesmente pedir de novo. */
class NoSpeechException : Exception("Não ouvi você dizer \"Ei Integra\"")

/**
 * Cadastro guiado de uma voz, igual ao `enrollment.ts` do web: quantas amostras,
 * o que torna uma amostra usável, e o que fazer quando uma destoa das outras.
 */
object Enrollment {
    /** Amostras de "Ei Integra" guardadas por voz. */
    const val SAMPLES = 4
    /** Quanto esperar cada amostra começar. */
    const val SILENCE_TIMEOUT_MS = 8_000L
    /** Menos áudio com voz que isto é uma tosse ou uma palavra cortada, não a frase. */
    const val MIN_VOICED_MS = 300
    /** Gravações inúteis (nada ouvido, curta demais) toleradas antes de desistir. */
    const val MAX_RETRIES = 4
    /**
     * A similaridade média de cada amostra com as outras precisa chegar a isto. Abaixo, a amostra
     * provavelmente é ruído, outra voz ou um jeito muito diferente de falar, e é gravada de novo.
     */
    const val MIN_CONSISTENCY = 0.45f
    /** Amostras destoantes regravadas antes de desistir. */
    const val MAX_REDOS = 2

    /** O que a pessoa deve fazer agora, para a mensagem de progresso. */
    sealed interface Step {
        /** Diga a frase: amostra [sample] de [total]. */
        data class Record(val sample: Int, val total: Int) : Step
        /** A última gravação ficou inútil: diga de novo. */
        data object Retry : Step
        /** Uma amostra não bateu com as outras: diga mais uma vez. */
        data object Redo : Step
    }

    class RecordedVoice(
        /** Um embedding L2-normalizado por amostra guardada. */
        val embeddings: List<FloatArray>,
        /**
         * Similaridade média entre as amostras (cosseno, 0..1). É mais ou menos o que esta voz vai
         * pontuar no "Ei Integra", por isso é comparada com o limiar.
         */
        val consistency: Float,
    )

    /** A mensagem de progresso de cada passo (`describeEnrollmentStep` do web). */
    fun describe(step: Step): String = when (step) {
        is Step.Record -> "Diga \"Ei Integra\" (${step.sample}/${step.total})…"
        Step.Retry -> "Não deu para ouvir direito. Diga \"Ei Integra\" de novo…"
        Step.Redo -> "Uma gravação destoou das outras. Diga \"Ei Integra\" mais uma vez…"
    }

    /** A similaridade média de cada embedding com todos os outros. */
    fun consistencyScores(embeddings: List<FloatArray>): List<Float> {
        if (embeddings.size < 2) return embeddings.map { 1f }
        return embeddings.mapIndexed { i, embedding ->
            var sum = 0f
            embeddings.forEachIndexed { j, other -> if (i != j) sum += Fbank.cosine(embedding, other) }
            sum / (embeddings.size - 1)
        }
    }

    /**
     * Grava uma voz: [SAMPLES] "Ei Integra", cada um recortado na fala e embutido localmente. Uma
     * gravação sem fala, ou com pouca, é pedida de novo; uma amostra que não soa como as outras é
     * substituída. Lança com uma mensagem para a pessoa quando não consegue uma voz usável, e
     * qualquer outro erro (ex.: o microfone) como veio.
     */
    suspend fun recordVoice(
        embed: suspend (FloatArray) -> FloatArray,
        capture: suspend (silenceTimeoutMs: Long) -> FloatArray,
        onStep: (Step) -> Unit,
    ): RecordedVoice {
        var retries = 0
        suspend fun recordOne(step: Step): FloatArray {
            onStep(step)
            while (true) {
                try {
                    val samples = capture(SILENCE_TIMEOUT_MS)
                    val speech = SpeechTrim.lastSpeech(samples, AudioInput.SAMPLE_RATE)
                    if (speech != null && speech.voicedMs >= MIN_VOICED_MS) return embed(speech.samples)
                } catch (_: NoSpeechException) {
                }
                if (++retries > MAX_RETRIES) {
                    throw IllegalStateException("Não consegui ouvir você direito. Tente de novo num lugar mais silencioso.")
                }
                onStep(Step.Retry)
            }
        }

        val embeddings = ArrayList<FloatArray>()
        for (i in 1..SAMPLES) embeddings += recordOne(Step.Record(i, SAMPLES))

        var scores = consistencyScores(embeddings)
        var redos = 0
        while (scores.min() < MIN_CONSISTENCY) {
            if (redos++ >= MAX_REDOS) {
                throw IllegalStateException(
                    "As gravações ficaram muito diferentes entre si. Tente de novo num lugar silencioso, " +
                        "falando sempre do mesmo jeito.",
                )
            }
            embeddings[scores.indexOf(scores.min())] = recordOne(Step.Redo)
            scores = consistencyScores(embeddings)
        }
        return RecordedVoice(embeddings, scores.average().toFloat())
    }
}
