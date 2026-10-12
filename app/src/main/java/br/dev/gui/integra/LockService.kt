package br.dev.gui.integra

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Serviço de acessibilidade usado só para bloquear a tela (mesmo efeito do botão power,
 * mantendo o desbloqueio por digital). Não lê nada da tela.
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: LockService? = null

        /** O serviço de acessibilidade está ativo. */
        fun isEnabled() = instance != null

        /**
         * Bloqueia a tela. Se o serviço não estiver ativo retorna false e, quando [askToEnable],
         * abre as configurações de acessibilidade.
         */
        fun lockScreen(context: Context, askToEnable: Boolean = true): Boolean {
            val service = instance
            if (service == null) {
                if (!askToEnable) return false
                Toast.makeText(
                    context, "Ative \"Integra - bloquear tela\" em Acessibilidade", Toast.LENGTH_LONG
                ).show()
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            // A mão que tocou no botão está perto do sensor: não religa a tela logo em seguida.
            KnockService.suppressWake(3_000)
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
