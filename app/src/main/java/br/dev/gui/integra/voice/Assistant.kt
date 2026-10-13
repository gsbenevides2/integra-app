package br.dev.gui.integra.voice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.Me
import br.dev.gui.integra.data.Telemetry
import br.dev.gui.integra.data.userMessage
import br.dev.gui.integra.ui.components.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class AssistantState(val label: String) {
    Idle("Pronta"), Listening("Ouvindo..."), Thinking("Pensando..."), Speaking("Falando...")
}

/** Uma mensagem do chat (`AssistantMessage` do `VoiceAssistantContext`). */
data class AssistantMessage(
    val id: Long,
    val role: String,
    val text: String,
    val actions: List<AssistantAction> = emptyList(),
    val images: List<AssistantImage> = emptyList(),
    /** A resposta ainda está sendo escrita (pontinhos até as primeiras palavras). */
    val pending: Boolean = false,
    /** A ferramenta rodando agora, ex.: "gerar_imagem" (mostrada como progresso). */
    val activity: String? = null,
    /** Recebida ao vivo nesta sessão e ainda não "digitada" (ver [Assistant.markTyped]). */
    val live: Boolean = false,
    /** Por que a resposta falhou; [Assistant.retry] manda a mesma mensagem de novo. */
    val error: String? = null,
    val retry: RetryPayload? = null,
) {
    val isUser get() = role == "user"
}

/**
 * [speakerVerified] é null para texto digitado; num turno de voz diz se a voz casou com o perfil
 * cadastrado neste aparelho (sem isso, a Integra só consulta).
 */
data class RetryPayload(
    val text: String,
    val images: List<AssistantImage>,
    val userMessageId: Long,
    val speakerVerified: Boolean? = null,
)

/**
 * O assistente de voz, nativo. É o `VoiceAssistantContext/index.tsx` do web: "Ei Integra" → plim →
 * ouvir → transcrever → resposta em stream → falar → ouvir de novo por 6 s (sem precisar da
 * palavra) até o silêncio ou uma despedida. Vive no processo do app (e não numa tela), para o
 * "Ei Integra" funcionar também com a tela desligada.
 */
object Assistant {
    private const val TAG = "Assistant"
    const val CHAT_ROUTE_KEY = "POST /api/assistant/chat"
    const val SETTINGS_ROUTE_KEY = "PUT /api/assistant/settings"
    const val HISTORY_ROUTE_KEY = "GET /api/assistant/conversations"
    const val UPLOAD_ROUTE_KEY = "POST /api/assistant/uploads"

    /** Igual ao servidor: depois disso parado, a próxima mensagem começa outra conversa. */
    private const val RESUME_WINDOW_MS = 10 * 60 * 1000L
    /** Quanto esperar pelas primeiras palavras depois do "Ei Integra". */
    private const val FIRST_TURN_MS = 8_000L
    /** Depois de uma resposta, ouve por mais este tempo sem precisar da palavra. */
    private const val FOLLOW_UP_MS = 6_000L
    /** O aviso sonoro de início dura 300 ms: o eco dele não pode contar como fala do usuário. */
    private const val CHIME_ECHO_MS = 400L

    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state
    private val _hearingSpeech = MutableStateFlow(false)
    val hearingSpeech: StateFlow<Boolean> = _hearingSpeech
    private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val messages: StateFlow<List<AssistantMessage>> = _messages
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId
    private val _attachments = MutableStateFlow<List<AssistantImage>>(emptyList())
    val attachments: StateFlow<List<AssistantImage>> = _attachments
    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading
    private val _toasts = MutableSharedFlow<Pair<String, ToastType>>(extraBufferCapacity = 8)
    /** Avisos para a UI mostrar como toast. */
    val toasts: SharedFlow<Pair<String, ToastType>> = _toasts

    /** O usuário tem a rota do chat (vem do `/api/me`; guardado para o "Ei Integra" com a tela desligada). */
    @Volatile var allowed = false
        private set

    /** A tela do assistente já fez o seu "como se tivesse ouvido a palavra" nesta execução. */
    var autoStarted = false

    private var session: Job? = null
    private var conversationEnded = false
    private var lastActivity = 0L
    private var nextId = 0L
    /** A verificação de locutor deste aparelho; null até carregar (ou quando não há perfil). */
    @Volatile private var speakerGate: SpeakerGate? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        allowed = br.dev.gui.integra.Prefs(context).assistantAllowed
    }

    fun updateMe(me: Me) {
        allowed = me.hasRoute(CHAT_ROUTE_KEY)
        br.dev.gui.integra.Prefs(context).assistantAllowed = allowed
    }

    /** "Ei Integra": começa do zero, ou interrompe a fala (barge-in). Ignorado ouvindo/pensando. */
    fun onWakeWord() = scope.launch {
        val s = _state.value
        Log.i(TAG, "Ei Integra (estado=$s, permitido=$allowed)")
        if (s == AssistantState.Idle || s == AssistantState.Speaking) start()
    }

    private fun toast(message: String, type: ToastType = ToastType.Error) {
        _toasts.tryEmit(message to type)
    }

    private fun setState(next: AssistantState) {
        if (_state.value != next) Log.i(TAG, "estado: $next")
        _state.value = next
    }

    private fun addMessage(message: AssistantMessage): Long {
        val id = nextId++
        _messages.update { it + message.copy(id = id) }
        return id
    }

    private fun updateMessage(id: Long, transform: (AssistantMessage) -> AssistantMessage?) {
        _messages.update { list -> list.mapNotNull { if (it.id == id) transform(it) else it } }
    }

    /** Para o que estiver acontecendo (sem mudar o estado). */
    private fun halt() {
        session?.cancel()
        session = null
        if (conversationEnded) {
            conversationEnded = false
            _messages.value = emptyList()
        }
        _hearingSpeech.value = false
    }

    fun stop() {
        halt()
        setState(AssistantState.Idle)
    }

    private fun launchSession(block: suspend CoroutineScope.() -> Unit) {
        halt()
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Falha no assistente", e)
                Telemetry.error("Falha no assistente", e)
                if (session === job) {
                    toast(e.userMessage("Falha no assistente"))
                    _hearingSpeech.value = false
                    setState(AssistantState.Idle)
                }
            }
        }
        session = job
    }

    /** Começa a ouvir (como se a palavra tivesse sido dita). */
    fun start() {
        if (!allowed) return
        launchSession {
            Speaker.chime()
            val heard = listen(FIRST_TURN_MS, skipMs = CHIME_ECHO_MS)
            // Não é a voz cadastrada (ou não se sabe de quem é): não responde, só um aviso.
            if (heard.voice == VoiceCheck.Rejected) {
                Speaker.chime()
                setState(AssistantState.Idle)
                return@launchSession
            }
            converse(heard.text, null, heard.voice.speakerVerified())
        }
    }

    /** Manda texto digitado, sem reconhecimento de voz. */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (!allowed || trimmed.isEmpty()) return
        launchSession { converse(trimmed, null, null) }
    }

    /** Manda de novo a mensagem de uma resposta que falhou (a troca vai para o fim do chat). */
    fun retry(replyId: Long) {
        val payload = _messages.value.firstOrNull { it.id == replyId }?.retry ?: return
        if (!allowed) return
        launchSession {
            _messages.update { list -> list.filter { it.id != replyId && it.id != payload.userMessageId } }
            converse(payload.text, payload.images, payload.speakerVerified)
        }
    }

    /** Verificação de locutor com o modelo do aparelho; carrega na primeira vez e reaproveita. */
    private suspend fun currentGate(): SpeakerGate? {
        speakerGate?.let { return it }
        val prefs = br.dev.gui.integra.Prefs(context)
        return withContext(Dispatchers.IO) {
            runCatching { SpeakerGate.build(context, prefs) }
                .onFailure { Log.w(TAG, "Verificação de locutor indisponível", it) }
                .getOrNull()
        }?.also { speakerGate = it }
    }

    private suspend fun listen(timeoutMs: Long, skipMs: Long = 0): Heard {
        val gate = currentGate()
        setState(AssistantState.Listening)
        val heard = Recorder.listenOnce(
            context,
            timeoutMs,
            skipMs = skipMs,
            verify = gate?.let { g -> { samples: FloatArray -> verifyVoice(g, samples) } },
        ) { phase ->
            Log.i(TAG, "fase: $phase")
            if (phase == ListenPhase.Speech) _hearingSpeech.value = true else setState(AssistantState.Thinking)
        }
        _hearingSpeech.value = false
        Log.i(TAG, "ouviu: \"${heard.text}\" (voz=${heard.voice})")
        return heard
    }

    /** Se a gravação casa com alguma voz cadastrada (a frase sozinha, como no cadastro). */
    private fun verifyVoice(gate: SpeakerGate, samples: FloatArray): Boolean {
        val speech = SpeechTrim.lastSpeech(samples, AudioInput.SAMPLE_RATE)
        val candidate = gate.model.embed(speech?.samples ?: samples, AudioInput.SAMPLE_RATE)
        val score = gate.bestScore(candidate)
        Log.i(TAG, "locutor: escore %.3f (limiar %.2f, trecho %d amostras de %d)"
            .format(score, gate.threshold, speech?.samples?.size ?: samples.size, samples.size))
        return score >= gate.threshold
    }

    private fun VoiceCheck.speakerVerified(): Boolean = this == VoiceCheck.Verified

    /** Responde e continua ouvindo continuações até o usuário ficar quieto. */
    private suspend fun converse(firstText: String, firstImages: List<AssistantImage>?, firstVerified: Boolean?) {
        var text = firstText
        var images = firstImages
        var verified = firstVerified
        while (text.isNotEmpty()) {
            val outcome = answer(text, images, verified)
            images = null
            when (outcome) {
                Outcome.Silent -> {
                    Speaker.chime()
                    break
                }
                Outcome.Continue -> Unit
                Outcome.Ended, Outcome.Failed -> break
            }
            val heard = listen(FOLLOW_UP_MS)
            // Um follow-up de outra voz (TV, outra pessoa) encerra a sessão, sem responder.
            if (heard.voice == VoiceCheck.Rejected) {
                Speaker.chime()
                break
            }
            text = heard.text
            verified = heard.voice.speakerVerified()
        }
        setState(AssistantState.Idle)
    }

    private enum class Outcome { Continue, Ended, Failed, Silent }

    private suspend fun answer(text: String, retryImages: List<AssistantImage>?, verified: Boolean?): Outcome {
        // Uma conversa parada não é retomada: começa outra, na tela também.
        if (_conversationId.value != null && System.currentTimeMillis() - lastActivity > RESUME_WINDOW_MS) {
            _conversationId.value = null
            _messages.value = emptyList()
        }
        // Os anexos pendentes vão com o que for dito ou digitado em seguida.
        val images = retryImages ?: _attachments.value
        if (retryImages == null) _attachments.value = emptyList()
        val userMessageId = addMessage(AssistantMessage(0, "user", text, images = images))
        setState(AssistantState.Thinking)
        val replyId = addMessage(AssistantMessage(0, "assistant", "", pending = true, live = true))
        val body = ChatBody(
            text = text,
            conversationId = _conversationId.value,
            images = images.map { it.key }.ifEmpty { null },
            source = verified?.let { "voice" },
            speakerVerified = verified,
        )
        val reply = try {
            AssistantHttp.streamChat(body) { event ->
                scope.launch {
                    when (event) {
                        is StreamEvent.Delta -> updateMessage(replyId) { it.copy(text = it.text + event.text, activity = null) }
                        is StreamEvent.Tool -> updateMessage(replyId) { it.copy(activity = event.tool) }
                        is StreamEvent.Action -> updateMessage(replyId) { it.copy(activity = null, actions = it.actions + event.action) }
                        is StreamEvent.Image -> updateMessage(replyId) { it.copy(images = it.images + event.image) }
                        is StreamEvent.Conversation -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            // Mantém o que já foi escrito, mas para de mostrar como em andamento.
            updateMessage(replyId) { m -> if (m.text.isNotEmpty()) m.copy(pending = false, activity = null) else null }
            throw e
        } catch (e: Throwable) {
            // A falha fica no chat, com um jeito de mandar de novo (o toast aparece sobre as outras telas).
            val message = e.userMessage("O assistente não respondeu")
            toast(message)
            Telemetry.error("Assistente não respondeu", e)
            updateMessage(replyId) {
                it.copy(
                    pending = false,
                    activity = null,
                    error = message,
                    retry = RetryPayload(text, images, userMessageId, verified),
                )
            }
            return Outcome.Failed
        }
        if (reply.silent) {
            // Não era para a Integra: a troca não fica no chat.
            _messages.update { list -> list.filter { it.id != userMessageId && it.id != replyId } }
            return Outcome.Silent
        }
        _conversationId.value = if (reply.endConversation) null else reply.conversationId
        lastActivity = System.currentTimeMillis()
        updateMessage(replyId) {
            AssistantMessage(replyId, "assistant", reply.reply, reply.actions, reply.images, live = it.live)
        }

        setState(AssistantState.Speaking)
        try {
            Speaker.speak(context, reply.reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Sem voz a resposta continua na tela: segue ouvindo.
            toast(e.userMessage("Falha ao gerar a fala"))
        }
        if (reply.endConversation) {
            conversationEnded = true
            return Outcome.Ended
        }
        return Outcome.Continue
    }

    /** Uma resposta ao vivo terminou de ser digitada: passa a aparecer completa. */
    fun markTyped(replyId: Long) {
        if (_messages.value.any { it.id == replyId && it.live }) updateMessage(replyId) { it.copy(live = false) }
    }

    /** Para e começa outra conversa (o histórico continua salvo). */
    suspend fun reset() {
        stop()
        _messages.value = emptyList()
        _conversationId.value = null
        runCatching { Integra.api<AssistantApi>().closeConversation() }
    }

    /** Abre uma conversa do histórico para ler e continuar. */
    fun openConversation(id: String) {
        stop()
        val opening = scope.launch {
            val detail = runCatching { Integra.api<AssistantApi>().conversation(id) }.getOrElse {
                toast("Falha ao abrir a conversa")
                return@launch
            }
            conversationEnded = false
            lastActivity = System.currentTimeMillis()
            _conversationId.value = detail.id
            _messages.value = detail.messages.map {
                AssistantMessage(nextId++, it.role, it.text, it.actions, it.images)
            }
        }
        session = opening
    }

    fun removeAttachment(key: String) {
        _attachments.update { list -> list.filter { it.key != key } }
    }

    /** Reduz a foto (lado maior 1600 px, JPEG 85%, como o web) e envia. */
    fun attachImage(uri: Uri) = scope.launch {
        _uploading.value = true
        try {
            val (bytes, type) = withContext(Dispatchers.IO) { prepareImage(uri) }
            val image = AssistantHttp.upload(bytes, type)
            _attachments.update { it + image }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            toast(e.userMessage("Falha ao enviar a imagem"))
        } finally {
            _uploading.value = false
        }
    }

    private fun prepareImage(uri: Uri): Pair<ByteArray, String> {
        val resolver = context.contentResolver
        val type = resolver.getType(uri) ?: "image/jpeg"
        val original = resolver.openInputStream(uri)!!.use { it.readBytes() }
        if (type == "image/gif") return original to type
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return original to type
        if (longest <= MAX_UPLOAD_DIMENSION && original.size < 1024 * 1024) return original to type
        var sample = 1
        while (longest / (sample * 2) >= MAX_UPLOAD_DIMENSION) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return original to type
        val scale = minOf(1f, MAX_UPLOAD_DIMENSION.toFloat() / max(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
        } else decoded
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to "image/jpeg"
    }

    private const val MAX_UPLOAD_DIMENSION = 1600
}
