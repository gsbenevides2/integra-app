package br.dev.gui.integra.ui.tuya

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Porte de server/modules/tuya/color.ts + public/dashboards/tuya/lampColor.ts, com a mesma matemática.

@Immutable
data class Hsv(
    /** 0-360 */
    val h: Double,
    /** 0-1 */
    val s: Double,
    /** 0-1 */
    val v: Double,
)

/** `Math.round` do JS (meio arredonda para cima, inclusive em negativos). */
internal fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

fun hsvToRgb(hsv: Hsv): Triple<Int, Int, Int> {
    val (h, s, v) = hsv
    val c = v * s
    val x = c * (1 - abs(((h / 60) % 2) - 1))
    val m = v - c
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0.0)
        h < 120 -> Triple(x, c, 0.0)
        h < 180 -> Triple(0.0, c, x)
        h < 240 -> Triple(0.0, x, c)
        h < 300 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    return Triple(jsRound((r + m) * 255), jsRound((g + m) * 255), jsRound((b + m) * 255))
}

fun rgbToHsv(r: Double, g: Double, b: Double): Hsv {
    val rn = r / 255
    val gn = g / 255
    val bn = b / 255
    val mx = max(rn, max(gn, bn))
    val mn = min(rn, min(gn, bn))
    val delta = mx - mn
    var h = 0.0
    if (delta != 0.0) {
        h = when (mx) {
            rn -> 60 * (((gn - bn) / delta) % 6)
            gn -> 60 * ((bn - rn) / delta + 2)
            else -> 60 * ((rn - gn) / delta + 4)
        }
    }
    if (h < 0) h += 360
    return Hsv(h, if (mx == 0.0) 0.0 else delta / mx, mx)
}

private val HEX_RE = Regex("^#?([0-9a-fA-F]{6})$")

fun hexToRgb(hex: String): Triple<Int, Int, Int>? {
    val match = HEX_RE.find(hex.trim()) ?: return null
    val value = match.groupValues[1].toInt(16)
    return Triple((value shr 16) and 0xff, (value shr 8) and 0xff, value and 0xff)
}

fun rgbToHex(r: Double, g: Double, b: Double): String {
    fun part(value: Double) = jsRound(value).coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${part(r)}${part(g)}${part(b)}"
}

/** 0% é a leitura mais quente que a lâmpada informa e 100% a mais fria. */
val WHITE_STOPS = listOf("#ffb46b", "#f6f1ea", "#8ec2ff")

/** O arco do branco é aberto embaixo: quente à esquerda, frio à direita. */
const val WHITE_START = 210f
const val WHITE_SWEEP = 300f

/** O anel de matiz, amostrado a cada 30°. */
val HUE_STOPS: List<Color> = List(13) { Color.hsl(it * 30f % 360f, 1f, 0.5f) }

val WHITE_STOP_COLORS: List<Color> = WHITE_STOPS.map { hexColor(it) }

val COLOR_PRESETS = listOf(
    "#ff0000",
    "#ff7a00",
    "#ffd400",
    "#4ade80",
    "#22d3ee",
    "#3b82f6",
    "#a855f7",
    "#ff4fa3",
)

fun hsvFromHex(hex: String?): Hsv {
    val rgb = hex?.let { hexToRgb(it) } ?: return Hsv(0.0, 1.0, 1.0)
    return rgbToHsv(rgb.first.toDouble(), rgb.second.toDouble(), rgb.third.toDouble())
}

fun hexFromHsv(hsv: Hsv): String {
    val (r, g, b) = hsvToRgb(hsv)
    return rgbToHex(r.toDouble(), g.toDouble(), b.toDouble())
}

/** A cor da luz no brilho máximo, para amostras e brilhos. */
fun hueHex(hsv: Hsv): String = hexFromHsv(hsv.copy(v = 1.0))

/** Aproxima a aparência da lâmpada no modo branco numa dada temperatura. */
fun whiteHex(colorTemp: Int?): String {
    val percent = (colorTemp ?: 50).coerceIn(0, 100).toDouble()
    val (from, to) = if (percent <= 50) WHITE_STOPS[0] to WHITE_STOPS[1] else WHITE_STOPS[1] to WHITE_STOPS[2]
    val ratio = if (percent <= 50) percent / 50 else (percent - 50) / 50
    val a = hexToRgb(from)!!
    val b = hexToRgb(to)!!
    return rgbToHex(
        a.first + (b.first - a.first) * ratio,
        a.second + (b.second - a.second) * ratio,
        a.third + (b.third - a.third) * ratio,
    )
}

fun isColourMode(state: DeviceState): Boolean = state.workMode == WORK_MODE_COLOUR && state.colorHex != null

/** A cor que o dispositivo mostra agora, em qualquer modo. */
fun currentHex(state: DeviceState): String =
    if (isColourMode(state)) hueHex(hsvFromHex(state.colorHex)) else whiteHex(state.colorTemp)

/**
 * No modo cor a lâmpada tira o brilho do canal V do `colour_data`; o data point de brilho só vale
 * no modo branco, então cada modo lê a sua fonte.
 */
fun effectiveBrightness(state: DeviceState): Int? =
    if (isColourMode(state)) max(1, jsRound(hsvFromHex(state.colorHex).v * 100)) else state.brightness

fun brightnessCommand(state: DeviceState, percent: Int): DeviceCommand =
    if (!isColourMode(state)) DeviceCommand(brightness = percent)
    else DeviceCommand(colorHex = hexFromHsv(hsvFromHex(state.colorHex).copy(v = percent / 100.0)))

/** Verdadeiro quando duas cores estão tão próximas que a diferença veio de arredondamento. */
fun sameColor(a: Hsv, b: Hsv): Boolean {
    val hueGap = abs(a.h - b.h)
    return min(hueGap, 360 - hueGap) < 6 && abs(a.s - b.s) < 0.04 && abs(a.v - b.v) < 0.04
}

/** "#rrggbb" → Color (cinza se inválido). */
fun hexColor(hex: String?): Color {
    val rgb = hex?.let { hexToRgb(it) } ?: return Color.Gray
    return Color(rgb.first, rgb.second, rgb.third)
}
