package br.dev.gui.integra.ui.permissions

import androidx.compose.runtime.Immutable
import br.dev.gui.integra.data.httpStatus
import br.dev.gui.integra.data.userMessage
import retrofit2.HttpException
import java.io.IOException
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** A única rota que restaura todas as outras: quem a tem é "gerenciador" (server/modules/permissions/constants.ts). */
const val MANAGER_ROUTE_KEY = "POST /api/permissions/users/:id/route-grants"

val RESOURCE_TYPE_LABELS: Map<String, String> = linkedMapOf(
    "frigate_camera" to "Câmeras",
    "tuya_device" to "Dispositivos Tuya",
    "tuya_sensor" to "Sensores Tuya",
    "tuya_preset" to "Modos Tuya",
    "cron_job" to "Crons",
)

/** Ordem das seções da aba Recursos; tipos desconhecidos vão depois. */
private val RESOURCE_TYPE_ORDER = RESOURCE_TYPE_LABELS.keys.toList()

val ACTION_LABELS: Map<String, String> = mapOf(
    "read" to "Ler",
    "write" to "Editar",
    "apply" to "Aplicar",
    "execute" to "Executar",
)

/** O que é um dispositivo ou sensor Tuya, mostrado ao lado do nome. */
val KIND_LABELS: Map<String, String> = mapOf(
    "lamp" to "Lâmpada",
    "switch" to "Interruptor",
    "door" to "Porta",
    "motion" to "Presença",
    "temperature_humidity" to "Temperatura e umidade",
    "unknown" to "Desconhecido",
)

val STATUS_FLAGS: Map<String, String> = mapOf(
    "orphaned" to "órfã",
    "unverified" to "não verificada",
)

/** `localeCompare` do navegador (pt-BR). */
private val collator: Collator = Collator.getInstance(Locale("pt", "BR"))

private val combiningMarks = Regex("[\\u0300-\\u036f]")

private fun normalize(text: String) =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

/** Busca sem diferenciar maiúsculas nem acentos (`matchesSearch`). */
fun matchesSearch(search: String, vararg fields: String): Boolean {
    val needle = normalize(search.trim())
    if (needle.isEmpty()) return true
    return fields.any { normalize(it).contains(needle) }
}

/** Texto `{ error }` / `{ message }` do servidor, ou [fallback] (`errorText` do web). Ex.: 409 do último gerenciador. */
fun Throwable.errorText(fallback: String): String = when (this) {
    is HttpException -> userMessage().takeUnless { it == "Erro ${httpStatus}" } ?: fallback
    is IOException -> fallback
    else -> message ?: fallback
}

@Immutable
data class ResourceRow(
    val resourceType: String,
    val resourceId: String,
    val label: String,
    val actions: List<String>,
    val kind: String? = null,
    /** Preenchido quando o recurso só existe como permissão (saiu do catálogo). */
    val flag: String? = null,
)

@Immutable
data class ResourceSection(val type: String, val rows: List<ResourceRow>)

/**
 * Todo recurso do catálogo mais os recursos com permissão que o catálogo não tem mais, agrupados
 * por tipo na ordem de exibição (`buildResourceSections`).
 */
fun buildResourceSections(resources: List<ResourceOption>, grants: List<ResourceGrant>): List<ResourceSection> {
    class Mutable(val base: ResourceRow, val actions: MutableList<String>)

    val byType = LinkedHashMap<String, MutableList<Mutable>>()
    val known = HashSet<String>()
    for (r in resources) {
        known += "${r.resourceType}:${r.resourceId}"
        byType.getOrPut(r.resourceType) { mutableListOf() } +=
            Mutable(ResourceRow(r.resourceType, r.resourceId, r.label, r.actions, r.kind), r.actions.toMutableList())
    }
    for (g in grants) {
        if ("${g.resourceType}:${g.resourceId}" in known) continue
        val rows = byType.getOrPut(g.resourceType) { mutableListOf() }
        // As linhas do catálogo foram puladas acima, então um match aqui é uma linha órfã anterior.
        val orphan = rows.firstOrNull { it.base.resourceId == g.resourceId }
        if (orphan != null) {
            orphan.actions += g.action
            continue
        }
        rows += Mutable(
            ResourceRow(g.resourceType, g.resourceId, g.label ?: g.resourceId, emptyList(), flag = STATUS_FLAGS[g.status]),
            mutableListOf(g.action),
        )
    }
    fun rank(type: String) = RESOURCE_TYPE_ORDER.indexOf(type).let { if (it == -1) RESOURCE_TYPE_ORDER.size else it }
    return byType.entries
        .sortedWith { (a, _), (b, _) -> (rank(a) - rank(b)).takeIf { it != 0 } ?: collator.compare(a, b) }
        .map { (type, rows) ->
            ResourceSection(
                type,
                rows.map { it.base.copy(actions = it.actions.toList()) }.sortedWith { a, b -> collator.compare(a.label, b.label) },
            )
        }
}

// ---------------------------------------------------------------------------------------------
// Linhas da lista (LazyColumn): cada seção vira cabeçalho + linhas, para rolar barato.
// ---------------------------------------------------------------------------------------------

@Immutable
sealed interface Entry {
    val key: String
}

@Immutable
data class NoticeEntry(val text: String) : Entry {
    override val key get() = "notice"
}

@Immutable
data class ResourceHeaderEntry(
    val type: String,
    val title: String,
    val withAccess: Int,
    val total: Int,
    val missingRead: List<ResourceRow>,
    val visibleGrants: List<ResourceGrant>,
    val first: Boolean,
) : Entry {
    override val key get() = "rh:$type"
}

@Immutable
data class ResourceRowEntry(
    val row: ResourceRow,
    /** Permissões deste usuário neste recurso. */
    val rowGrants: List<ResourceGrant>,
    val firstRow: Boolean,
    val last: Boolean,
) : Entry {
    override val key get() = "r:${row.resourceType}:${row.resourceId}"
}

@Immutable
data class RouteHeaderEntry(
    val tag: String,
    val granted: Int,
    val total: Int,
    val toGrant: List<String>,
    val toRevoke: List<RouteGrant>,
    val first: Boolean,
) : Entry {
    override val key get() = "th:$tag"
}

@Immutable
data class RouteRowEntry(val route: RouteOption, val grant: RouteGrant?, val firstRow: Boolean, val last: Boolean) : Entry {
    override val key get() = "t:${route.key}"
}

@Immutable
data class OrphanHeaderEntry(val first: Boolean) : Entry {
    override val key get() = "oh"
}

@Immutable
data class OrphanRowEntry(val grant: RouteGrant, val firstRow: Boolean, val last: Boolean) : Entry {
    override val key get() = "o:${grant.id}"
}

/** Linhas da aba Recursos, já filtradas pela busca. */
fun resourceEntries(
    resources: List<ResourceOption>,
    grants: Grants,
    frigateAvailable: Boolean,
    search: String,
): List<Entry> {
    val out = mutableListOf<Entry>()
    if (!frigateAvailable) out += NoticeEntry("Frigate indisponível: câmeras não verificadas.")
    val grantsByResource = grants.resources.groupBy { "${it.resourceType}:${it.resourceId}" }
    for ((type, rows) in buildResourceSections(resources, grants.resources)) {
        val visible = rows.filter { row ->
            matchesSearch(search, row.label, row.resourceId, row.kind?.let { KIND_LABELS[it] ?: it } ?: "")
        }
        if (visible.isEmpty()) continue
        fun grantsOf(row: ResourceRow) = grantsByResource["$type:${row.resourceId}"].orEmpty()
        val withAccess = rows.count { grantsOf(it).isNotEmpty() }
        val missingRead = visible.filter { it.flag == null && "read" in it.actions && grantsOf(it).isEmpty() }
        val visibleGrants = visible.flatMap { grantsOf(it) }
        out += ResourceHeaderEntry(
            type, RESOURCE_TYPE_LABELS[type] ?: type, withAccess, rows.size, missingRead, visibleGrants,
            first = out.none { it is ResourceHeaderEntry },
        )
        visible.forEachIndexed { i, row -> out += ResourceRowEntry(row, grantsOf(row), i == 0, i == visible.lastIndex) }
    }
    return out
}

/** Linhas da aba Rotas (agrupadas por tag, na ordem do servidor) + rotas órfãs. */
fun routeEntries(routes: List<RouteOption>, grants: Grants, search: String): List<Entry> {
    val out = mutableListOf<Entry>()
    val grantByKey = grants.routes.associateBy { it.routeKey }
    val knownKeys = routes.mapTo(HashSet()) { it.key }
    val groups = LinkedHashMap<String, MutableList<RouteOption>>()
    for (route in routes) groups.getOrPut(route.tag) { mutableListOf() } += route
    for ((tag, tagRoutes) in groups) {
        val visible = tagRoutes.filter { matchesSearch(search, it.summary, it.key, tag) }
        if (visible.isEmpty()) continue
        out += RouteHeaderEntry(
            tag,
            granted = tagRoutes.count { it.key in grantByKey },
            total = tagRoutes.size,
            toGrant = visible.filter { it.key !in grantByKey }.map { it.key },
            toRevoke = visible.mapNotNull { grantByKey[it.key] },
            first = out.isEmpty(),
        )
        visible.forEachIndexed { i, route -> out += RouteRowEntry(route, grantByKey[route.key], i == 0, i == visible.lastIndex) }
    }
    val orphans = grants.routes.filter { it.routeKey !in knownKeys }
    if (orphans.isNotEmpty()) {
        out += OrphanHeaderEntry(first = out.isEmpty())
        orphans.forEachIndexed { i, g -> out += OrphanRowEntry(g, i == 0, i == orphans.lastIndex) }
    }
    return out
}
