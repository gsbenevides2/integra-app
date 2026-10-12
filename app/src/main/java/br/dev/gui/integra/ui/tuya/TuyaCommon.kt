package br.dev.gui.integra.ui.tuya

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.components.Pill
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** `h2/h3 text-sm font-semibold text-mist-300`. */
@Composable
internal fun SubHeading(text: String, modifier: Modifier = Modifier, color: Color = Tw.mist300) =
    Text(text, style = Txt.sm.semibold(), color = color, modifier = modifier)

/** Botão-link `text-xs text-mist-400 underline`. */
@Composable
internal fun LinkButton(text: String, onClick: () -> Unit) = Text(
    text,
    style = Txt.xs.copy(textDecoration = TextDecoration.Underline),
    color = Tw.mist400,
    modifier = Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
)

/** Bolinha de cor `size-4 rounded-full border border-gray-600`. */
@Composable
internal fun ColorDot(color: Color, size: Dp = 16.dp, border: Boolean = true) = Box(
    Modifier
        .size(size)
        .clip(CircleShape)
        .background(color)
        .then(if (border) Modifier.border(1.dp, Tw.gray600, CircleShape) else Modifier),
)

/** Pílula "Oculta"/"Oculto" (`bg-gray-700 text-mist-300`). */
@Composable
internal fun HiddenPill(text: String) = Pill(text, Tw.gray700, Tw.mist300)

/** Abas Branco/Cor: `rounded-full bg-gray-800 p-1 gap-1 text-xs`. */
@Composable
internal fun ModeTabs(colour: Boolean, onSelect: (colour: Boolean) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Tw.gray800).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabButton("Branco", !colour) { onSelect(false) }
        TabButton("Cor", colour) { onSelect(true) }
    }
}

@Composable
private fun TabButton(text: String, active: Boolean, onClick: () -> Unit) = Text(
    text,
    style = Txt.xs,
    color = if (active) Tw.mist200 else Tw.mist400,
    modifier = Modifier
        .clip(CircleShape)
        .background(if (active) Tw.gray700 else Color.Transparent)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 4.dp),
)

/** Seção `flex flex-col gap-2`. */
@Composable
internal fun TuyaSection(content: @Composable () -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** Leitura de sensor: valor `text-lg` sobre o rótulo `text-xs text-mist-400`. */
@Composable
internal fun Reading(label: String, value: String) {
    Column {
        Text(value, style = Txt.lg)
        Text(label, style = Txt.xs, color = Tw.mist400)
    }
}

/**
 * Grade `grid-cols-[repeat(auto-fill,minmax(<min>,1fr))] gap-3`: quantas colunas de [minItemWidth]
 * couberem, todas com a mesma largura. [columns] força um número fixo (ex.: 2 em paisagem).
 */
@Composable
internal fun AdaptiveGrid(
    modifier: Modifier = Modifier,
    minItemWidth: Dp = 240.dp,
    gap: Dp = 12.dp,
    columns: Int? = null,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val cols = (columns ?: ((width + gapPx) / (minItemWidth.roundToPx() + gapPx))).coerceAtLeast(1)
        val itemWidth = max(0, (width - gapPx * (cols - 1)) / cols)
        val placeables = measurables.map { it.measure(Constraints(minWidth = itemWidth, maxWidth = itemWidth)) }
        val rows = placeables.chunked(cols)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + gapPx * (rows.size - 1).coerceAtLeast(0)
        layout(width, total) {
            var y = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, placeable -> placeable.place(c * (itemWidth + gapPx), y) }
                y += heights[r] + gapPx
            }
        }
    }
}

/** `new Date(iso).toLocaleString("pt-BR")` → "12/10/2026, 14:03:05". */
internal fun localeDateTime(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale("pt", "BR")).format(Date(ms))

internal fun parseIso(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Linha `<dt>Rótulo: </dt><dd>valor</dd>` de `text-xs text-mist-400`. */
@Composable
internal fun InfoItem(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = Txt.xs, color = Tw.mist400)
        Text(
            value,
            style = if (mono) Txt.xs.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else Txt.xs,
            color = Tw.mist400,
        )
    }
}

/** Um holder mutável que não dispara recomposição (o `useRef` do web). */
internal class Ref<T>(var value: T)

/** O último valor não nulo, para o conteúdo de modais/drawers não sumir na animação de saída. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { Ref<T?>(null) }
    if (value != null) holder.value = value
    return holder.value
}
