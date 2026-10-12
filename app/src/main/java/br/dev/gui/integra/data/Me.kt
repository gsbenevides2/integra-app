package br.dev.gui.integra.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/** `GET /api/me` (server/modules/permissions/index.ts). */
@Serializable
data class Me(
    val uid: String,
    val username: String,
    val name: String? = null,
    val routes: List<String> = emptyList(),
    val resources: List<MeResource> = emptyList(),
) {
    /** O usuário tem a permissão de rota, ex.: `"POST /api/assistant/chat"`. */
    fun hasRoute(key: String) = key in routes

    /** Ação sobre um recurso (qualquer ação diferente de "read" também implica leitura). */
    fun can(resourceType: String, resourceId: String, action: String) = resources.any {
        it.resourceType == resourceType && it.resourceId == resourceId && (it.action == action || action == "read")
    }

    val displayName get() = name?.takeIf { it.isNotBlank() } ?: username
}

@Serializable
data class MeResource(val resourceType: String, val resourceId: String, val action: String)

interface MeApi {
    @GET("api/me")
    suspend fun me(): Me
}

/** Iniciais para o avatar (`initialsOf` do `MeContext.tsx`). */
fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

val LocalMe = compositionLocalOf<Me> { error("Me ausente") }

/**
 * Repete [block] a cada [intervalMs] enquanto a tela estiver em primeiro plano (para com a tela
 * desligada ou outro app por cima), como os `setInterval` dos dashboards do web. Roda logo ao entrar.
 * Reinicia quando alguma das [keys] muda.
 */
@Composable
fun PollingEffect(intervalMs: Long, vararg keys: Any?, block: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = rememberUpdatedState(block)
    LaunchedEffect(lifecycle, intervalMs, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                current.value()
                delay(intervalMs)
            }
        }
    }
}
