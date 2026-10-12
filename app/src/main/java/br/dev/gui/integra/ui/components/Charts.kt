package br.dev.gui.integra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class SeriesKind { Line, Area, Step, Bar }

/** Um ponto do gráfico: [x] em epoch ms; [y] nulo abre um buraco na linha. */
@Immutable
data class ChartPoint(val x: Long, val y: Float?)

/**
 * Uma série, com os defaults do Recharts usado no web: curva `monotone` (suave) em linhas e áreas,
 * degrau `stepAfter` em [SeriesKind.Step], traço de 1 px e preenchimento chapado com [fillAlpha]
 * (o `fillOpacity` do `<Area>`; o web usa 0.3 por padrão).
 */
@Immutable
data class ChartSeries(
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
    val kind: SeriesKind = SeriesKind.Line,
    val dashed: Boolean = false,
    /** Preenche a área sob a linha. Padrão: só em [SeriesKind.Area] e [SeriesKind.Step]. */
    val fill: Boolean = kind == SeriesKind.Area || kind == SeriesKind.Step,
    /** Formato do valor desta série no tooltip (padrão: o `formatY` do gráfico). */
    val formatValue: ((Float) -> String)? = null,
    /** Preenchimento da área sob a linha (o `fillOpacity` do `<Area>`; o web usa 0.3). */
    val fillAlpha: Float = 0.3f,
    val strokeWidth: Dp = 1.dp,
    /** Curva suave (`type="monotone"`); falso desenha segmentos retos (`type="linear"`). */
    val smooth: Boolean = true,
)

// Estilo dos gráficos do web (Recharts): grade `#374151` tracejada 3 3 com opacidade 0.4, eixos e
// rótulos `#9ca3af` em 11 px, tooltip `#111827` com borda `#374151` e texto de 12 px.
private val GridColor = Color(0xFF374151).copy(alpha = 0.4f)
private val AxisColor = Color(0xFF9CA3AF)
private val TooltipBg = Color(0xFF111827)
private val TooltipBorder = Color(0xFF374151)

private val ptBR = Locale("pt", "BR")
fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", ptBR).format(Date(ms))
fun formatDay(ms: Long): String = SimpleDateFormat("dd/MM", ptBR).format(Date(ms))
/** `toLocaleString("pt-BR", {day, month, hour, minute})` → "12/10, 00:23". */
fun formatDateTime(ms: Long): String = SimpleDateFormat("dd/MM, HH:mm", ptBR).format(Date(ms))

/**
 * Gráfico de séries no tempo, no lugar do Recharts do web. Toque/arraste mostra o tooltip.
 *
 * @param categoryX pontos igualmente espaçados pela ordem (o `<XAxis dataKey>` padrão do Recharts,
 *   usado em Server Metrics e TP-Link); falso usa uma escala de tempo (`type="number"`).
 * @param yTicks rótulos fixos no eixo Y (ex.: 0 → "Down", 1 → "Up"); sem eles, os rótulos são
 *   "redondos" como os do Recharts e o domínio começa em 0 (`domain [0, "auto"]`).
 */
@Composable
fun TimeChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    yMin: Float? = null,
    yMax: Float? = null,
    yTicks: List<Pair<Float, String>>? = null,
    formatY: (Float) -> String = { formatNumber(it) },
    formatX: ((Long) -> String)? = null,
    formatTooltipX: (Long) -> String = ::formatDateTime,
    showLegend: Boolean = series.size > 1,
    categoryX: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    val all = remember(series) { series.flatMap { it.points } }
    val xs = remember(series) { all.map { it.x }.distinct().sorted() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(series) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touchX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            touchX = change.position.x
                        }
                        touchX = null
                    }
                },
        ) {
            if (xs.isEmpty()) return@Box
            val minX = xs.first()
            val maxX = xs.last().let { if (it == minX) minX + 1 else it }
            val index = remember(xs) { xs.withIndex().associate { (i, x) -> x to i } }
            val ys = all.mapNotNull { it.y }
            var lo = yMin ?: min(0f, ys.minOrNull() ?: 0f)
            var hi = yMax ?: (ys.maxOrNull() ?: 1f)
            if (hi <= lo) hi = lo + 1f
            val ticks = yTicks ?: niceTicks(lo, hi).also { t ->
                if (yMin == null) lo = min(lo, t.first().first)
                if (yMax == null) hi = max(hi, t.last().first)
            }
            val spanDays = (maxX - minX) > 36 * 3600_000L
            val fx = formatX ?: if (categoryX || spanDays) ::formatDateTime else ::formatTime

            Canvas(Modifier.matchParentSize()) {
                val labelStyle = TextStyle(color = AxisColor, fontSize = 11.sp)
                val tickLen = 6.dp.toPx()
                val yLabelWidth = ticks.maxOf { measurer.measure(it.second, labelStyle).size.width }.toFloat()
                val left = yLabelWidth + tickLen + 4.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 6.dp.toPx()
                val bottom = size.height - 22.dp.toPx()
                val w = right - left
                val h = bottom - top
                fun px(x: Long): Float = if (categoryX) {
                    if (xs.size <= 1) left + w / 2 else left + (index[x] ?: 0).toFloat() / (xs.size - 1) * w
                } else left + (x - minX).toFloat() / (maxX - minX) * w
                fun py(y: Float) = bottom - (y - lo) / (hi - lo) * h

                // Rótulos do eixo X sem se sobrepor (`minTickGap={40}`), sempre com o último.
                val sampleWidth = measurer.measure(fx(maxX), labelStyle).size.width.toFloat()
                val gap = sampleWidth + 40.dp.toPx()
                val xTicks: List<Long> = if (categoryX) {
                    val per = if (xs.size > 1) w / (xs.size - 1) else w
                    val step = max(1, ceil(gap / per).toInt())
                    val picked = xs.indices.filter { it % step == 0 }.map { xs[it] }.toMutableList()
                    if (picked.last() != xs.last()) {
                        if (picked.size > 1 && px(xs.last()) - px(picked.last()) < gap) picked.removeAt(picked.size - 1)
                        picked += xs.last()
                    }
                    picked
                } else {
                    val count = max(1, floor(w / gap).toInt())
                    (0..count).map { minX + (maxX - minX) * it / count }
                }

                // Grade (horizontal nos ticks de Y, vertical nos de X).
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                for ((value, _) in ticks) drawLine(GridColor, Offset(left, py(value)), Offset(right, py(value)), 1.dp.toPx(), pathEffect = dash)
                for (x in xTicks) drawLine(GridColor, Offset(px(x), top), Offset(px(x), bottom), 1.dp.toPx(), pathEffect = dash)

                // Eixos com marcas e rótulos.
                drawLine(AxisColor, Offset(left, top), Offset(left, bottom), 1.dp.toPx())
                drawLine(AxisColor, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
                for ((value, label) in ticks) {
                    val y = py(value)
                    drawLine(AxisColor, Offset(left - tickLen, y), Offset(left, y), 1.dp.toPx())
                    val m = measurer.measure(label, labelStyle)
                    drawText(m, topLeft = Offset(left - tickLen - 2.dp.toPx() - m.size.width, y - m.size.height / 2))
                }
                for (x in xTicks) {
                    val cx = px(x)
                    drawLine(AxisColor, Offset(cx, bottom), Offset(cx, bottom + tickLen), 1.dp.toPx())
                    val m = measurer.measure(fx(x), labelStyle)
                    val lx = (cx - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
                    drawText(m, topLeft = Offset(lx, bottom + tickLen + 1.dp.toPx()))
                }

                val minGap = if (categoryX) w / max(1, xs.size - 1) else
                    (xs.zipWithNext { a, b -> b - a }.minOrNull() ?: (maxX - minX)).toFloat() / (maxX - minX) * w
                val barW = (minGap * 0.7f).coerceIn(2.dp.toPx(), 24.dp.toPx())
                for (s in series) drawSeries(s, ::px, ::py, bottom, barW)

                touchX?.let { tx ->
                    val nearest = xs.minByOrNull { abs(px(it) - tx) } ?: return@let
                    val x = px(nearest)
                    drawLine(Color(0xFFCCCCCC), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                    for (s in series) {
                        if (s.kind == SeriesKind.Bar) continue
                        val y = s.points.firstOrNull { it.x == nearest }?.y ?: continue
                        drawCircle(s.color, 4.dp.toPx(), Offset(x, py(y)))
                        drawCircle(Color.White, 4.dp.toPx(), Offset(x, py(y)), style = Stroke(1.dp.toPx()))
                    }
                    drawTooltip(measurer, nearest, series, formatTooltipX, formatY, x, top, left, right)
                }
            }
        }
        if (showLegend) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                for (s in series) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(14.dp, 10.dp)) {
                            drawLine(s.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
                        }
                        Text(s.name, style = TextStyle(fontSize = 12.sp), color = s.color)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSeries(s: ChartSeries, px: (Long) -> Float, py: (Float) -> Float, bottom: Float, barW: Float) {
    val stroke = Stroke(
        s.strokeWidth.toPx(),
        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())) else null,
    )
    if (s.kind == SeriesKind.Bar) {
        for (p in s.points) {
            val y = p.y ?: continue
            val x = px(p.x)
            val top = py(y)
            drawRoundRect(s.color, Offset(x - barW / 2, top), Size(barW, bottom - top), CornerRadius(2.dp.toPx()))
        }
        return
    }
    // Segmentos contínuos (um y nulo quebra a linha).
    val segments = mutableListOf<List<Offset>>()
    var current = mutableListOf<Offset>()
    for (p in s.points.sortedBy { it.x }) {
        if (p.y == null) { if (current.isNotEmpty()) segments += current; current = mutableListOf() }
        else current += Offset(px(p.x), py(p.y))
    }
    if (current.isNotEmpty()) segments += current
    for (seg in segments) {
        val path = Path()
        when {
            seg.size == 1 -> drawCircle(s.color, 2.5.dp.toPx(), seg[0])
            s.kind == SeriesKind.Step -> {
                path.moveTo(seg[0].x, seg[0].y)
                for (i in 1 until seg.size) { path.lineTo(seg[i].x, seg[i - 1].y); path.lineTo(seg[i].x, seg[i].y) }
            }
            s.smooth -> monotone(path, seg)
            else -> { path.moveTo(seg[0].x, seg[0].y); for (i in 1 until seg.size) path.lineTo(seg[i].x, seg[i].y) }
        }
        if (seg.size == 1) continue
        if (s.fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(seg.last().x, bottom)
                lineTo(seg.first().x, bottom)
                close()
            }
            drawPath(area, s.color.copy(alpha = s.color.alpha * s.fillAlpha))
        }
        drawPath(path, s.color, style = stroke)
    }
}

/** Curva monotone X (a mesma do d3/Recharts, `curveMonotoneX`): suave sem passar dos pontos. */
private fun monotone(path: Path, p: List<Offset>) {
    val n = p.size
    val slopes = FloatArray(n - 1) { i -> val dx = p[i + 1].x - p[i].x; if (dx == 0f) 0f else (p[i + 1].y - p[i].y) / dx }
    val tangents = FloatArray(n)
    tangents[0] = slopes[0]
    tangents[n - 1] = slopes[n - 2]
    for (i in 1 until n - 1) {
        val a = slopes[i - 1]; val b = slopes[i]
        tangents[i] = if (a * b <= 0f) 0f else {
            val h0 = p[i].x - p[i - 1].x; val h1 = p[i + 1].x - p[i].x
            val t = (a * h1 + b * h0) / (h0 + h1)
            (if (a > 0) 1f else -1f) * minOf(abs(a), abs(b), 0.5f * abs(t)) * 2f
        }
    }
    path.moveTo(p[0].x, p[0].y)
    for (i in 0 until n - 1) {
        val dx = (p[i + 1].x - p[i].x) / 3f
        path.cubicTo(p[i].x + dx, p[i].y + dx * tangents[i], p[i + 1].x - dx, p[i + 1].y - dx * tangents[i + 1], p[i + 1].x, p[i + 1].y)
    }
}

private fun DrawScope.drawTooltip(
    measurer: TextMeasurer,
    x: Long,
    series: List<ChartSeries>,
    formatX: (Long) -> String,
    formatY: (Float) -> String,
    at: Float,
    top: Float,
    left: Float,
    right: Float,
) {
    val lines = buildList {
        add(formatX(x) to Color(0xFFE5E7EB))
        for (s in series) {
            val p = s.points.firstOrNull { it.x == x } ?: continue
            // Web: `${value} ${name}` (sem o " : " e sem prefixar o label).
            add("${p.y?.let(s.formatValue ?: formatY) ?: "—"} ${s.name}" to s.color)
        }
    }
    val style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val measured = lines.map { (text, color) -> measurer.measure(text, style.copy(color = color)) }
    val pad = 10.dp.toPx()
    val spacing = 4.dp.toPx()
    val width = measured.maxOf { it.size.width } + 2 * pad
    val height = measured.sumOf { it.size.height } + spacing * (measured.size - 1) + 2 * pad
    val boxLeft = if (at + 12.dp.toPx() + width < right) at + 12.dp.toPx() else (at - 12.dp.toPx() - width).coerceAtLeast(left)
    drawRect(TooltipBg, Offset(boxLeft, top), Size(width, height))
    drawRect(TooltipBorder, Offset(boxLeft, top), Size(width, height), style = Stroke(1.dp.toPx()))
    var y = top + pad
    for (m in measured) {
        drawText(m, topLeft = Offset(boxLeft + pad, y))
        y += m.size.height + spacing
    }
}

/**
 * Rótulos "redondos" para o eixo Y, com a mesma conta do Recharts (`getNiceTickValues`, 5 ticks):
 * 0–600 vira 0, 150, 300, 450, 600; 0–6500 vira 0, 2000, …, 8000.
 */
fun niceTicks(lo: Float, hi: Float, count: Int = 5): List<Pair<Float, String>> {
    val range = (hi - lo).takeIf { it > 0 } ?: 1f
    val rough = range / (count - 1)
    val digitCount = floor(log10(rough.toDouble())).toInt() + 1
    val digitValue = 10.0.pow(digitCount).toFloat()
    val ratio = rough / digitValue
    val scale = if (digitCount != 1) 0.05f else 0.1f
    val step = ceil(ratio / scale - 1e-6f) * scale * digitValue
    val start = floor(lo / step) * step
    val out = mutableListOf<Pair<Float, String>>()
    var i = 0
    while (true) {
        val v = start + step * i
        out += v to formatNumber(v)
        if (v >= hi - step * 1e-4f || i > 20) break
        i++
    }
    return out
}

fun formatNumber(v: Float): String =
    if (abs(v - v.toLong()) < 0.001f) v.toLong().toString() else String.format(ptBR, "%.1f", v)

