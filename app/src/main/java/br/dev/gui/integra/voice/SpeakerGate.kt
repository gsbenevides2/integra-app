package br.dev.gui.integra.voice

import android.content.Context
import android.util.Log
import br.dev.gui.integra.Prefs
import br.dev.gui.integra.data.Integra
import kotlinx.coroutines.runBlocking

/**
 * A verificação de locutor aplicada antes de o "Ei Integra" disparar: o modelo,
 * os embeddings cadastrados e o limiar. Igual ao `SpeakerGate` do `wakeWord.ts`.
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
         * publicado, sem perfil cadastrado, ou o modelo mudou desde o cadastro.
         * Null => o "Ei Integra" dispara com qualquer voz (como antes). Bloqueia:
         * chame fora da thread principal.
         */
        fun build(context: Context, prefs: Prefs): SpeakerGate? {
            if (!prefs.hasCredentials) return null
            val api = Integra.api<AssistantApi>()
            val config = runCatching { runBlocking { api.config() } }.getOrNull() ?: return null
            if (config.voiceModelUrl == null) return null
            val profile = runCatching { runBlocking { api.voiceProfile() } }.getOrNull() ?: return null
            if (!profile.enrolled || profile.embeddings.isEmpty()) return null
            if (profile.modelId != config.voiceModelId) return null
            val model = SpeakerModel.load(context, prefs) ?: return null
            val embeddings = profile.embeddings.map { it.toFloatArray() }
            Log.i(TAG, "Verificação de locutor ligada (${embeddings.size} amostras)")
            return SpeakerGate(model, embeddings, prefs.voiceMatchThreshold)
        }
    }
}
