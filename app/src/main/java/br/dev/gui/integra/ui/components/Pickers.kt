package br.dev.gui.integra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * `Slider.tsx`: trilho `h-1.5` (preenchido mist-400 até o valor, gray-700 depois, ou um [track]
 * próprio), bolinha `size-4 bg-mist-200 border-2 border-gray-900`. [onCommit] só é chamado ao soltar,
 * e enquanto o dedo segura o valor que chega (do polling) é ignorado.
 */
@Composable
fun IntegraSlider(
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = 100,
    step: Int = 1,
    enabled: Boolean = true,
    label: String? = null,
    formatValue: (Int) -> String = { "$it%" },
    track: Brush? = null,
) {
    var local by remember { mutableStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    val commit by rememberUpdatedState(onCommit)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text("$label · ${formatValue(local)}", style = Txt.xs, color = Tw.mist400)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .pointerInput(enabled, min, max, step) {
                    if (!enabled) return@pointerInput
                    fun at(x: Float): Int {
                        val f = ((x - 8.dp.toPx()) / (size.width - 16.dp.toPx())).coerceIn(0f, 1f)
                        val raw = min + f * (max - min)
                        return ((raw / step).roundToInt() * step).coerceIn(min, max)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        local = at(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            local = at(change.position.x)
                            change.consume()
                        }
                        dragging = false
                        commit(local)
                    }
                },
        ) {
            val fraction = if (max > min) (local - min).toFloat() / (max - min) else 0f
            Canvas(Modifier.fillMaxSize()) {
                val thumbR = 8.dp.toPx()
                val trackH = 6.dp.toPx()
                val y = size.height / 2
                val left = thumbR
                val width = size.width - 2 * thumbR
                val trackTop = Offset(0f, y - trackH / 2)
                val trackSize = Size(size.width, trackH)
                val radius = androidx.compose.ui.geometry.CornerRadius(trackH / 2)
                if (track != null) {
                    drawRoundRect(track, trackTop, trackSize, radius)
                } else {
                    drawRoundRect(Tw.gray700, trackTop, trackSize, radius)
                    drawRoundRect(Tw.mist400, trackTop, Size(left + width * fraction, trackH), radius)
                }
                val cx = left + width * fraction
                drawCircle(Tw.gray900, thumbR, Offset(cx, y))
                drawCircle(Tw.mist200, thumbR - 2.dp.toPx(), Offset(cx, y))
            }
        }
    }
}

/** Onde começa o furo do anel, como fração do raio externo (igual ao `RingPicker.tsx`). */
private const val INNER = 0.64f

/**
 * `RingPicker.tsx`: anel com gradiente cônico ([colors] distribuídas ao longo do arco), arco parcial
 * opcional ([startAngle] graus a partir das 12h, sentido horário, cobrindo [sweep] graus), bolinha
 * branca e [content] no centro. [value] vai de 0 a 1; [onCommit] só ao soltar. O centro fica com o
 * conteúdo (toques ali não mexem no anel).
 */
@Composable
fun RingPicker(
    value: Float,
    onCommit: (Float) -> Unit,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    startAngle: Float = 0f,
    sweep: Float = 360f,
    knobColor: Color? = null,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    val commit by rememberUpdatedState(onCommit)

    Box(
        modifier
            .widthIn(max = 272.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (enabled) 1f else 0.4f)
            .pointerInput(enabled, startAngle, sweep) {
                if (!enabled) return@pointerInput
                fun hit(p: Offset): Pair<Float, Float> {
                    val dx = p.x - size.width / 2f
                    val dy = p.y - size.height / 2f
                    val radius = hypot(dx, dy) / (size.width / 2f)
                    var angle = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
                    if (angle < 0) angle += 360f
                    var position = (angle - startAngle + 360f) % 360f
                    if (position > sweep) position = if (position - sweep < 360f - position) sweep else 0f
                    return position / sweep to radius
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (v, r) = hit(down.position)
                    if (r < INNER) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    local = v
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) local = hit(change.position).first
                        change.consume()
                    }
                    dragging = false
                    commit(local)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f
            val thickness = outer * (1 - INNER)
            val mid = outer - thickness / 2f
            val arcSize = Size(mid * 2, mid * 2)
            val topLeft = Offset(center.x - mid, center.y - mid)
            // Gradiente "sweep" começa às 3h; giramos para começar em startAngle (a partir das 12h).
            val stops = colors.mapIndexed { i, c -> (i.toFloat() / (colors.size - 1)) * (sweep / 360f) to c }
            val brush = Brush.sweepGradient(
                *(if (sweep < 360f) stops + listOf(sweep / 360f + 0.0001f to colors.last(), 1f to colors.last()) else stops).toTypedArray(),
                center = center,
            )
            rotate(startAngle - 90f) {
                drawArc(
                    brush, 0f, sweep, false, topLeft, arcSize,
                    style = Stroke(thickness, cap = if (sweep < 360f) StrokeCap.Round else StrokeCap.Butt),
                )
            }
            val knobAngle = Math.toRadians((startAngle + local * sweep - 90f).toDouble())
            val knobCenter = Offset(center.x + cos(knobAngle).toFloat() * mid, center.y + sin(knobAngle).toFloat() * mid)
            val knobR = thickness / 2f + 2.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.25f), knobR + 2.dp.toPx(), knobCenter)
            if (knobColor != null) drawCircle(knobColor, knobR, knobCenter)
            drawCircle(Color.White, knobR, knobCenter, style = Stroke(3.dp.toPx()))
        }
        if (content != null) {
            Box(Modifier.fillMaxSize().insetFraction((1 - INNER) / 2f + 0.04f), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

/** Recuo proporcional ao tamanho do box (o `inset: X%` do web). */
private fun Modifier.insetFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val inset = (constraints.maxWidth * fraction).roundToInt()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = 0, minHeight = 0,
            maxWidth = (constraints.maxWidth - 2 * inset).coerceAtLeast(0),
            maxHeight = (constraints.maxHeight - 2 * inset).coerceAtLeast(0),
        ),
    )
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2)
    }
}
