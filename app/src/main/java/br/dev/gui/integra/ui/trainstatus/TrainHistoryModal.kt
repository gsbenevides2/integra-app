package br.dev.gui.integra.ui.trainstatus

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
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToLong

private fun pad(value: Int): String = value.toString().padStart(2, '0')

/** `formatDate.ts` → `formatDateTime(date, { withYear, withSeconds })`. */
fun formatTrainDateTime(ms: Long, withYear: Boolean = false, withSeconds: Boolean = false): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val day = pad(c.get(Calendar.DAY_OF_MONTH))
    val month = pad(c.get(Calendar.MONTH) + 1)
    val datePart = if (withYear) "$day/$month/${c.get(Calendar.YEAR)}" else "$day/$month"
    val hm = "${pad(c.get(Calendar.HOUR_OF_DAY))}:${pad(c.get(Calendar.MINUTE))}"
    val timePart = if (withSeconds) "$hm:${pad(c.get(Calendar.SECOND))}" else hm
    return "$datePart $timePart"
}

/** A lightweight stand-in for date-fns's formatDistanceStrict — largest whole unit only. */
fun formatTrainDuration(fromMs: Long, toMs: Long): String {
    val diff = abs(toMs - fromMs).toDouble()
    val minutes = (diff / 60_000).roundToLong()
    if (minutes < 1) return "less than a minute"
    if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"}"
    val hours = (diff / 3_600_000).roundToLong()
    if (hours < 24) return "$hours hour${if (hours == 1L) "" else "s"}"
    val days = (diff / 86_400_000).roundToLong()
    return "$days day${if (days == 1L) "" else "s"}"
}

private val STATUS_TICKS = TrainLineStatus.entries.map { it.value.toFloat() to it.label }

/** `component/HistoryModal`: gráfico em degraus com 4 níveis e a lista de segmentos da linha. */
@Composable
fun TrainHistoryModal(open: Boolean, onClose: () -> Unit, lineCode: Int?) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val currentCode = rememberUpdatedState(lineCode)
    val pager = remember {
        HistoryPager(
            scope,
            load = { before ->
                val code = currentCode.value ?: return@HistoryPager Result.failure(IllegalStateException())
                apiCall { Integra.api<TrainStatusApi>().history(code, before) }
            },
            onError = { toast.error("Failed to fetch history") },
        )
    }
    LaunchedEffect(open, lineCode) {
        if (open && lineCode != null) pager.reset()
    }

    val page = pager.page
    val checks = page?.checks.orEmpty()
    // Situação de cada check, para a 3ª linha do tooltip (`point.situation` no web).
    val situations = remember(checks) {
        checks.mapNotNull { check -> parseIsoMs(check.checkedAt)?.let { it to check.situation } }.toMap()
    }
    val series = remember(checks) {
        listOf(
            ChartSeries(
                name = "Status",
                color = Tw.green500,
                kind = SeriesKind.Step,
                // fillOpacity={0.3} no web.
                fillAlpha = 0.3f,
                points = checks.mapNotNull { check ->
                    val time = parseIsoMs(check.checkedAt) ?: return@mapNotNull null
                    ChartPoint(time, (TrainLineStatus.of(check.status) ?: TrainLineStatus.UNKNOWN).value.toFloat())
                },
            ),
        )
    }
    val rangeLabel = remember(checks) {
        val first = parseIsoMs(checks.firstOrNull()?.checkedAt)
        val last = parseIsoMs(checks.lastOrNull()?.checkedAt)
        if (first != null && last != null) {
            "${formatTrainDateTime(first, withYear = true)} - ${formatTrainDateTime(last, withYear = true)}"
        } else ""
    }

    HistoryModalFrame(
        open = open,
        onClose = onClose,
        title = "History - Line ${lineCode ?: ""}",
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
                yMax = 3f,
                yTicks = STATUS_TICKS,
                formatY = { v -> TrainLineStatus.entries.firstOrNull { it.value == v.toInt() }?.label ?: "Unknown" },
                formatX = if (compactAxis) null else { ms -> formatTrainDateTime(ms) },
                formatTooltipX = { ms ->
                    val status = series.first().points.firstOrNull { it.x == ms }
                    val label = status?.y?.let { v -> TrainLineStatus.entries.firstOrNull { it.value == v.toInt() }?.label } ?: "Unknown"
                    val situation = situations[ms]
                    // Web: data + status colorido + situation em mist-400.
                    buildString {
                        append(formatTrainDateTime(ms, withYear = true, withSeconds = true))
                        append('\n'); append(label)
                        if (!situation.isNullOrBlank()) { append('\n'); append(situation) }
                    }
                },
                showLegend = false,
            )
        },
    ) {
        val now = System.currentTimeMillis()
        page?.segments.orEmpty().asReversed().forEachIndexed { index, segment ->
            androidx.compose.runtime.key("${segment.startedAt}-$index") {
                val status = TrainLineStatus.of(segment.status) ?: TrainLineStatus.UNKNOWN
                val started = parseIsoMs(segment.startedAt) ?: now
                SegmentRow(
                    dotColor = status.dot,
                    label = status.label,
                    since = formatTrainDateTime(started),
                    duration = formatTrainDuration(started, parseIsoMs(segment.endedAt) ?: now),
                )
            }
        }
    }
}
