package br.dev.gui.integra.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.LocalMe
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.data.initialsOf
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.EmptyState
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.LoadingText
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.shell.ScreenHeader
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class Tab { Resources, Routes }

private val METHOD_COLORS: Map<String, Pair<Color, Color>> = mapOf(
    "GET" to (Tw.blue900 to Tw.blue200),
    "POST" to (Tw.green900 to Tw.green200),
    "PUT" to (Tw.yellow900 to Tw.yellow200),
    "PATCH" to (Tw.yellow900 to Tw.yellow200),
    "DELETE" to (Tw.red900 to Tw.red200),
)

private val SectionRadius = 8.dp

/** Permissões (`public/dashboards/permissions`). */
@Composable
fun PermissionsScreen() {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val me = LocalMe.current
    val scope = rememberCoroutineScope()

    var users by remember { mutableStateOf<List<UserRow>>(emptyList()) }
    var selectedUserId by rememberSaveable { mutableStateOf("") }
    var grants by remember { mutableStateOf(Grants()) }
    var routes by remember { mutableStateOf<List<RouteOption>>(emptyList()) }
    var resources by remember { mutableStateOf<List<ResourceOption>>(emptyList()) }
    var frigateAvailable by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var grantsFailed by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.Resources) }
    var search by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadAttempt by remember { mutableIntStateOf(0) }

    val selectedUser = users.firstOrNull { it.id == selectedUserId }

    suspend fun fetchGrants(userId: String) {
        if (userId.isEmpty()) {
            grants = Grants()
            return
        }
        apiCall { Integra.api<PermissionsApi>().grants(userId) }
            .onSuccess { grantsFailed = false; grants = it }
            .onFailure {
                // Nunca mostrar uma lista velha ou vazia como se fosse a deste usuário.
                grantsFailed = true
                toast.error(it.errorText("Falha ao carregar permissões do usuário"))
            }
    }

    LaunchedEffect(loadAttempt) {
        isLoading = true
        loadFailed = false
        val api = Integra.api<PermissionsApi>()
        val (usersRes, routesRes, resourcesRes) = coroutineScope {
            listOf(
                async { apiCall { api.users() } },
                async { apiCall { api.routes() } },
                async { apiCall { api.resources() } },
            ).awaitAll()
        }
        if (usersRes.isFailure || routesRes.isFailure || resourcesRes.isFailure) {
            loadFailed = true
        } else {
            @Suppress("UNCHECKED_CAST")
            users = usersRes.getOrNull() as List<UserRow>
            @Suppress("UNCHECKED_CAST")
            routes = routesRes.getOrNull() as List<RouteOption>
            val payload = resourcesRes.getOrNull() as ResourcesPayload
            resources = payload.resources.orEmpty()
            frigateAvailable = payload.frigateAvailable ?: true
            if (users.none { it.id == selectedUserId }) selectedUserId = users.firstOrNull()?.id.orEmpty()
        }
        isLoading = false
    }

    LaunchedEffect(selectedUserId) { fetchGrants(selectedUserId) }

    /** Uma mutação por chave; depois recarrega as permissões do usuário selecionado. */
    fun mutate(keys: List<String>, failMessage: String, calls: List<suspend () -> Unit>) {
        val userId = selectedUserId
        scope.launch {
            pending = pending + keys
            try {
                val results = coroutineScope { calls.map { call -> async { apiCall { call() } } }.awaitAll() }
                results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { toast.error(it.errorText(failMessage)) }
            } finally {
                fetchGrants(userId)
                pending = pending - keys.toSet()
            }
        }
    }

    val api = remember { Integra.api<PermissionsApi>() }

    fun addRouteGrants(routeKeys: List<String>) {
        val userId = selectedUserId
        mutate(
            routeKeys.map { "route:$it" },
            "Falha ao conceder rota",
            routeKeys.map { key -> suspend { api.grantRoute(userId, RouteGrantBody(key)) } },
        )
    }

    fun removeRouteGrants(toRemove: List<RouteGrant>) {
        scope.launch {
            val removesOwnManagement = toRemove.any { it.routeKey == MANAGER_ROUTE_KEY && selectedUser?.uid == me.uid }
            if (removesOwnManagement) {
                val ok = confirm.confirm(
                    "Confirmar ação",
                    "Remover sua própria permissão de gerenciamento? Você pode ficar sem acesso para restaurá-la.",
                )
                if (!ok) return@launch
            }
            mutate(
                toRemove.map { "route:${it.routeKey}" },
                "Falha ao remover permissão",
                toRemove.map { g -> suspend { api.revokeRoute(g.id) } },
            )
        }
    }

    fun addResourceGrants(items: List<ResourceGrantBody>) {
        val userId = selectedUserId
        mutate(
            items.map { "${it.resourceType}:${it.resourceId}:${it.action}" },
            "Falha ao conceder recurso",
            items.map { body -> suspend { api.grantResource(userId, body) } },
        )
    }

    fun removeResourceGrants(toRemove: List<ResourceGrant>) = mutate(
        toRemove.map { "${it.resourceType}:${it.resourceId}:${it.action}" },
        "Falha ao remover permissão",
        toRemove.map { g -> suspend { api.revokeResource(g.id) } },
    )

    val narrow = LocalConfiguration.current.screenWidthDp < 600
    val entries = remember(tab, resources, routes, grants, frigateAvailable, search) {
        if (tab == Tab.Resources) resourceEntries(resources, grants, frigateAvailable, search)
        else routeEntries(routes, grants, search)
    }
    val resourceCount = remember(grants) { grants.resources.distinctBy { it.resourceType to it.resourceId }.size }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item(key = "header") { ScreenHeader("Permissões", Modifier.padding(bottom = 16.dp)) }

        when {
            isLoading -> item(key = "loading") { LoadingText("Carregando permissões...") }
            loadFailed -> item(key = "failed") {
                RetryBlock("Não foi possível carregar as permissões") { loadAttempt++ }
            }
            users.isEmpty() -> item(key = "empty") { EmptyState(Hero.UserGroup, "Nenhum usuário registrado") }
            else -> {
                item(key = "users") {
                    UserChips(users, selectedUserId) { selectedUserId = it }
                }
                if (selectedUser != null) {
                    item(key = "uid") {
                        val isManager = grants.routes.any { it.routeKey == MANAGER_ROUTE_KEY }
                        UserInfo(selectedUser, isManager)
                    }
                }
                item(key = "tabs") {
                    TabsAndSearch(tab, { tab = it }, resourceCount, grants.routes.size, search) { search = it }
                }

                if (grantsFailed) {
                    item(key = "grantsFailed") {
                        RetryBlock("Não foi possível carregar as permissões deste usuário") {
                            scope.launch { fetchGrants(selectedUserId) }
                        }
                    }
                } else {
                    items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                        when (entry) {
                            is NoticeEntry -> Text(entry.text, style = Txt.xs, color = Tw.yellow300, modifier = Modifier.padding(bottom = 12.dp))
                            is ResourceHeaderEntry -> SectionHeader(
                                entry.title, entry.withAccess, entry.total, entry.first, narrow,
                                grantLabel = "Liberar leitura de todos", revokeLabel = "Remover todos",
                                canGrant = entry.missingRead.isNotEmpty(), canRevoke = entry.visibleGrants.isNotEmpty(),
                                onGrant = {
                                    addResourceGrants(entry.missingRead.map { ResourceGrantBody(entry.type, it.resourceId, "read") })
                                },
                                onRevoke = { removeResourceGrants(entry.visibleGrants) },
                            )
                            is ResourceRowEntry -> ResourceRowItem(
                                entry, pending, narrow,
                                onGrant = { action -> addResourceGrants(listOf(ResourceGrantBody(entry.row.resourceType, entry.row.resourceId, action))) },
                                onRevoke = { grant -> removeResourceGrants(listOf(grant)) },
                            )
                            is RouteHeaderEntry -> SectionHeader(
                                entry.tag, entry.granted, entry.total, entry.first, narrow,
                                grantLabel = "Liberar todas", revokeLabel = "Remover todas",
                                canGrant = entry.toGrant.isNotEmpty(), canRevoke = entry.toRevoke.isNotEmpty(),
                                onGrant = { addRouteGrants(entry.toGrant) },
                                onRevoke = { removeRouteGrants(entry.toRevoke) },
                            )
                            is RouteRowEntry -> RouteRowItem(
                                entry,
                                busy = "route:${entry.route.key}" in pending,
                                onToggle = { entry.grant?.let { removeRouteGrants(listOf(it)) } ?: addRouteGrants(listOf(entry.route.key)) },
                            )
                            is OrphanHeaderEntry -> SectionHeader("Rotas órfãs", null, null, entry.first, narrow)
                            is OrphanRowEntry -> OrphanRowItem(entry) { removeRouteGrants(listOf(entry.grant)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RetryBlock(text: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = Txt.base, color = Tw.mist200)
        IntegraButton("Tentar novamente", onRetry)
    }
}

@Composable
private fun UserChips(users: List<UserRow>, selectedId: String, onSelect: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).semantics { contentDescription = "Usuários" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(users, key = { it.id }) { user ->
            val selected = user.id == selectedId
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) Tw.blue950 else Color.Transparent)
                    .border(1.dp, if (selected) Tw.blue500 else Tw.gray700, CircleShape)
                    .clickable { onSelect(user.id) }
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Tw.blue900), contentAlignment = Alignment.Center) {
                    Text(initialsOf(user.username), style = Txt.xs)
                }
                Text(user.username, style = Txt.sm, color = if (selected) Color.White else Tw.mist300)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserInfo(user: UserRow, isManager: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "uid: ${user.uid}", style = Txt.xs, color = Tw.mist400, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (isManager) {
            Text(
                "Gerenciador de permissões",
                style = Txt.xs,
                color = Tw.green300,
                modifier = Modifier.align(Alignment.CenterVertically).clip(CircleShape).background(Tw.green950)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TabsAndSearch(
    tab: Tab,
    onTab: (Tab) -> Unit,
    resourceCount: Int,
    routeCount: Int,
    search: String,
    onSearch: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Surface.card).padding(4.dp)) {
            for ((id, label) in listOf(Tab.Resources to "Recursos ($resourceCount)", Tab.Routes to "Rotas ($routeCount)")) {
                val active = tab == id
                Text(
                    label,
                    style = Txt.sm,
                    color = if (active) Color.White else Tw.mist300,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) Tw.gray950 else Color.Transparent)
                        .clickable { onTab(id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Tw.gray900)
                .border(1.dp, Tw.mist600, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Hero.MagnifyingGlass, null, Modifier.size(16.dp), tint = Tw.mist400)
            BasicTextField(
                value = search,
                onValueChange = onSearch,
                singleLine = true,
                textStyle = Txt.sm.copy(color = Surface.text),
                cursorBrush = SolidColor(Tw.mist300),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { contentDescription = "Buscar" },
                decorationBox = { inner ->
                    Box {
                        if (search.isEmpty()) Text("Buscar", style = Txt.sm, color = Tw.gray400)
                        inner()
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Seções em cartão (`rounded-lg bg-gray-800 p-3`) partidas em itens da LazyColumn.
// ---------------------------------------------------------------------------------------------

private val TopShape = RoundedCornerShape(topStart = SectionRadius, topEnd = SectionRadius)
private val BottomShape = RoundedCornerShape(bottomStart = SectionRadius, bottomEnd = SectionRadius)
private val RectShape = RoundedCornerShape(0.dp)

/**
 * Fundo de uma linha da seção; a última fecha o cartão com os cantos de baixo.
 * `p-3` do `<section>`: o header já aplica `top=12`, as linhas `bottom=12` só na última.
 */
private fun Modifier.sectionBody(last: Boolean, firstRow: Boolean): Modifier {
    val shape: Shape = if (last) BottomShape else RectShape
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(Surface.card)
        .padding(start = 12.dp, end = 12.dp, bottom = if (last) 12.dp else 0.dp)
        // divide-y divide-gray-700
        .then(
            if (firstRow) Modifier
            else Modifier.drawBehind { drawLine(Surface.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        )
        .padding(vertical = 8.dp)
}

/**
 * Cabeçalho da seção: o `div.mb-2` do web (margem inferior 8dp) dentro do `p-3` do `<section>`.
 * O `mb-2` vira `bottom = 8` no próprio padding do header, e as linhas começam logo abaixo.
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int?,
    total: Int?,
    first: Boolean,
    narrow: Boolean,
    grantLabel: String? = null,
    revokeLabel: String? = null,
    canGrant: Boolean = false,
    canRevoke: Boolean = false,
    onGrant: () -> Unit = {},
    onRevoke: () -> Unit = {},
) {
    val titleText = buildAnnotatedString {
        append(title)
        if (count != null && total != null) {
            append(" ")
            withStyle(SpanStyle(color = Tw.mist400)) { append("($count/$total)") }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 12.dp)) {
        Column(
            // p-3 do <section> + mb-2 do header (8dp) — o título/botões ficam com o mesmo
            // espaçamento do web antes da primeira linha da lista.
            Modifier.fillMaxWidth().clip(TopShape).background(Surface.card).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val buttons = @Composable {
                if (grantLabel != null) IntegraButton(grantLabel, onGrant, variant = ButtonVariant.Secondary, enabled = canGrant)
                if (revokeLabel != null) IntegraButton(revokeLabel, onRevoke, variant = ButtonVariant.Secondary, enabled = canRevoke)
            }
            if (narrow && grantLabel != null) {
                Text(titleText, style = Txt.sm, color = Tw.mist200)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(titleText, style = Txt.sm, color = Tw.mist200, modifier = Modifier.weight(1f))
                    buttons()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResourceRowItem(
    entry: ResourceRowEntry,
    pending: Set<String>,
    narrow: Boolean,
    onGrant: (String) -> Unit,
    onRevoke: (ResourceGrant) -> Unit,
) {
    val row = entry.row
    // Qualquer permissão diferente de "read" já implica leitura.
    val impliedRead = entry.rowGrants.isNotEmpty() && entry.rowGrants.none { it.action == "read" }

    val label = @Composable {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.label, style = Txt.sm, color = Tw.mist100, modifier = Modifier.align(Alignment.CenterVertically))
            row.kind?.let { kind ->
                Text(
                    KIND_LABELS[kind] ?: kind,
                    style = Txt.xs,
                    color = Tw.mist300,
                    modifier = Modifier.align(Alignment.CenterVertically).clip(CircleShape).background(Tw.gray700)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            row.flag?.let { Text("($it)", style = Txt.xs, color = Tw.yellow300, modifier = Modifier.align(Alignment.CenterVertically)) }
        }
    }
    val chips = @Composable {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (action in row.actions) {
                val grant = entry.rowGrants.firstOrNull { it.action == action }
                val implied = action == "read" && grant == null && impliedRead
                val busy = "${row.resourceType}:${row.resourceId}:$action" in pending
                val canGrant = row.flag == null
                ActionChip(
                    label = ACTION_LABELS[action] ?: action,
                    description = "${ACTION_LABELS[action] ?: action} ${row.label}",
                    granted = grant != null,
                    implied = implied,
                    busy = busy,
                    enabled = !(busy || implied || (grant == null && !canGrant)),
                    onClick = { if (grant != null) onRevoke(grant) else onGrant(action) },
                )
            }
        }
    }

    if (narrow) {
        Column(Modifier.sectionBody(entry.last, entry.firstRow), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label()
            chips()
        }
    } else {
        Row(Modifier.sectionBody(entry.last, entry.firstRow), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(end = 8.dp)) { label() }
            chips()
        }
    }
}

/** Chip de ação: concedida (azul), implícita (borda tracejada, "Incluído por outra permissão") ou livre. */
@Composable
private fun ActionChip(
    label: String,
    description: String,
    granted: Boolean,
    implied: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val dashedColor = Tw.blue500
    val modifier = Modifier
        .alpha(if (busy) 0.5f else 1f)
        .clip(CircleShape)
        .then(
            when {
                granted -> Modifier.background(Tw.blue900).border(1.dp, Tw.blue500, CircleShape)
                implied -> Modifier.drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        dashedColor,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
                else -> Modifier.border(1.dp, Tw.gray600, CircleShape)
            },
        )
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = if (implied) "$description (Incluído por outra permissão)" else description }
        .padding(horizontal = 12.dp, vertical = 4.dp)
    Text(
        label,
        style = Txt.xs,
        color = when {
            granted -> Color.White
            implied -> Tw.blue200
            else -> Tw.mist400
        },
        modifier = modifier,
    )
}

@Composable
private fun RouteRowItem(entry: RouteRowEntry, busy: Boolean, onToggle: () -> Unit) {
    val route = entry.route
    Row(
        Modifier.sectionBody(entry.last, entry.firstRow),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(route.summary, style = Txt.sm, color = Tw.mist100)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (bg, fg) = METHOD_COLORS[route.method] ?: (Tw.gray700 to Surface.text)
                Text(
                    route.method,
                    style = Txt.xs.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp),
                    color = fg,
                    modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(bg).padding(horizontal = 6.dp),
                )
                Text(
                    route.path,
                    style = Txt.xs.copy(fontFamily = FontFamily.Monospace),
                    color = Tw.mist400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IntegraSwitch(checked = entry.grant != null, onChange = { onToggle() }, enabled = !busy)
    }
}

@Composable
private fun OrphanRowItem(entry: OrphanRowEntry, onRemove: () -> Unit) {
    Row(
        Modifier.sectionBody(entry.last, entry.firstRow),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Tw.mist100)) { append(entry.grant.routeKey) }
                STATUS_FLAGS[entry.grant.status]?.let { withStyle(SpanStyle(color = Tw.yellow300)) { append("  ($it)") } }
            },
            style = Txt.xs,
            modifier = Modifier.weight(1f),
        )
        IntegraButton("Remover", onRemove, variant = ButtonVariant.Secondary)
    }
}
