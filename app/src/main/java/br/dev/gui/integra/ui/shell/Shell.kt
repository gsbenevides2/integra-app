package br.dev.gui.integra.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.BuildConfig
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.LocalMe
import br.dev.gui.integra.data.Me
import br.dev.gui.integra.data.MeApi
import br.dev.gui.integra.data.Telemetry
import br.dev.gui.integra.ui.assistant.AssistantOverlay
import br.dev.gui.integra.voice.Assistant
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.data.initialsOf
import br.dev.gui.integra.data.userMessage
import br.dev.gui.integra.ui.components.ConfirmDialogView
import br.dev.gui.integra.ui.components.ConfirmState
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalOverlayHost
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.OverlayHost
import br.dev.gui.integra.ui.components.OverlayHostView
import br.dev.gui.integra.ui.components.SheetSide
import br.dev.gui.integra.ui.components.SheetSize
import br.dev.gui.integra.ui.components.SideSheet
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import br.dev.gui.integra.ui.components.ToastHostView
import br.dev.gui.integra.ui.components.ToastState
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Ações do app disponíveis para qualquer tela (abrir o menu, bloquear a tela, trocar de página). */
class ShellActions(
    val openDrawer: () -> Unit,
    val lock: () -> Unit,
    val kill: () -> Unit,
    val navigate: (Page) -> Unit,
)

val LocalShell = staticCompositionLocalOf<ShellActions> { error("ShellActions ausente") }

/**
 * Cabeçalho de cada tela, como nos dashboards do web: hambúrguer + título `text-xl`, ações à direita
 * e o botão de cadeado (bloquear a tela), que no web era injetado pelo app.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: androidx.compose.ui.text.TextStyle = Txt.xl,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val shell = LocalShell.current
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBtn(Hero.Bars3, "Abrir menu", shell.openDrawer)
        Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
        IconBtn(Hero.LockClosed, "Bloquear tela", shell.lock)
        IconBtn(Hero.Power, "Fechar app", shell.kill)
    }
}

/**
 * Layout padrão de uma tela que rola inteira: `flex flex-col gap-4 p-3` com o [ScreenHeader] no topo.
 * Telas com lista própria (LazyColumn) ou layout fixo usam o [ScreenHeader] direto.
 */
@Composable
fun ScreenColumn(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    titleStyle: androidx.compose.ui.text.TextStyle = Txt.xl,
    padding: androidx.compose.ui.unit.Dp = 12.dp,
    gap: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        ScreenHeader(title, titleStyle = titleStyle, actions = actions)
        content()
    }
}

/**
 * Raiz do app: carrega o usuário (`/api/me`, como o `MeContext.tsx`), monta o menu lateral com as
 * telas permitidas e os hosts de modais, confirmações e toasts.
 */
@Composable
fun IntegraApp(
    lock: () -> Unit,
    kill: () -> Unit,
    openSettings: () -> Unit,
    homeRequests: StateFlow<Int>,
    overlay: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toast = remember { ToastState(scope) }
    val confirm = remember { ConfirmState() }
    val overlays = remember { OverlayHost() }

    var me by remember { mutableStateOf<Me?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        apiCall { Integra.api<MeApi>().me() }
            .onSuccess { me = it; Telemetry.setUser(it); Assistant.updateMe(it) }
            .onFailure { if (me == null) error = it.userMessage() }
    }

    // Avisos do assistente (que vive fora das telas) viram toasts.
    LaunchedEffect(Unit) { Assistant.toasts.collect { (message, type) -> toast.show(message, type) } }

    CompositionLocalProvider(LocalToast provides toast, LocalConfirm provides confirm, LocalOverlayHost provides overlays) {
        Box(Modifier.fillMaxSize().background(Surface.page).safeDrawingPadding()) {
            val current = me
            when {
                current != null -> CompositionLocalProvider(LocalMe provides current) {
                    Shell(current, lock, kill, openSettings, homeRequests, overlay)
                }
                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Não foi possível identificar o usuário", style = Txt.base)
                    Text(error!!, style = Txt.sm, color = Tw.mist400, textAlign = TextAlign.Center)
                    IntegraButton("Tentar novamente", { attempt++ })
                    IntegraButton("Configurações", openSettings, variant = br.dev.gui.integra.ui.components.ButtonVariant.Secondary)
                }
                else -> Text("Carregando...", style = Txt.base, color = Tw.mist400, modifier = Modifier.align(Alignment.Center))
            }
            OverlayHostView(overlays)
            ConfirmDialogView(confirm)
            ToastHostView(toast)
        }
    }
}

@Composable
private fun Shell(
    me: Me,
    lock: () -> Unit,
    kill: () -> Unit,
    openSettings: () -> Unit,
    homeRequests: StateFlow<Int>,
    overlay: @Composable () -> Unit,
) {
    val pages = remember(me) { Page.entries.filter { it.routeKey == null || me.hasRoute(it.routeKey) } }
    var pageId by rememberSaveable { mutableStateOf(pages.firstOrNull()?.name) }
    val page = pages.firstOrNull { it.name == pageId } ?: pages.firstOrNull()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }

    // "Home" pressionado com o app já na frente: volta para a primeira tela.
    val home by homeRequests.collectAsState()
    LaunchedEffect(home) { if (home > 0) { pageId = pages.firstOrNull()?.name; drawerOpen = false } }
    LaunchedEffect(page) { page?.let { Telemetry.view(it.name, it.label) } }

    val actions = remember(pages) {
        ShellActions(
            openDrawer = { drawerOpen = true },
            lock = lock,
            kill = kill,
            navigate = { target -> pageId = target.name; drawerOpen = false },
        )
    }
    CompositionLocalProvider(LocalShell provides actions) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Surface.page)
                .edgeSwipe(open = drawerOpen, onOpen = { drawerOpen = true }, onClose = { drawerOpen = false }),
        ) {
            if (page == null) {
                Text(
                    "Seu usuário ainda não tem acesso a nenhuma tela. Peça acesso a um administrador.",
                    style = Txt.base, color = Tw.mist300, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                androidx.compose.runtime.key(page) { page.content() }
                if (page != Page.Assistant) AssistantOverlay()
                overlay()
            }
        }
        // O menu do web é um Drawer à esquerda, título "Menu", tamanho small, sem padding.
        SideSheet(
            open = drawerOpen,
            onClose = { drawerOpen = false },
            title = "Menu",
            side = SheetSide.Left,
            size = SheetSize.Small,
            removePadding = true,
            scrollable = false,
        ) {
            DrawerContent(me, pages, page, actions, openSettings)
        }
    }
}

/**
 * Gestos do `Drawer.tsx`: arrastar da borda esquerda (entre 25 e 120 px dela) para a direita abre;
 * com o menu aberto, arrastar para a esquerda fecha. Só observa os toques, sem consumi-los.
 */
private fun Modifier.edgeSwipe(open: Boolean, onOpen: () -> Unit, onClose: () -> Unit) = pointerInput(open) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        val fromEdge = start.x in 25.dp.toPx()..120.dp.toPx()
        var last = start
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            last = change.position
        }
        val dx = last.x - start.x
        val dy = last.y - start.y
        if (kotlin.math.abs(dx) < 60.dp.toPx() || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return@awaitEachGesture
        if (!open && fromEdge && dx > 0) onOpen() else if (open && dx < 0) onClose()
    }
}

/** `GlobalDrawerContext/Content.tsx`. */
@Composable
private fun DrawerContent(me: Me, pages: List<Page>, current: Page?, actions: ShellActions, openSettings: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val sessionId by Telemetry.sessionId.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Tw.blue900), contentAlignment = Alignment.Center) {
                Text(initialsOf(me.displayName), style = Txt.lg)
            }
            Column(Modifier.weight(1f)) {
                Text(me.displayName, style = Txt.lg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(me.username, style = Txt.sm, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (p in pages) DrawerItem(p.label, p.icon, p == current) { actions.navigate(p) }
            DrawerItem("Configurações do app", Hero.Cog6Tooth, false, openSettings)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(Tw.gray800, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("v${BuildConfig.VERSION_NAME}", style = Txt.xs, color = Tw.gray500)
            Text(
                "RUM Session Id: $sessionId",
                style = Txt.xs, color = Tw.gray500, textAlign = TextAlign.Center,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("RUM Session Id", sessionId))
                    toast.success("Copiado para área de transferencia")
                }.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Tw.blue900 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val color = if (selected) Color.White else Tw.gray300
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Text(label, style = Txt.sm, color = color)
    }
}
