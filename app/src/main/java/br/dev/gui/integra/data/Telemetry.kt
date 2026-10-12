package br.dev.gui.integra.data

import android.content.Context
import android.util.Log
import br.dev.gui.integra.BuildConfig
import br.dev.gui.integra.Prefs
import com.openobserve.android.OpenObserve
import com.openobserve.android.compose.enableComposeActionTracking
import com.openobserve.android.core.configuration.Configuration
import com.openobserve.android.log.Logger
import com.openobserve.android.log.Logs
import com.openobserve.android.log.LogsConfiguration
import com.openobserve.android.privacy.TrackingConsent
import com.openobserve.android.rum.GlobalRumMonitor
import com.openobserve.android.rum.Rum
import com.openobserve.android.rum.RumConfiguration
import com.openobserve.android.rum.RumErrorSource
import com.openobserve.android.rum.RumSessionListener
import com.openobserve.android.api.SdkCore
import com.openobserve.android.rum.tracking.ViewTrackingStrategy
import com.openobserve.android.sessionreplay.ImagePrivacy
import com.openobserve.android.sessionreplay.SessionReplay
import com.openobserve.android.sessionreplay.SessionReplayConfiguration
import com.openobserve.android.sessionreplay.TextAndInputPrivacy
import com.openobserve.android.sessionreplay.TouchPrivacy
import com.openobserve.android.sessionreplay.compose.ComposeExtensionSupport
import com.openobserve.android.trace.TracingHeaderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Sem rastreamento automático de activities: as views são abertas à mão (uma por tela Compose). */
private object ManualViews : ViewTrackingStrategy {
    override fun register(sdkCore: SdkCore, context: Context) = Unit
    override fun unregister(context: Context?) = Unit
}

/** `GET /client-config` (rota pública do integra, server/modules/client-config). */
@Serializable
data class ClientConfig(val version: String = "", val rum: RumSettings? = null)

@Serializable
data class RumSettings(val clientToken: String, val site: String, val organization: String, val env: String = "production")

/**
 * RUM, logs e session replay do OpenObserve (SDK Android beta), equivalentes ao
 * `public/instrumentFrontend.ts` do web. A config vem do `/client-config` e fica em cache, para o
 * SDK subir já no início das próximas aberturas.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    const val SERVICE = "integra-android"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId

    @Volatile var started = false
        private set
    private var logger: Logger? = null

    fun init(context: Context) {
        val prefs = Prefs(context)
        prefs.clientConfigJson?.let { cached ->
            runCatching { Integra.json.decodeFromString<ClientConfig>(cached) }.getOrNull()?.rum?.let { start(context, it) }
        }
        scope.launch {
            val fresh = runCatching { fetchConfig() }.onFailure { Log.w(TAG, "client-config", it) }.getOrNull() ?: return@launch
            prefs.clientConfigJson = Integra.json.encodeToString(ClientConfig.serializer(), fresh)
            // Uma config nova (ex.: token trocado) vale a partir da próxima abertura.
            if (!started) fresh.rum?.let { start(context, it) }
        }
    }

    /** A rota é pública (liberada no Authentik/Cloudflare), mas manda os headers do app mesmo assim. */
    private fun fetchConfig(): ClientConfig {
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(Integra.url("/client-config")).apply {
            for ((k, v) in Integra.authHeaders()) header(k, v)
        }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return Integra.json.decodeFromString(response.body!!.string())
        }
    }

    @Synchronized
    private fun start(context: Context, rum: RumSettings) {
        if (started) return
        val base = "https://${rum.site}/rum/v1/${rum.organization}"
        try {
            val config = Configuration.Builder(rum.clientToken, rum.env, "", SERVICE)
                .setFirstPartyHostsWithHeaderType(mapOf(Prefs.HOST to setOf(TracingHeaderType.TRACECONTEXT)))
                .setVersion(BuildConfig.VERSION_NAME)
                .setCrashReportsEnabled(true)
                .build()
            OpenObserve.initialize(context, config, TrackingConsent.GRANTED)
            Rum.enable(
                RumConfiguration.Builder(SERVICE)
                    .useCustomEndpoint("$base/rum")
                    .setSessionSampleRate(100f)
                    // As telas são Compose numa activity só: as views são abertas à mão em [view].
                    .useViewTrackingStrategy(ManualViews)
                    .trackUserInteractions()
                    .enableComposeActionTracking()
                    .trackLongTasks()
                    .trackBackgroundEvents(true)
                    .setSessionListener(object : RumSessionListener {
                        override fun onSessionStarted(sessionId: String, isDiscarded: Boolean) {
                            _sessionId.value = sessionId
                        }
                    })
                    .build(),
            )
            Logs.enable(LogsConfiguration.Builder().useCustomEndpoint("$base/logs").build())
            SessionReplay.enable(
                SessionReplayConfiguration.Builder(100f)
                    .useCustomEndpoint("$base/replay")
                    .addExtensionSupport(ComposeExtensionSupport())
                    .setTextAndInputPrivacy(TextAndInputPrivacy.MASK_SENSITIVE_INPUTS)
                    .setImagePrivacy(ImagePrivacy.MASK_NONE)
                    .setTouchPrivacy(TouchPrivacy.SHOW)
                    .build(),
            )
            logger = Logger.Builder()
                .setName(SERVICE)
                .setService(SERVICE)
                .setBundleWithRumEnabled(true)
                .setLogcatLogsEnabled(BuildConfig.DEBUG)
                .build()
            started = true
            GlobalRumMonitor.get().getCurrentSessionId { id -> if (id != null) _sessionId.value = id }
            Log.i(TAG, "OpenObserve iniciado ($base)")
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao iniciar o OpenObserve", e)
        }
    }

    /** Identifica o usuário nas sessões (como o `setUser` do web). */
    fun setUser(me: Me) {
        if (!started) return
        OpenObserve.setUserInfo(me.uid, me.displayName, null, mapOf("username" to me.username))
    }

    /** Abre uma view do RUM (uma por tela do app). */
    fun view(key: String, name: String) {
        if (started) GlobalRumMonitor.get().startView(key, name, emptyMap())
    }

    fun error(message: String, throwable: Throwable? = null, attributes: Map<String, Any?> = emptyMap()) {
        if (!started) return
        GlobalRumMonitor.get().addError(message, RumErrorSource.SOURCE, throwable, attributes)
        logger?.e(message, throwable, attributes)
    }

    fun info(message: String, attributes: Map<String, Any?> = emptyMap()) {
        logger?.i(message, null, attributes)
    }
}
