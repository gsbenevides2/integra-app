package br.dev.gui.integra.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Portal: modais, drawers e diálogos são desenhados pela raiz do app, por cima de tudo (inclusive
// do menu lateral), como o `createPortal(document.body)` do web.
// ---------------------------------------------------------------------------------------------

@Stable
class OverlayHost {
    internal class Entry(var content: @Composable () -> Unit)
    internal val entries = mutableStateListOf<Entry>()
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost> { error("OverlayHost ausente") }

/** Desenha [content] na raiz do app enquanto este composable estiver na composição. */
@Composable
fun Portal(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val entry = remember { OverlayHost.Entry(content) }
    entry.content = content
    DisposableEffect(host) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

@Composable
fun OverlayHostView(host: OverlayHost) {
    for (entry in host.entries) androidx.compose.runtime.key(entry) { entry.content() }
}

// ---------------------------------------------------------------------------------------------

/**
 * `Modal.tsx`: fundo `bg-mist-950/90 backdrop-blur-sm`, cartão `max-w-md rounded-lg bg-gray-800 p-4
 * gap-4 shadow-xl` com título `text-base font-semibold`. Toque fora ou "voltar" fecham.
 */
@Composable
fun Modal(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    maxWidth: Dp = 448.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Portal {
        AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            BackHandler(open, onClose)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Tw.mist950.copy(alpha = 0.9f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                    .imePadding()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .animateEnterExit(enter = scaleIn(initialScale = 0.95f), exit = scaleOut(targetScale = 0.95f))
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface.card)
                        .drawBehind {
                            // shadow-xl
                            drawRect(
                                Color.Black.copy(alpha = 0.25f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 8.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(size.width, size.height),
                            )
                        }
                        .clickable(remember { MutableInteractionSource() }, null) {}
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(title, style = Txt.base.semibold())
                    content()
                }
            }
        }
    }
}

enum class SheetSide { Left, Right }
enum class SheetSize(val maxWidth: Dp) { Small(360.dp), Medium(448.dp), Large(576.dp) }

/**
 * `Drawer.tsx`: painel lateral `bg-gray-900` com cabeçalho fixo (título + X), fundo `bg-black/50`.
 * O conteúdo rola e tem `p-4 gap-4` (sem padding com [removePadding]). `enableSwipeClose`
 * (arrastar para fora fecha) é aplicado pelos chamadores.
 */
@Composable
fun SideSheet(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    side: SheetSide = SheetSide.Right,
    size: SheetSize = SheetSize.Medium,
    removePadding: Boolean = false,
    scrollable: Boolean = true,
    enableSwipeClose: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Portal {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                        .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
                )
            }
            AnimatedVisibility(
                open,
                modifier = Modifier.align(if (side == SheetSide.Right) Alignment.CenterEnd else Alignment.CenterStart),
                enter = slideInHorizontally { if (side == SheetSide.Right) it else -it },
                exit = slideOutHorizontally { if (side == SheetSide.Right) it else -it },
            ) {
                BackHandler(open, onClose)
                Column(
                    Modifier
                        .widthIn(max = size.maxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(Surface.sheet)
                        // Arrastar para fora fecha (enableSwipeClose do web).
                        .then(
                            if (enableSwipeClose) Modifier.swipeToClose(onClose, fromRight = side == SheetSide.Right)
                            else Modifier,
                        )
                        .clickable(remember { MutableInteractionSource() }, null) {}
                        .imePadding(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                val s = drawContext.size
                                drawLine(Surface.border, androidx.compose.ui.geometry.Offset(0f, s.height), androidx.compose.ui.geometry.Offset(s.width, s.height), 1.dp.toPx())
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = Txt.base.semibold(), modifier = Modifier.weight(1f))
                        IconBtn(Hero.XMark, "Fechar", onClose)
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .then(if (removePadding) Modifier else Modifier.padding(16.dp)),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * Arrastar para fora do painel fecha (o `enableSwipeClose` do `Drawer.tsx`): 60 dp mínimos,
 * na horizontal, com sinal coerente com o lado de onde o painel veio.
 */
private fun Modifier.swipeToClose(onClose: () -> Unit, fromRight: Boolean): Modifier = pointerInput(onClose) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        var last = start
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            last = change.position
        }
        val dx = last.x - start.x
        val dy = last.y - start.y
        if (kotlin.math.abs(dx) < 60.dp.toPx() || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return@awaitEachGesture
        if (fromRight && dx > 0 || !fromRight && dx < 0) onClose()
    }
}

// ---------------------------------------------------------------------------------------------
// Confirmação (`Confirm.tsx` / `useConfirm`)
// ---------------------------------------------------------------------------------------------

@Stable
class ConfirmState {
    internal class Request(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val cancelLabel: String,
        val result: CompletableDeferred<Boolean>,
    )

    internal var current by mutableStateOf<Request?>(null)

    /** Abre o diálogo e espera a resposta. */
    suspend fun confirm(
        title: String,
        message: String,
        confirmLabel: String = "Confirmar",
        cancelLabel: String = "Cancelar",
    ): Boolean {
        current?.result?.complete(false)
        val request = Request(title, message, confirmLabel, cancelLabel, CompletableDeferred())
        current = request
        return try {
            request.result.await()
        } finally {
            if (current === request) current = null
        }
    }
}

val LocalConfirm = staticCompositionLocalOf<ConfirmState> { error("ConfirmState ausente") }

@Composable
fun ConfirmDialogView(state: ConfirmState) {
    val request = state.current
    var last by remember { mutableStateOf<ConfirmState.Request?>(null) }
    if (request != null) last = request
    val shown = last ?: return
    AnimatedVisibility(request != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        BackHandler(request != null) { shown.result.complete(false) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Tw.mist950.copy(alpha = 0.9f))
                .clickable(remember { MutableInteractionSource() }, null) { shown.result.complete(false) }
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 384.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface.card)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.clip(CircleShape).background(Tw.red950).padding(8.dp)) {
                        Icon(Hero.ExclamationTriangle, null, Modifier.size(20.dp), tint = Tw.red500)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(shown.title, style = Txt.lg.semibold())
                        Text(shown.message, style = Txt.sm, color = Tw.mist300)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    IntegraButton(shown.cancelLabel, { shown.result.complete(false) }, variant = ButtonVariant.Secondary)
                    IntegraButton(shown.confirmLabel, { shown.result.complete(true) }, variant = ButtonVariant.Danger)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Toasts (`Toast.tsx` / `useToast`)
// ---------------------------------------------------------------------------------------------

enum class ToastType { Success, Error }

@Stable
class ToastState(private val scope: CoroutineScope) {
    internal data class Item(val id: Long, val message: String, val type: ToastType)

    internal val items = mutableStateListOf<Item>()
    private var nextId = 0L

    /** Mostra um aviso por 4 s. Pode ser chamado de qualquer thread. */
    fun show(message: String, type: ToastType = ToastType.Success) {
        scope.launch {
            val item = Item(nextId++, message, type)
            items.add(item)
            delay(4_000)
            items.remove(item)
        }
    }

    fun success(message: String) = show(message, ToastType.Success)
    fun error(message: String) = show(message, ToastType.Error)

    internal fun dismiss(id: Long) {
        items.removeAll { it.id == id }
    }
}

val LocalToast = compositionLocalOf<ToastState> { error("ToastState ausente") }

@Composable
fun BoxScope.ToastHostView(state: ToastState) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding(16.dp).widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (item in state.items) {
            androidx.compose.runtime.key(item.id) {
                val accent = if (item.type == ToastType.Success) Tw.green700 else Tw.red700
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surface.card)
                        .drawBehind { drawRect(accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(if (item.type == ToastType.Success) Hero.CheckCircle else Hero.XCircle, null, Modifier.size(20.dp))
                    Text(item.message, style = Txt.sm, modifier = Modifier.weight(1f))
                    IconBtn(Hero.XMark, "Fechar", { state.dismiss(item.id) }, size = 16.dp)
                }
            }
        }
    }
}
