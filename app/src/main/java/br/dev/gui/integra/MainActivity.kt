package br.dev.gui.integra

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.dev.gui.integra.data.AuthState
import br.dev.gui.integra.ui.shell.IntegraApp
import br.dev.gui.integra.ui.theme.IntegraTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tela principal, toda nativa (Compose), consumindo as APIs do integra. Também é registrada como
 * HOME (launcher), então pode ser a tela inicial do celular.
 *
 * Para abrir as configurações: toque na tela com 3 dedos ao mesmo tempo.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    /** Incrementado quando "home" é pressionado com o app já na frente (volta para a 1ª tela). */
    private val homeRequests = MutableStateFlow(0)
    /** Muda quando as credenciais mudam, para recarregar tudo com o novo usuário. */
    private var credentials by mutableStateOf("")
    private var askedMic = false

    // Bloqueio por inatividade: a tela fica acesa com o app aberto (como um painel), então sem
    // toques por N minutos o app bloqueia.
    private val idleLock = Runnable { LockService.lockScreen(this, askToEnable = false) }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleLock)
        val minutes = prefs.idleLockMinutes
        if (minutes > 0) handler.postDelayed(idleLock, minutes * 60_000L)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Com a permissão, o serviço passa a ouvir o "Ei Integra".
        if (granted) KnockService.startIfEnabled(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyLockScreenFlags()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        credentials = prefs.basicAuthHeader()

        setContent {
            IntegraTheme {
                key(credentials) {
                    IntegraApp(
                        lock = { LockService.lockScreen(this) },
                        openSettings = ::openSettings,
                        homeRequests = homeRequests,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyLockScreenFlags()
        KnockService.cancelWakeNotification(this)
        KnockService.startIfEnabled(this)
        resetIdleTimer()
        AuthState.listener = {
            runOnUiThread {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_AUTH_FAILED, true))
            }
        }

        if (!prefs.hasCredentials) {
            openSettings()
            return
        }
        prefs.basicAuthHeader().let { if (it != credentials) credentials = it }
        if (prefs.voiceWakeEnabled && !askedMic && !KnockService.hasMicPermission(this)) {
            askedMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(idleLock)
        AuthState.listener = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressionar "home" estando já na home: volta para a tela inicial.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME) && hasWindowFocus()) {
            homeRequests.value++
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) resetIdleTimer()
        // Gesto escondido: 3 dedos na tela abre as configurações.
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount >= 3) {
            openSettings()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun applyLockScreenFlags() {
        val show = prefs.showWhenLocked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    companion object {
        const val EXTRA_FROM_KNOCK = "from_knock"
        const val EXTRA_FROM_VOICE = "from_voice"
    }
}
