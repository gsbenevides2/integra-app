package br.dev.gui.integra.ui.statusplatform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ChartPoint
import br.dev.gui.integra.ui.components.ChartSeries
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.SeriesKind
import br.dev.gui.integra.ui.components.TimeChart
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.trainstatus.HistoryModalFrame
import br.dev.gui.integra.ui.trainstatus.HistoryPager
import br.dev.gui.integra.ui.trainstatus.SegmentRow
import br.dev.gui.integra.ui.trainstatus.parseIsoMs
import java.util.Calendar

private fun pad(n: Int): String = n.toString().padStart(2, '0')

private fun formatDateTime(ms: Long): String {
    val d = Calendar.getInstance().apply { timeInMillis = ms }
    return "${pad(d.get(Calendar.DAY_OF_MONTH))}/${pad(d.get(Calendar.MONTH) + 1)}/${d.get(Calendar.YEAR)} " +
        "${pad(d.get(Calendar.HOUR_OF_DAY))}:${pad(d.get(Calendar.MINUTE))}:${pad(d.get(Calendar.SECOND))}"
}

private fun formatShortDateTime(ms: Long): String {
    val d = Calendar.getInstance().apply { timeInMillis = ms }
    return "${pad(d.get(Calendar.DAY_OF_MONTH))}/${pad(d.get(Calendar.MONTH) + 1)} " +
        "${pad(d.get(Calendar.HOUR_OF_DAY))}:${pad(d.get(Calendar.MINUTE))}"
}

private fun formatDuration(fromMs: Long, toMs: Long): String {
    val seconds = Math.floorDiv(toMs - fromMs, 1000L)
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}min"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h"
    val days = hours / 24
    return "${days}d"
}

private val UP_DOWN_TICKS = listOf(0f to "Down", 1f to "Up")

/** `component/HistoryModal`: gráfico Up/Down em degraus e a lista de segmentos da plataforma. */
@Composable
fun PlatformHistoryModal(open: Boolean, onClose: () -> Unit, platformId: String?, platformName: String?) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val currentId = rememberUpdatedState(platformId)
    val pager = remember {
        HistoryPager(
            scope,
            load = { before ->
                val id = currentId.value ?: return@HistoryPager Result.failure(IllegalStateException())
                apiCall { Integra.api<StatusPlatformApi>().history(id, before) }
            },
            onError = { toast.error("Failed to fetch history") },
        )
    }
    LaunchedEffect(open, platformId) {
        if (open && platformId != null) pager.reset()
    }

    val page = pager.page
    val checks = page?.checks.orEmpty()
    val series = remember(checks) {
        listOf(
            ChartSeries(
                name = "Status",
                color = Tw.green500,
                kind = SeriesKind.Step,
                points = checks.mapNotNull { check ->
                    val time = parseIsoMs(check.checkedAt) ?: return@mapNotNull null
                    ChartPoint(time, if (check.status == "OK") 1f else 0f)
                },
            ),
        )
    }
    val rangeLabel = remember(checks) {
        val first = parseIsoMs(checks.firstOrNull()?.checkedAt)
        val last = parseIsoMs(checks.lastOrNull()?.checkedAt)
        if (first != null && last != null) "${formatShortDateTime(first)} - ${formatShortDateTime(last)}" else ""
    }

    HistoryModalFrame(
        open = open,
        onClose = onClose,
        title = "History - ${platformName ?: ""}",
        isLoading = pager.isLoading,
        isEmpty = series.first().points.isEmpty(),
        rangeLabel = rangeLabel,
        canGoNewer = pager.canGoNewer,
        canGoOlder = pager.canGoOlder,
        onNewer = pager::goNewer,
        onOlder = pager::goOlder,
        chart = { height ->
            // Em tela estreita, 5 rótulos "dd/MM HH:mm" se sobrepõem: usa o formato automático (HH:mm ou dd/MM).
            val compactAxis = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
            TimeChart(
                series = series,
                height = height,
                yMin = 0f,
                yMax = 1f,
                yTicks = UP_DOWN_TICKS,
                formatY = { if (it >= 0.5f) "Operational" else "Down" },
                formatX = if (compactAxis) null else ::formatShortDateTime,
                formatTooltipX = ::formatDateTime,
                showLegend = false,
            )
        },
    ) {
        val now = System.currentTimeMillis()
        page?.segments.orEmpty().asReversed().forEachIndexed { index, segment ->
            androidx.compose.runtime.key("${segment.startedAt}-$index") {
                val ok = segment.status == "OK"
                val started = parseIsoMs(segment.startedAt) ?: now
                SegmentRow(
                    dotColor = if (ok) Tw.green700 else Tw.red700,
                    label = if (ok) "Operational" else "Down",
                    since = formatShortDateTime(started),
                    duration = formatDuration(started, parseIsoMs(segment.endedAt) ?: now),
                )
            }
        }
    }
}
