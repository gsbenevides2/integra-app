package br.dev.gui.integra.data

import android.content.Context
import android.webkit.WebSettings
import br.dev.gui.integra.Prefs
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.openobserve.android.okhttp.OpenObserveEventListener
import com.openobserve.android.okhttp.OpenObserveInterceptor
import com.openobserve.android.trace.TracingHeaderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Acesso às APIs do integra. Todas as requisições ao host do integra levam o Basic Auth (usuário +
 * App password do Authentik) e o User-Agent `IntegraApp/<token>` liberado na Cloudflare. Outros
 * hosts (ex.: a URL pré-assinada do S3 das imagens do assistente) não recebem esses headers.
 *
 * Uso: `Integra.api<TuyaApi>().devices()` dentro de uma corrotina.
 */
object Integra {
    lateinit var appContext: Context
        private set

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
        isLenient = true
    }

    /** Chamado pelo [br.dev.gui.integra.App]. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs by lazy { Prefs(appContext) }
    private val defaultUserAgent by lazy { runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("Android") }

    /** User-Agent atual (muda quando as credenciais mudam). */
    fun userAgent(): String = prefs.userAgent(defaultUserAgent)

    /** Headers de autenticação para quem monta requisições à mão (ex.: ExoPlayer). */
    fun authHeaders(): Map<String, String> = prefs.authHeaders() + ("User-Agent" to userAgent())

    fun isIntegraHost(host: String?) = host != null && (host == Prefs.HOST || host.endsWith("." + Prefs.HOST))

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!isIntegraHost(request.url.host)) return@Interceptor chain.proceed(request)
        val builder = request.newBuilder().header("User-Agent", userAgent())
        for ((k, v) in prefs.authHeaders()) builder.header(k, v)
        val response = chain.proceed(builder.build())
        // Sem sessão válida o proxy do Authentik redireciona para o login do SSO em vez de 401.
        val landed = response.request.url
        if (!isIntegraHost(landed.host) && isSsoLogin(landed.encodedPath)) {
            response.close()
            AuthState.failed()
            throw AuthFailedException()
        }
        if (response.code == 401) AuthState.failed()
        response
    }

    private fun isSsoLogin(path: String) = path.startsWith("/application/o/authorize") || path.startsWith("/if/flow/")

    val http: OkHttpClient by lazy {
        val firstParty = mapOf(Prefs.HOST to setOf(TracingHeaderType.TRACECONTEXT))
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            // RUM: cada requisição vira um "resource" e leva o traceparent para o backend.
            .addInterceptor(OpenObserveInterceptor.Builder(firstParty).build())
            .eventListenerFactory(OpenObserveEventListener.Factory())
            .build()
    }

    /**
     * Cliente sem timeout de leitura, para streams longos (SSE do assistente, vídeo das câmeras).
     * Fica sem o interceptor do RUM: para medir o tamanho da resposta ele lê o corpo (`peekBody`),
     * o que trava um stream que nunca termina.
     */
    val streamingHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(Prefs.HOME_URL.trimEnd('/') + "/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    inline fun <reified T> api(): T = retrofit.create(T::class.java)

    /** URL absoluta para um caminho do integra (ex.: "/api/assistant/images/x"). */
    fun url(path: String): String = if (path.startsWith("http")) path else Prefs.HOME_URL.trimEnd('/') + path
}

class AuthFailedException : IOException("Usuário ou senha inválidos")

/** Avisa a UI quando o Authentik recusa as credenciais (a tela de login é aberta). */
object AuthState {
    @Volatile var listener: (() -> Unit)? = null
    fun failed() { listener?.invoke() }
}

/** Erro de uma chamada à API, com a mensagem que o servidor mandou (`{error}` ou `{message}`). */
class ApiException(message: String, val status: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** Converte a exceção de uma chamada na mensagem para o usuário. */
fun Throwable.userMessage(fallback: String = "Erro desconhecido"): String = when (this) {
    is ApiException -> message ?: fallback
    is HttpException -> serverMessage(this) ?: "Erro ${code()}"
    is AuthFailedException -> message!!
    is IOException -> "Sem conexão com o servidor"
    else -> message ?: fallback
}

private fun serverMessage(e: HttpException): String? = runCatching {
    val body = e.response()?.errorBody()?.string() ?: return null
    val obj = Integra.json.parseToJsonElement(body) as? JsonObject ?: return body.take(200).ifBlank { null }
    (obj["error"] ?: obj["message"])?.jsonPrimitive?.content
}.getOrNull()

/** Código HTTP de uma falha (ou null se não veio do servidor). */
val Throwable.httpStatus: Int? get() = (this as? HttpException)?.code() ?: (this as? ApiException)?.status

/**
 * Executa uma chamada e devolve [Result]. Ex.:
 * ```
 * apiCall { Integra.api<CronsApi>().run(name) }
 *     .onSuccess { … }.onFailure { toast.error(it.userMessage()) }
 * ```
 */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** Lê um corpo de erro HTTP do OkHttp como mensagem. */
fun Response.errorMessage(): String {
    val body = runCatching { body?.string() }.getOrNull().orEmpty()
    val parsed = runCatching {
        (Integra.json.parseToJsonElement(body) as? JsonObject)?.let { (it["error"] ?: it["message"])?.jsonPrimitive?.content }
    }.getOrNull()
    return parsed ?: "Erro $code"
}
