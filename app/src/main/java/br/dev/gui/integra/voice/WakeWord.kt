package br.dev.gui.integra.voice

import android.content.Context
import android.util.Log
import br.dev.gui.integra.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * "Ei Integra" nativo, com o Vosk restrito às mesmas frases do assistente web
 * (`public/components/VoiceAssistantContext/wakeWord.ts` no integra): qualquer outra fala vira
 * `[unk]`, e só um resultado final que seja exatamente uma das frases conta. Ouve o [AudioInput]
 * com a tela ligada ou desligada.
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var lastDetection = 0L

    /**
     * Verificação de locutor: quando presente, só o "Ei Integra" dito pela voz
     * cadastrada dispara. Null => dispara com qualquer voz (comportamento antigo).
     */
    @Volatile private var gate: SpeakerGate? = null

    /** Anel com o áudio recente (~4 s), para checar a voz que disse a frase. */
    private val ring = ShortArray(AudioInput.SAMPLE_RATE * SPEAKER_BUFFER_MS / 1000)
    private var ringWrite = 0
    private var ringFilled = false
    private val ringLock = Any()

    fun setGate(newGate: SpeakerGate?) {
        gate = newGate
    }

    /**
     * O reconhecimento roda numa thread própria para não atrasar a leitura do microfone. Se ficar
     * para trás (aparelho ocupado), descarta os blocos mais antigos: só importa o "agora".
     */
    private val worker = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(30), ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val listener = AudioInput.Listener { samples, count ->
        val copy = samples.copyOf(count)
        if (gate != null) pushRing(copy)
        worker.execute { process(copy) }
    }

    /** Grava o áudio no anel circular (chamado na thread do microfone). */
    private fun pushRing(samples: ShortArray) {
        synchronized(ringLock) {
            for (s in samples) {
                ring[ringWrite] = s
                ringWrite = (ringWrite + 1) % ring.size
                if (ringWrite == 0) ringFilled = true
            }
        }
    }

    /** Snapshot do anel como FloatArray em [-1, 1], em ordem cronológica. */
    private fun ringSnapshot(): FloatArray = synchronized(ringLock) {
        val length = if (ringFilled) ring.size else ringWrite
        FloatArray(length) { i ->
            val idx = if (ringFilled) (ringWrite + i) % ring.size else i
            ring[idx] / 32768f
        }
    }

    @Volatile var isListening = false
        private set

    /** Carrega o modelo (lento, ~1 s): chame fora da thread principal. */
    fun load(): Boolean {
        if (model != null) return true
        if (!VoiceModel.isReady(context)) return false
        return try {
            model = Model(VoiceModel.dir(context).absolutePath)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao carregar o modelo", e)
            false
        }
    }

    fun start() {
        if (isListening) return
        val model = model ?: return
        worker.execute {
            recognizer?.close()
            recognizer = Recognizer(model, AudioInput.SAMPLE_RATE.toFloat(), JSONArray(WAKE_PHRASES + "[unk]").toString())
        }
        if (AudioInput.add(context, listener)) {
            isListening = true
            Log.i(TAG, "Ouvindo \"Ei Integra\"")
        } else {
            Log.w(TAG, "Microfone indisponível")
        }
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        AudioInput.remove(listener)
        worker.execute { recognizer?.close(); recognizer = null }
        Log.i(TAG, "Parou de ouvir \"Ei Integra\"")
    }

    fun release() {
        stop()
        gate?.model?.close()
        gate = null
        worker.execute { model?.close(); model = null }
        worker.shutdown()
    }

    private fun process(samples: ShortArray) {
        val r = recognizer ?: return
        if (r.acceptWaveForm(samples, samples.size)) check(r.result)
    }

    private fun check(hypothesis: String) {
        val text = runCatching { JSONObject(hypothesis).optString("text") }.getOrDefault("").trim()
        if (BuildConfig.DEBUG && text.isNotEmpty()) Log.i(TAG, "ouviu: $text")
        if (text !in WAKE_PHRASES) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDetection < WAKE_COOLDOWN_MS) return
        if (isPaused) return
        // Verificação de locutor: só uma voz cadastrada dispara (quando há gate).
        val gate = gate
        if (gate != null && !verifySpeaker(gate)) return
        // A checagem leva um tempo: se pausou enquanto ela rodava (cadastro), não dispara.
        if (isPaused) return
        lastDetection = now
        onWake()
    }

    /** Roda o modelo sobre a frase recém-ouvida e compara com as vozes cadastradas. */
    private fun verifySpeaker(gate: SpeakerGate): Boolean = try {
        // Só a frase, como no cadastro; se nada se destaca do ruído (voz muito baixa), a janela toda.
        val snapshot = ringSnapshot()
        val speech = SpeechTrim.lastSpeech(snapshot, AudioInput.SAMPLE_RATE)
        val candidate = gate.model.embed(speech?.samples ?: snapshot, AudioInput.SAMPLE_RATE)
        gate.accepts(candidate)
    } catch (e: Exception) {
        // Na dúvida, não dispara: é o trabalho da verificação de locutor.
        Log.w(TAG, "Falha na verificação de locutor", e)
        false
    }

    companion object {
        private const val TAG = "VoiceWake"
        /** Iguais a `WAKE_PHRASES` do assistente web. */
        val WAKE_PHRASES = listOf("ei integra", "integra")
        private const val WAKE_COOLDOWN_MS = 3_000L
        /**
         * Áudio recente guardado para a verificação de locutor. O Vosk só dá o resultado final
         * depois do silêncio de fim de fala, então uma janela curta tinha mais silêncio que frase;
         * a checagem embute só a frase, recortada desta janela pelo [SpeechTrim].
         */
        private const val SPEAKER_BUFFER_MS = 4_000

        /** `pause()` em aberto; o "Ei Integra" só dispara quando não há nenhum. */
        private val pauses = java.util.concurrent.atomic.AtomicInteger(0)

        private val isPaused get() = pauses.get() > 0

        /**
         * Desliga o "Ei Integra" até a função devolvida ser chamada, ex.: enquanto uma voz é
         * cadastrada (dizer "Ei Integra" para gravar não pode abrir uma sessão). As pausas se
         * acumulam: volta quando todas forem liberadas. Igual ao `pauseWakeWord` do web.
         */
        fun pause(): () -> Unit {
            pauses.incrementAndGet()
            val released = java.util.concurrent.atomic.AtomicBoolean(false)
            return { if (released.compareAndSet(false, true)) pauses.decrementAndGet() }
        }
    }
}
