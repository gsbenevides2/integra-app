package br.dev.gui.integra.voice

import android.content.Context
import android.util.Log
import br.dev.gui.integra.Prefs
import br.dev.gui.integra.data.Integra
import kotlinx.coroutines.runBlocking

/**
 * A verificação de locutor aplicada antes de o "Ei Integra" disparar: o modelo,
 * os embeddings de todas as vozes cadastradas (juntos) e o limiar. Igual ao
 * `SpeakerGate` do `wakeWord.ts`: dispara se casar com QUALQUER voz.
 */
class SpeakerGate(
    val model: SpeakerModel,
    val embeddings: List<FloatArray>,
    val threshold: Float,
) {
    /** Maior cosseno entre a voz ouvida e os embeddings cadastrados ≥ limiar. */
    fun accepts(candidate: FloatArray): Boolean {
        var best = Float.NEGATIVE_INFINITY
        for (enrolled in embeddings) best = maxOf(best, Fbank.cosine(candidate, enrolled))
        return best >= threshold
    }

    companion object {
        private const val TAG = "SpeakerGate"

        /**
         * Monta a verificação, ou null quando não há o que verificar: modelo não
         * publicado ou nenhuma voz cadastrada com o modelo atual.
         * Null => o "Ei Integra" dispara com qualquer voz (como antes). Bloqueia:
         * chame fora da thread principal.
         */
        fun build(context: Context, prefs: Prefs): SpeakerGate? {
            if (!prefs.hasCredentials) return null
            val api = Integra.api<AssistantApi>()
            val config = runCatching { runBlocking { api.config() } }.getOrNull() ?: return null
            if (config.voiceModelUrl == null) return null
            val voices = runCatching { runBlocking { api.voiceProfiles() } }.getOrNull() ?: return null
            val current = voices.filter { it.modelId == config.voiceModelId }
            val embeddings = current.flatMap { voice -> voice.embeddings.map { it.toFloatArray() } }
            if (embeddings.isEmpty()) return null
            val model = SpeakerModel.load(context, prefs) ?: return null
            Log.i(TAG, "Verificação de locutor ligada (${current.size} vozes, ${embeddings.size} amostras)")
            return SpeakerGate(model, embeddings, prefs.voiceMatchThreshold)
        }
    }
}
