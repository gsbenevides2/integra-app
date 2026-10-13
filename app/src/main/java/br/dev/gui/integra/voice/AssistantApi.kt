package br.dev.gui.integra.voice

import br.dev.gui.integra.data.ApiException
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import kotlin.coroutines.coroutineContext

// Modelos de server/modules/assistant/model.ts e server/db/assistant.ts.

@Serializable
data class AssistantAction(val tool: String, val ok: Boolean, val summary: String)

/** `ImageView`: chave no bucket + URL do integra que redireciona para a URL pré-assinada. */
@Serializable
data class AssistantImage(
    val key: String,
    val url: String,
    val mediaType: String = "image/jpeg",
    val source: String = "upload",
    val label: String? = null,
)

@Serializable
data class ChatReply(
    val conversationId: String,
    val reply: String,
    val actions: List<AssistantAction> = emptyList(),
    val images: List<AssistantImage> = emptyList(),
    val endConversation: Boolean = false,
    /** A fala não era para a Integra: nada é falado nem guardado (só um aviso sonoro). */
    val silent: Boolean = false,
)

/**
 * `source` e `speakerVerified` vêm só em turnos de voz: sem a verificação de locutor neste
 * aparelho, a Integra só consulta (não muda nada na casa). Nulos são omitidos do JSON.
 */
@Serializable
data class ChatBody(
    val text: String,
    val conversationId: String? = null,
    val images: List<String>? = null,
    val source: String? = null,
    val speakerVerified: Boolean? = null,
)

@Serializable
data class ConversationSummary(val id: String, val title: String, val updatedAt: String, val ended: Boolean = false)

@Serializable
data class StoredMessage(
    val role: String,
    val text: String,
    val actions: List<AssistantAction> = emptyList(),
    val images: List<AssistantImage> = emptyList(),
)

@Serializable
data class ConversationDetail(val id: String, val title: String = "", val messages: List<StoredMessage> = emptyList())

@Serializable
data class AssistantSettings(
    val extraInstructions: String = "",
    val voiceMatchThreshold: Float = 0.6f,
)

@Serializable
data class AssistantConfig(
    val wakeWordEnabled: Boolean = false,
    val voiceModelUrl: String? = null,
    val voiceModelId: String = "",
)

/** `VoiceProfile` de model.ts: uma das vozes cadastradas do usuário (pessoa ou aparelho). */
@Serializable
data class VoiceProfile(
    val id: String,
    val label: String,
    val embeddings: List<List<Float>> = emptyList(),
    val dims: Int = 0,
    val modelId: String = "",
)

@Serializable
data class VoiceProfileBody(val label: String, val embeddings: List<List<Float>>, val modelId: String)

@Serializable
data class VoiceLabelBody(val label: String)

interface AssistantApi {
    @GET("api/assistant/config") suspend fun config(): AssistantConfig
    @DELETE("api/assistant/conversation") suspend fun closeConversation()
    @GET("api/assistant/conversations") suspend fun conversations(): List<ConversationSummary>
    @GET("api/assistant/conversations/{id}") suspend fun conversation(@Path("id") id: String): ConversationDetail
    @DELETE("api/assistant/conversations/{id}") suspend fun deleteConversation(@Path("id") id: String)
    @GET("api/assistant/settings") suspend fun settings(): AssistantSettings
    @PUT("api/assistant/settings") suspend fun saveSettings(@Body body: AssistantSettings)
    @GET("api/assistant/voice-profiles") suspend fun voiceProfiles(): List<VoiceProfile>
    @POST("api/assistant/voice-profiles") suspend fun createVoiceProfile(@Body body: VoiceProfileBody)
    @PATCH("api/assistant/voice-profiles/{id}") suspend fun renameVoiceProfile(@Path("id") id: String, @Body body: VoiceLabelBody)
    @DELETE("api/assistant/voice-profiles/{id}") suspend fun deleteVoiceProfile(@Path("id") id: String)
}

/** Eventos do `POST /api/assistant/chat/stream` (`StreamEvent` em model.ts). */
sealed interface StreamEvent {
    data class Conversation(val conversationId: String) : StreamEvent
    data class Tool(val tool: String) : StreamEvent
    data class Action(val action: AssistantAction) : StreamEvent
    data class Image(val image: AssistantImage) : StreamEvent
    data class Delta(val text: String) : StreamEvent
}

object AssistantHttp {
    private val JSON = "application/json".toMediaType()

    /**
     * Envia a mensagem e lê a resposta em Server-Sent Events (um `data: <json>` por evento), como o
     * `chatStream.ts`. Devolve a resposta final (`done`); um evento `error` vira exceção.
     */
    suspend fun streamChat(body: ChatBody, onEvent: (StreamEvent) -> Unit): ChatReply = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Integra.url("/api/assistant/chat/stream"))
            .post(Integra.json.encodeToString(ChatBody.serializer(), body).toRequestBody(JSON))
            .header("Accept", "text/event-stream")
            .build()
        val call = Integra.streamingHttp.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.errorMessage(), response.code)
            val source = response.body!!.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val obj = Integra.json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "conversation" -> onEvent(StreamEvent.Conversation(obj.str("conversationId")))
                    "tool" -> onEvent(StreamEvent.Tool(obj.str("tool")))
                    "action" -> onEvent(StreamEvent.Action(Integra.json.decodeFromJsonElement(obj["action"]!!)))
                    "image" -> onEvent(StreamEvent.Image(Integra.json.decodeFromJsonElement(obj["image"]!!)))
                    "delta" -> onEvent(StreamEvent.Delta(obj.str("text")))
                    "done" -> return@withContext Integra.json.decodeFromJsonElement<ChatReply>(obj["reply"]!!)
                    "error" -> throw ApiException(obj["message"]?.jsonPrimitive?.content ?: "O assistente não respondeu")
                }
            }
            throw ApiException("O assistente não respondeu")
        }
    }

    /** `POST /api/assistant/stt` com um WAV 16 kHz mono → texto. */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Integra.url("/api/assistant/stt"))
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val call = Integra.http.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Falha na transcrição", response.code)
            val obj = Integra.json.parseToJsonElement(response.body!!.string()).jsonObject
            obj["text"]?.jsonPrimitive?.content.orEmpty().trim()
        }
    }

    /** `POST /api/assistant/tts` → mp3. */
    suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val payload = JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(text))).toString()
        val request = Request.Builder()
            .url(Integra.url("/api/assistant/tts"))
            .post(payload.toRequestBody(JSON))
            .build()
        val call = Integra.http.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Falha ao gerar a fala", response.code)
            response.body!!.bytes()
        }
    }

    /** `POST /api/assistant/uploads` com a imagem crua. */
    suspend fun upload(bytes: ByteArray, mediaType: String): AssistantImage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Integra.url("/api/assistant/uploads"))
            .post(bytes.toRequestBody(mediaType.toMediaType()))
            .build()
        Integra.http.newCall(request).execute().use { response ->
            if (response.code == 415) throw ApiException("Formato de imagem não suportado", 415)
            if (!response.isSuccessful) throw ApiException("Falha ao enviar a imagem", response.code)
            Integra.json.decodeFromString(AssistantImage.serializer(), response.body!!.string())
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
}
