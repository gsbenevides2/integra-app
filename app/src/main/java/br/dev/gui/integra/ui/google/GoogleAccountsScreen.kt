package br.dev.gui.integra.ui.google

import br.dev.gui.integra.ui.theme.semibold
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.Card
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path

/** `server/modules/google`. */
interface GoogleAccountsApi {
    @GET("api/google/accounts")
    suspend fun accounts(): GoogleAccountsResponse

    @DELETE("api/google/accounts/{email}")
    suspend fun delete(@Path("email") email: String)
}

@Immutable
@Serializable
data class GoogleAccountsResponse(val accounts: List<String> = emptyList())

/**
 * Abre o início do OAuth do Google numa Custom Tab. O fluxo termina no web (redireciona para o
 * dashboard com `?googleAccountAdded`); ao voltar para o app a lista é recarregada.
 */
private fun openOAuth(context: Context) {
    val url = Uri.parse(Integra.url("/api/google/oauth/start"))
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(Surface.sheet.toArgb()).build()
    val intent = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(colors)
        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
        .build()
    try {
        intent.launchUrl(context, url)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
fun GoogleAccountsScreen() {
    val toast = LocalToast.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // Contas antes de abrir o OAuth, para avisar quando uma nova conta aparecer ao voltar.
    var accountsBeforeOAuth by remember { mutableStateOf<Set<String>?>(null) }

    suspend fun fetchAccounts() {
        apiCall { Integra.api<GoogleAccountsApi>().accounts() }.onSuccess { data ->
            accounts = data.accounts
            val before = accountsBeforeOAuth
            if (before != null && data.accounts.any { it !in before }) {
                toast.success("Conta Google adicionada")
                accountsBeforeOAuth = null
            }
        }
        isLoading = false
    }

    // Carrega ao entrar e sempre que a tela volta ao primeiro plano (ex.: depois do OAuth).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { fetchAccounts() } }

    // O web usa `p-4 gap-3` e título `text-lg font-semibold` só nesta tela.
    ScreenColumn(
        "Contas Google",
        titleStyle = Txt.lg.semibold(),
        padding = 16.dp,
        gap = 12.dp,
        actions = {
            IntegraButton("Adicionar conta", {
                accountsBeforeOAuth = accounts.toSet()
                openOAuth(context)
            }, icon = Hero.Plus)
        },
    ) {
        when {
            isLoading -> Text("Carregando...", style = Txt.sm, color = Tw.mist300)
            accounts.isEmpty() -> Text("Nenhuma conta linkada.", style = Txt.sm, color = Tw.mist300)
            else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (email in accounts) {
                    androidx.compose.runtime.key(email) {
                        GoogleAccountCard(email, onDeleted = { scope.launch { fetchAccounts() } })
                    }
                }
            }
        }
    }
}

/** `component/Card`: e-mail da conta e botão de remover. */
@Composable
private fun GoogleAccountCard(email: String, onDeleted: () -> Unit) {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()

    fun remove() {
        scope.launch {
            val ok = confirm.confirm(title = "Remover conta", message = "Remover a conta Google \"$email\"?")
            if (!ok) return@launch
            apiCall { Integra.api<GoogleAccountsApi>().delete(email) }
                .onSuccess {
                    toast.success("Conta removida")
                    onDeleted()
                }
                .onFailure { toast.error("Falha ao remover a conta") }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(email, style = Txt.sm, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconBtn(Hero.Trash, "Remover", ::remove, tint = Tw.mist300, size = 16.dp)
        }
    }
}
