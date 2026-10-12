package br.dev.gui.integra

import android.content.Context
import android.util.Base64

class Prefs(context: Context) {
    // Device-protected storage para que o serviço funcione também logo após o boot (antes do desbloqueio).
    private val sp = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("integra", Context.MODE_PRIVATE)

    var username: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER, v).apply()

    var password: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(v) = sp.edit().putString(KEY_PASS, v).apply()

    var knockEnabled: Boolean
        get() = sp.getBoolean(KEY_KNOCK, true)
        set(v) = sp.edit().putBoolean(KEY_KNOCK, v).apply()

    /** Liga a tela quando algo (a mão) se aproxima do sensor de proximidade. */
    var proximityEnabled: Boolean
        get() = sp.getBoolean(KEY_PROXIMITY, true)
        set(v) = sp.edit().putBoolean(KEY_PROXIMITY, v).apply()

    /** Liga a tela ao ouvir "Ei Integra" (Vosk offline, só com a tela desligada). */
    var voiceWakeEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE, true)
        set(v) = sp.edit().putBoolean(KEY_VOICE, v).apply()

    val anyWakeEnabled: Boolean get() = knockEnabled || proximityEnabled || voiceWakeEnabled

    /** Limiar (m/s²) da aceleração linear que conta como uma "batida". */
    var knockThreshold: Float
        get() = sp.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(v) = sp.edit().putFloat(KEY_THRESHOLD, v).apply()

    var showWhenLocked: Boolean
        get() = sp.getBoolean(KEY_SHOW_LOCKED, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_LOCKED, v).apply()

    /** Minutos sem toque até o app bloquear a tela (0 = nunca). */
    var idleLockMinutes: Int
        get() = sp.getInt(KEY_IDLE_LOCK, 2)
        set(v) = sp.edit().putInt(KEY_IDLE_LOCK, v).apply()

    val hasCredentials: Boolean get() = username.isNotEmpty()

    /** O usuário tem a rota do chat do assistente (cache do `/api/me`). */
    var assistantAllowed: Boolean
        get() = sp.getBoolean(KEY_ASSISTANT_ALLOWED, false)
        set(v) = sp.edit().putBoolean(KEY_ASSISTANT_ALLOWED, v).apply()

    /** Última resposta do `GET /client-config` (config do RUM), em JSON. */
    var clientConfigJson: String?
        get() = sp.getString(KEY_CLIENT_CONFIG, null)
        set(v) = sp.edit().putString(KEY_CLIENT_CONFIG, v).apply()

    fun basicAuthHeader(): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /**
     * Identificador do app anexado ao User-Agent (`IntegraApp/<token>`), usado numa regra da
     * Cloudflare para liberar o app do desafio.
     * Formato: `<usuário>.<32 primeiros hex do SHA-256 de "usuário:senha">`.
     */
    fun clientToken(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$username:$password".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$username.${hex.take(32)}"
    }

    /** Headers que acompanham toda requisição ao integra. */
    fun authHeaders(): Map<String, String> = mapOf("Authorization" to basicAuthHeader())

    fun userAgent(defaultUserAgent: String) = "$defaultUserAgent $USER_AGENT_PRODUCT/${clientToken()}"

    fun clearCredentials() {
        sp.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    companion object {
        const val HOME_URL = BuildConfig.HOME_URL
        val HOST: String = android.net.Uri.parse(HOME_URL).host ?: "integra.infra.gui.dev.br"
        const val USER_AGENT_PRODUCT = "IntegraApp"
        const val DEFAULT_THRESHOLD = 2.5f

        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_KNOCK = "knock_enabled"
        private const val KEY_IDLE_LOCK = "idle_lock_minutes"
        private const val KEY_PROXIMITY = "proximity_enabled"
        private const val KEY_VOICE = "voice_wake_enabled"
        private const val KEY_CLIENT_CONFIG = "client_config"
        private const val KEY_ASSISTANT_ALLOWED = "assistant_allowed"
        private const val KEY_THRESHOLD = "knock_threshold"
        private const val KEY_SHOW_LOCKED = "show_when_locked"
    }
}
