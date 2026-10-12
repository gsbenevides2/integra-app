package br.dev.gui.integra.ui.tuya

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ChartPoint
import br.dev.gui.integra.ui.components.ChartSeries
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.SeriesKind
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.components.TimeChart
import br.dev.gui.integra.ui.components.formatDateTime
import br.dev.gui.integra.ui.components.formatDay
import br.dev.gui.integra.ui.components.formatTime
import br.dev.gui.integra.ui.components.niceTicks
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val HISTORY_CODES = listOf(
    "va_temperature",
    "va_humidity",
    "doorcontact_state",
    "pir_state",
    "battery_percentage",
    "battery_state",
)

// Sensores que só informam um nível grosseiro de bateria são plotados numa escala de 1 a 3.
private val BATTERY_STATE_PERCENT = mapOf("low" to "1", "middle" to "2", "high" to "3")

/** `SensorDrawerContent`: configuração do sensor e gráficos de histórico por código. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorDrawerContent(sensor: Sensor, onChanged: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember(sensor.id, sensor.name) { mutableStateOf(sensor.name) }
    var isSaving by remember { mutableStateOf(false) }
    var readings by remember(sensor.id) { mutableStateOf<List<SensorReading>>(emptyList()) }

    LaunchedEffect(sensor.id) {
        // Uma requisição por código: uma janela compartilhada deixaria os códigos falantes
        // (temperatura) empurrarem os raros (bateria, porta) para fora da página.
        readings = coroutineScope {
            HISTORY_CODES.map { code ->
                async { apiCall { tuyaApi.sensorHistory(sensor.id, code) }.getOrNull()?.readings.orEmpty() }
            }.awaitAll().flatten()
        }
    }

    fun save(patch: SensorPatch) = scope.launch {
        isSaving = true
        val result = apiCall { tuyaApi.updateSensor(sensor.id, patch) }
        isSaving = false
        result
            .onSuccess { toast.success("Sensor salvo"); onChanged() }
            .onFailure { toast.error("Falha ao salvar o sensor") }
    }

    TuyaSection {
        SubHeading("Configuração")
        TextInput(name, { name = it }, label = "Nome")
        IntegraSwitch(
            checked = sensor.enabled,
            onChange = { save(SensorPatch(enabled = it)) },
            label = if (sensor.enabled) "Coletando" else "Pausado",
        )
        IntegraSwitch(
            checked = sensor.hidden,
            onChange = { save(SensorPatch(hidden = it)) },
            label = if (sensor.hidden) "Oculto no painel" else "Visível no painel",
        )
        Text(
            "Ocultar tira o sensor do painel sem parar a coleta, então o histórico segue sem buracos.",
            style = Txt.xs,
            color = Tw.mist400,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoItem("Device ID", sensor.tuyaDeviceId, mono = true)
            InfoItem("Categoria", sensor.category ?: "-")
            InfoItem("Último evento", parseIso(sensor.lastEventAt)?.let(::localeDateTime) ?: "nenhum")
        }
        Row { IntegraButton("Salvar", { save(SensorPatch(name = name)) }, loading = isSaving) }
    }

    val hasBatteryPercentage = readings.any { it.code == "battery_percentage" }
    val pirReadings = remember(readings) {
        readings.map { if (it.code == "pir_state") it.copy(value = if (it.value == "pir") "true" else "false") else it }
    }
    val batteryReadings = remember(readings, hasBatteryPercentage) {
        if (hasBatteryPercentage) readings
        else readings.map { if (it.code == "battery_state") it.copy(value = BATTERY_STATE_PERCENT[it.value] ?: "NaN") else it }
    }

    SensorHistoryChart(readings, "va_temperature", "Temperatura", scale = 10.0, unit = " °C", color = Color(0xFFFB923C))
    SensorHistoryChart(readings, "va_humidity", "Umidade", unit = " %", color = Color(0xFF38BDF8))
    SensorHistoryChart(readings, "doorcontact_state", "Aberturas da porta", boolean = true, eventLabel = "aberturas", color = Color(0xFFFBBF24))
    SensorHistoryChart(pirReadings, "pir_state", "Detecções de movimento", boolean = true, eventLabel = "detecções", color = Color(0xFFA78BFA))
    SensorHistoryChart(
        batteryReadings,
        if (hasBatteryPercentage) "battery_percentage" else "battery_state",
        "Bateria",
        levels = if (hasBatteryPercentage) null else listOf("Baixa", "Média", "Alta"),
        unit = " %",
        color = Color(0xFF22C55E),
    )

    if (readings.isEmpty()) {
        Text(
            "Sem histórico ainda. O Integra grava os eventos que a Tuya envia assim que o sensor reporta uma mudança.",
            style = Txt.sm,
            color = Tw.mist400,
        )
    }
}

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** Rótulo curto do eixo X: só a hora para menos de 2 dias, senão só a data (`makeTickFormatter`). */
internal fun tickFormatter(min: Long?, max: Long?): (Long) -> String {
    val span = if (min != null && max != null) max - min else 0L
    return if (span < 2 * DAY) ::formatTime else ::formatDay
}

private data class TsValue(val ts: Long, val value: Double)

/** Média em no máximo [max] baldes de mesma largura (`downsample` do chartUtils). */
private fun downsample(points: List<TsValue>, max: Int = 48): List<TsValue> {
    if (points.size <= max) return points
    val mn = points.minOf { it.ts }
    val width = ((points.maxOf { it.ts } - mn).toDouble() / max).takeIf { it != 0.0 } ?: 1.0
    val buckets = sortedMapOf<Int, Pair<Double, Int>>()
    for (p in points) {
        val k = min(max - 1, floor((p.ts - mn) / width).toInt())
        val (sum, n) = buckets[k] ?: (0.0 to 0)
        buckets[k] = (sum + p.value) to (n + 1)
    }
    return buckets.map { (k, b) -> TsValue((mn + (k + 0.5) * width).toLong(), b.first / b.second) }
}

/** Conta timestamps por balde: hora até 3 dias de dados, senão dia (`bucketCount`). */
private fun bucketCount(timestamps: List<Long>): Pair<List<ChartPoint>, Long> {
    if (timestamps.isEmpty()) return emptyList<ChartPoint>() to HOUR
    val span = timestamps.max() - timestamps.min()
    val bucketMs = if (span <= 3 * DAY) HOUR else DAY
    val counts = sortedMapOf<Long, Int>()
    for (ts in timestamps) {
        val bucket = Math.floorDiv(ts, bucketMs) * bucketMs
        counts[bucket] = (counts[bucket] ?: 0) + 1
    }
    return counts.map { (ts, count) -> ChartPoint(ts, count.toFloat()) } to bucketMs
}

/** `Number.prototype.toString` para os rótulos do eixo (ponto decimal, sem zeros sobrando). */
private fun jsNumber(v: Float): String {
    val rounded = Math.round(v * 100) / 100.0
    return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
}

private fun parseJsNumber(raw: String): Double? = when {
    raw.isBlank() -> 0.0 // Number("") === 0
    else -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** `SensorHistoryChart`: linha (com média em 48 baldes), degraus para níveis ou barras de contagem. */
@Composable
private fun SensorHistoryChart(
    readings: List<SensorReading>,
    code: String,
    label: String,
    scale: Double = 1.0,
    unit: String = "",
    boolean: Boolean = false,
    eventLabel: String = "eventos",
    levels: List<String>? = null,
    color: Color = Color(0xFF38BDF8),
) {
    val model = remember(readings, code, scale, boolean, levels) {
        val own = readings.filter { it.code == code }
        val stamped = own.mapNotNull { r -> parseIso(r.recordedAt)?.let { it to r } }
        val raw = stamped.mapNotNull { (ts, r) -> parseJsNumber(r.value)?.let { TsValue(ts, it / scale) } }
        val points = downsample(raw).map { if (levels != null) it.copy(value = jsRound(it.value).toDouble()) else it }
        val events = stamped.filter { it.second.value == "true" }.map { it.first }
        if (own.isEmpty() || (!boolean && points.isEmpty())) null
        else {
            val all = stamped.map { it.first }
            ChartModel(
                points = points.map { ChartPoint(it.ts, it.value.toFloat()) },
                bars = if (boolean) bucketCount(events) else null,
                minTs = all.minOrNull(),
                maxTs = all.maxOrNull(),
            )
        }
    } ?: return

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = Txt.sm.semibold())
        val formatX = tickFormatter(model.minTs, model.maxTs)
        val bars = model.bars
        if (bars != null) {
            val (data, bucketMs) = bars
            val maxCount = data.maxOfOrNull { it.y ?: 0f } ?: 0f
            val ticks = integerTicks(maxCount)
            TimeChart(
                series = listOf(ChartSeries(if (bucketMs < DAY) "por hora" else "por dia", color, data, kind = SeriesKind.Bar)),
                height = 208.dp,
                yMin = 0f,
                yMax = ticks.last().first,
                yTicks = ticks,
                formatY = { "${it.toInt()} $eventLabel" },
                formatX = formatX,
                formatTooltipX = ::formatDateTime,
                showLegend = false,
            )
        } else if (levels != null) {
            TimeChart(
                series = listOf(ChartSeries(label, color, model.points, kind = SeriesKind.Step, fill = false)),
                height = 208.dp,
                yMin = 1f,
                yMax = levels.size.toFloat(),
                yTicks = levels.mapIndexed { i, l -> (i + 1).toFloat() to l },
                formatY = { levels.getOrNull(jsRound(it.toDouble()) - 1) ?: "" },
                formatX = formatX,
                formatTooltipX = ::formatDateTime,
                showLegend = false,
            )
        } else {
            val ys = model.points.mapNotNull { it.y }
            val ticks = niceTicks(ys.min(), ys.max()).map { it.first to jsNumber(it.first) + unit }
            TimeChart(
                series = listOf(ChartSeries(label, color, model.points, kind = SeriesKind.Line)),
                height = 208.dp,
                yMin = ticks.first().first,
                yMax = ticks.last().first,
                yTicks = ticks,
                formatY = { String.format(Locale.US, "%.1f", it) + unit },
                formatX = formatX,
                formatTooltipX = ::formatDateTime,
                showLegend = false,
            )
        }
    }
}

private class ChartModel(
    val points: List<ChartPoint>,
    val bars: Pair<List<ChartPoint>, Long>?,
    val minTs: Long?,
    val maxTs: Long?,
)

/** Eixo Y só com inteiros (`allowDecimals={false}`), ~5 linhas. */
private fun integerTicks(maxValue: Float): List<Pair<Float, String>> {
    val top = max(1, ceil(maxValue.toDouble()).toInt())
    val step = max(1, ceil(top / 4.0).toInt())
    val last = ceil(top.toDouble() / step).toInt() * step
    return (0..last step step).map { it.toFloat() to it.toString() }
}
