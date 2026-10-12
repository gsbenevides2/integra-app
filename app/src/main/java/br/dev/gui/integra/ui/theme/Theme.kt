package br.dev.gui.integra.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tamanhos de texto do Tailwind (text-xs … text-2xl). O web usa px; aqui viram sp.
 * Use com `Text(..., style = Txt.sm)` e troque a cor com `color = Tw.mist400`.
 */
object Txt {
    val xs = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val sm = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val base = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val lg = TextStyle(fontSize = 18.sp, lineHeight = 28.sp)
    val xl = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val x2l = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)
    val x3l = TextStyle(fontSize = 30.sp, lineHeight = 36.sp)
}

/** `font-semibold` (600). O Roboto do Android 10 não tem 600 e cairia no regular: usa 700. */
fun TextStyle.semibold() = copy(fontWeight = FontWeight.Bold)
fun TextStyle.medium() = copy(fontWeight = FontWeight.Medium)
fun TextStyle.bold() = copy(fontWeight = FontWeight.Bold)

/** Cores de superfície do web (`body bg-gray-950 text-gray-100`, cards gray-800, drawers gray-900). */
object Surface {
    val page = Tw.gray950
    val card = Tw.gray800
    val sheet = Tw.gray900
    val border = Tw.gray700
    val text = Tw.gray100
}

private val colors = darkColorScheme(
    primary = Tw.mist500,
    onPrimary = Tw.gray100,
    secondary = Tw.blue900,
    background = Surface.page,
    onBackground = Surface.text,
    surface = Surface.sheet,
    onSurface = Surface.text,
    surfaceVariant = Surface.card,
    onSurfaceVariant = Tw.mist300,
    outline = Tw.mist600,
    error = Tw.red500,
)

@Composable
fun IntegraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(bodyMedium = Txt.base, bodyLarge = Txt.base, bodySmall = Txt.sm),
    ) {
        // Igual ao WebView do app antigo (textZoom 100): a escala de fonte do sistema não aumenta a UI,
        // para as telas ficarem com as mesmas proporções do web.
        val density = androidx.compose.ui.platform.LocalDensity.current
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = 1f),
            LocalContentColor provides Surface.text,
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                TextSelectionColors(handleColor = Tw.mist300, backgroundColor = Tw.mist700),
            content = content,
        )
    }
}
