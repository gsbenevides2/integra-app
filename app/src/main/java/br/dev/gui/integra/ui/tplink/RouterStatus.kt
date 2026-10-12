package br.dev.gui.integra.ui.tplink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.components.ChartPoint
import br.dev.gui.integra.ui.components.ChartSeries
import br.dev.gui.integra.ui.components.SeriesKind
import br.dev.gui.integra.ui.components.TimeChart
import br.dev.gui.integra.ui.components.formatDateTime
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.medium
import br.dev.gui.integra.ui.theme.semibold
import java.time.Instant

/** Número como o JS imprime (`44`, `44.5`). */
internal fun jsNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

/** Cartão `rounded-md bg-gray-800 p-3`. */
internal val PanelShape = RoundedCornerShape(6.dp)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Txt.xs, color = Tw.mist400)
        Text(value, style = Txt.sm.medium())
    }
}

/** `RouterStatusPanel`: grade de 10 estatísticas (2 / 3 / 5 colunas conforme a largura). */
@Composable
fun RouterStatusPanel(status: RouterStatus?) {
    val panel = Modifier.fillMaxWidth().clip(PanelShape).background(Surface.card).padding(12.dp)
    if (status == null) {
        Text("Nenhum status do roteador ainda.", style = Txt.sm, color = Tw.mist400, modifier = panel)
        return
    }
    val stats = remember(status) {
        listOf(
            "WAN IP" to status.wanIp.ifEmpty { "-" },
            "Conexão" to status.connectionStatus.ifEmpty { "-" },
            "Uptime Conexão" to status.connectionUptime.ifEmpty { "-" },
            "Uptime Roteador" to status.routerUptime.ifEmpty { "-" },
            "Firmware" to status.firmwareVersion.ifEmpty { "-" },
            "Hardware" to status.hardwareVersion.ifEmpty { "-" },
            "CPU" to (status.cpuUsage?.let { "${jsNumber(it)}%" } ?: "-"),
            "Memória" to (status.memoryUsage?.let { "${jsNumber(it)}%" } ?: "-"),
            "Download Total" to (status.totalDownload ?: "-"),
            "Upload Total" to (status.totalUpload ?: "-"),
        )
    }
    BoxWithConstraints(panel) {
        // grid-cols-2 sm:grid-cols-3 lg:grid-cols-5
        val cols = when {
            maxWidth < 640.dp -> 2
            maxWidth < 1024.dp -> 3
            else -> 5
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in stats.chunked(cols)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((label, value) in row) Stat(label, value, Modifier.weight(1f))
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val CpuColor = Color(0xFF38BDF8)
private val MemoryColor = Color(0xFFC084FC)
private val ConnectionColor = Color(0xFF22C55E)
private val PercentTicks = listOf(0f, 25f, 50f, 75f, 100f).map { it to "${it.toInt()}%" }

private fun parseIso(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

/**
 * Degrau ("step" do Recharts) desenhado como linha sem preenchimento: antes de cada mudança entra um
 * ponto de canto 1 ms antes, com o valor anterior.
 */
private fun stepPoints(statuses: List<Pair<Long, String>>): List<ChartPoint> {
    val out = ArrayList<ChartPoint>(statuses.size * 2)
    var previous: Float? = null
    for ((x, status) in statuses.sortedBy { it.first }) {
        val y = if (status.equals("connected", ignoreCase = true)) 100f else 0f
        if (previous != null && previous != y) out += ChartPoint(x - 1, previous)
        out += ChartPoint(x, y)
        previous = y
    }
    return out
}

/** `RouterStatusChart`: CPU e memória (linhas) e conexão (degrau tracejado, 100 = conectado). */
@Composable
fun RouterStatusChart(data: List<RouterStatusHistoryPoint>) {
    val series = remember(data) {
        val points = data.mapNotNull { p -> parseIso(p.collectedAt)?.let { it to p } }
        listOf(
            // connectNulls: pontos sem valor são só pulados.
            ChartSeries("CPU", CpuColor, points.mapNotNull { (x, p) -> p.cpuUsage?.let { ChartPoint(x, it.toFloat()) } }),
            ChartSeries("Memória", MemoryColor, points.mapNotNull { (x, p) -> p.memoryUsage?.let { ChartPoint(x, it.toFloat()) } }),
            ChartSeries(
                "Conexão",
                ConnectionColor,
                stepPoints(points.map { (x, p) -> x to p.connectionStatus }),
                kind = SeriesKind.Step,
                dashed = true,
                // 0/100 é só o valor do eixo; o tooltip mostra o status cru (`connectionStatus`).
                formatValue = { v -> if (v >= 50f) "connected" else "disconnected" },
            ),
        )
    }
    Column(
        Modifier.fillMaxWidth().clip(PanelShape).background(Surface.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("CPU, memória e conexão do roteador", style = Txt.sm.semibold())
        TimeChart(
            series = series,
            categoryX = true,
            height = 260.dp,
            yMin = 0f,
            yMax = 100f,
            yTicks = PercentTicks,
            formatY = { "${Math.round(it)}%" },
            formatX = ::formatDateTime,
            formatTooltipX = ::formatDateTime,
            showLegend = false,
        )
    }
}
