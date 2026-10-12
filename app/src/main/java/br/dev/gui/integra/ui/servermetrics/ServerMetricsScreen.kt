package br.dev.gui.integra.ui.servermetrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.PollingEffect
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.formatDateTime
import br.dev.gui.integra.ui.components.ChartPoint
import br.dev.gui.integra.ui.components.ChartSeries
import br.dev.gui.integra.ui.components.EmptyState
import br.dev.gui.integra.ui.components.LoadingText
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.SeriesKind
import br.dev.gui.integra.ui.components.TimeChart
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.medium
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

private const val POLL_INTERVAL_MS = 10000L

/** Cores dos gráficos do web (Recharts). */
private val SkyLine = Color(0xFF38BDF8)
private val PurpleLine = Color(0xFFC084FC)
private val OrangeArea = Color(0xFFF97316)
private val GreenArea = Color(0xFF22C55E)

private fun parseMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

/** `toLocaleString("pt-BR", { day, month, hour, minute })` → "11/10, 20:15". */
private fun formatTime(ms: Long): String = SimpleDateFormat("dd/MM, HH:mm", Locale("pt", "BR")).format(Date(ms))

private fun formatTime(value: String): String = parseMs(value)?.let(::formatTime) ?: value

private fun Double.fixed(digits: Int) = String.format(Locale.US, "%.${digits}f", this)

@Composable
fun ServerMetricsScreen() {
    val toast = LocalToast.current
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var speedtestSnapshots by remember { mutableStateOf<List<SpeedtestSnapshot>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    PollingEffect(POLL_INTERVAL_MS) {
        val api = Integra.api<ServerMetricsApi>()
        coroutineScope {
            val history = async { apiCall { api.history() } }
            val speedtest = async { apiCall { api.speedtestHistory() } }
            history.await()
                .onSuccess { snapshots = it.snapshots }
                .onFailure { toast.error("Falha ao buscar métricas do servidor") }
            speedtest.await()
                .onSuccess { speedtestSnapshots = it.snapshots }
                .onFailure { toast.error("Falha ao buscar histórico de velocidade") }
        }
        isLoading = false
    }

    ScreenColumn("Server Metrics") {
        if (isLoading) {
            LoadingText("Carregando métricas...")
            return@ScreenColumn
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedtestLatest(speedtestSnapshots.lastOrNull())
            if (speedtestSnapshots.isNotEmpty()) SpeedtestChart(speedtestSnapshots)
        }

        if (snapshots.isEmpty()) {
            EmptyState(Hero.Server, "Ainda não há métricas coletadas") {
                Text("Aguarde o próximo ciclo de coleta via SSH.", style = Txt.sm, color = Tw.mist400)
            }
        } else {
            // `grid-cols-1 lg:grid-cols-2`: memória e rede lado a lado só em telas largas.
            val twoColumns = LocalConfiguration.current.screenWidthDp >= 1024
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { MemoryChart(snapshots) }
                        Box(Modifier.weight(1f)) { NetworkChart(snapshots) }
                    }
                } else {
                    MemoryChart(snapshots)
                    NetworkChart(snapshots)
                }
                DiskUsage(snapshots.last().disks)
            }
        }
    }
}

/** Cartão `rounded-md bg-gray-800 p-3` com título `text-sm font-semibold`. */
@Composable
private fun MetricCard(
    title: String,
    trailing: String? = null,
    gap: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Txt.sm.semibold(), modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = Txt.xs, color = Tw.mist400)
        }
        content()
    }
}

/** `component/SpeedtestLatest`. */
@Composable
private fun SpeedtestLatest(latest: SpeedtestSnapshot?) {
    MetricCard("Último teste (Cloudflare)", trailing = latest?.let { formatTime(it.collectedAt) }) {
        if (latest == null) {
            Text("Ainda não há teste de velocidade coletado.", style = Txt.xs, color = Tw.mist400)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Hero.ArrowDownTray, "Download", "${latest.downloadMbps.fixed(1)} Mbps", Modifier.weight(1f))
                Stat(Hero.ArrowUpTray, "Upload", "${latest.uploadMbps.fixed(1)} Mbps", Modifier.weight(1f))
                Stat(Hero.Signal, "Latência", "${latest.latencyMs.fixed(0)} ms", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = Tw.mist400)
        Column {
            Text(label, style = Txt.xs, color = Tw.mist400)
            Text(value, style = Txt.sm.medium(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private inline fun <T> List<T>.toPoints(time: (T) -> String, value: (T) -> Double): List<ChartPoint> =
    mapNotNull { item -> parseMs(time(item))?.let { ChartPoint(it, value(item).toFloat()) } }

/** `component/SpeedtestChart`: download e upload em Mbps. */
@Composable
private fun SpeedtestChart(data: List<SpeedtestSnapshot>) {
    val series = remember(data) {
        listOf(
            ChartSeries("Download", SkyLine, data.toPoints({ it.collectedAt }, { it.downloadMbps })),
            ChartSeries("Upload", PurpleLine, data.toPoints({ it.collectedAt }, { it.uploadMbps })),
        )
    }
    MetricCard("Velocidade da internet (Mbps)") {
        TimeChart(series, height = 260.dp, formatTooltipX = ::formatDateTime, showLegend = false, categoryX = true)
    }
}

/** `component/MemoryChart`: memória usada (laranja) e livre (verde) em MB. */
@Composable
private fun MemoryChart(data: List<Snapshot>) {
    val series = remember(data) {
        listOf(
            ChartSeries("Usado", OrangeArea, data.toPoints({ it.collectedAt }, { it.memoryUsedMb }), kind = SeriesKind.Area, fillAlpha = 0.25f),
            ChartSeries("Livre", GreenArea, data.toPoints({ it.collectedAt }, { it.memoryFreeMb }), kind = SeriesKind.Area, fillAlpha = 0.15f),
        )
    }
    MetricCard("Memória (MB)") {
        TimeChart(series, height = 260.dp, formatTooltipX = ::formatDateTime, showLegend = false, categoryX = true)
    }
}

/** `component/NetworkChart`: recebido (sky) e enviado (purple) em kB/s. */
@Composable
private fun NetworkChart(data: List<Snapshot>) {
    val series = remember(data) {
        listOf(
            ChartSeries("Download", SkyLine, data.toPoints({ it.collectedAt }, { it.networkRxKbs })),
            ChartSeries("Upload", PurpleLine, data.toPoints({ it.collectedAt }, { it.networkTxKbs })),
        )
    }
    MetricCard("Rede (kB/s)") {
        TimeChart(series, height = 260.dp, formatTooltipX = ::formatDateTime, showLegend = false, categoryX = true)
    }
}

private fun usageColor(percent: Double): Color = when {
    percent >= 90 -> Tw.red500
    percent >= 75 -> Tw.orange400
    else -> Tw.green500
}

/** `component/DiskUsage`: barra de uso por disco. */
@Composable
private fun DiskUsage(disks: List<DiskSnapshot>) {
    MetricCard("Uso de disco", gap = 12.dp) {
        if (disks.isEmpty()) {
            Text("Sem dados de disco ainda.", style = Txt.xs, color = Tw.mist400)
        } else {
            for (disk in disks) {
                androidx.compose.runtime.key(disk.filesystem) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${disk.filesystem} (${disk.mountedAt})",
                                style = Txt.xs,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${disk.usedMb.fixed(1)} / ${disk.totalMb.fixed(1)} (${disk.usagePercent.fixed(0)}%)",
                                style = Txt.xs,
                                color = Tw.mist400,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Tw.gray700)) {
                            val fraction = (disk.usagePercent.coerceIn(0.0, 100.0) / 100.0).toFloat()
                            if (fraction > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(usageColor(disk.usagePercent)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
