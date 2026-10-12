package br.dev.gui.integra.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold

enum class ButtonVariant { Primary, Secondary, Danger }

/**
 * `Button.tsx`: `rounded-md px-3 py-1.5 text-sm`, primary `bg-mist-900`, secondary transparente,
 * spinner quando [loading]. Danger é o botão vermelho do `Confirm.tsx` (`bg-red-800`).
 */
@Composable
fun IntegraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    loading: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when (variant) {
        ButtonVariant.Primary -> if (pressed) Tw.mist600 else Tw.mist900
        ButtonVariant.Secondary -> if (pressed) Tw.gray700 else Color.Transparent
        ButtonVariant.Danger -> if (pressed) Tw.red700 else Tw.red800
    }
    val active = enabled && !loading
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(interaction, null, enabled = active, onClick = onClick)
            .alpha(if (active) 1f else 0.6f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Surface.text, androidx.compose.material3.LocalTextStyle provides Txt.sm) {
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            content()
        }
    }
}

/** Atalho para botão com ícone + texto. */
@Composable
fun IntegraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: ButtonVariant = ButtonVariant.Primary,
    loading: Boolean = false,
    enabled: Boolean = true,
) = IntegraButton(onClick, modifier, variant, loading, enabled) {
    if (icon != null && !loading) Icon(icon, null, Modifier.size(16.dp))
    Text(text, style = Txt.sm)
}

/** `IconButton.tsx`: botão redondo `p-1` (4 px), ícone `size-5`. */
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    size: Dp = 20.dp,
    background: Color = Color.Transparent,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .clip(CircleShape)
            .background(if (pressed) Tw.gray700 else background)
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(size), tint = tint)
    }
}

/** Rótulo `text-xs text-mist-300` usado acima de inputs. */
@Composable
fun FieldLabel(text: String) = Text(text, style = Txt.xs, color = Tw.mist300)

/** `Input.tsx`: `rounded-md border border-mist-600 bg-gray-900 p-2`, borda mist-300 com foco. */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = Txt.base,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = textStyle.copy(color = Surface.text),
            cursorBrush = SolidColor(Tw.mist300),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(6.dp))
                .background(Tw.gray900)
                .border(1.dp, if (focused) Tw.mist300 else Tw.mist600, RoundedCornerShape(6.dp))
                .alpha(if (enabled) 1f else 0.6f)
                .padding(8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = Tw.gray400)
                    inner()
                }
            },
        )
    }
}

data class SelectOption(val value: String, val label: String)

/** `Select.tsx`: mesmo visual do input, abre um menu com as opções. */
@Composable
fun SelectField(
    value: String,
    options: List<SelectOption>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) FieldLabel(label)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Tw.gray900)
                    .border(1.dp, if (open) Tw.mist300 else Tw.mist600, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { open = true }
                    .alpha(if (enabled) 1f else 0.6f)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val selected = options.firstOrNull { it.value == value }
                Text(
                    selected?.label ?: placeholder ?: "",
                    style = Txt.base,
                    color = if (selected == null) Tw.gray400 else Surface.text,
                    modifier = Modifier.weight(1f),
                )
                Icon(Hero.ChevronDown, null, Modifier.size(16.dp), tint = Tw.mist400)
            }
            DropdownMenu(open, { open = false }, Modifier.background(Tw.gray800)) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(option.label, style = Txt.sm, color = if (option.value == value) Tw.mist100 else Tw.gray300) },
                        onClick = { open = false; onChange(option.value) },
                    )
                }
            }
        }
    }
}

/** `Switch.tsx`: trilho `h-5 w-9`, ligado `bg-mist-500`, desligado `bg-gray-600`, bolinha branca. */
@Composable
fun IntegraSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val track by animateColorAsState(if (checked) Tw.mist500 else Tw.gray600, tween(150), label = "track")
    val offset by animateDpAsState(if (checked) 18.dp else 4.dp, tween(150), label = "thumb")
    Row(
        modifier
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled) { onChange(!checked) }
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(36.dp).height(20.dp).clip(CircleShape).background(track),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.offset(x = offset).size(14.dp).clip(CircleShape).background(Color.White))
        }
        if (label != null) Text(label, style = Txt.xs, color = Tw.mist300)
    }
}

/** Card padrão do web: `rounded-lg bg-gray-800 p-3`. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 12.dp,
    background: Color = Surface.card,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) { content() }
}

/** Pílula `rounded-full px-2 py-0.5 text-xs`, ex.: Online (green-900/green-300), Offline (red-950/red-300). */
@Composable
fun Pill(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Txt.xs,
        color = color,
        modifier = modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun OnlinePill(online: Boolean, modifier: Modifier = Modifier) =
    if (online) Pill("Online", Tw.green900, Tw.green300, modifier) else Pill("Offline", Tw.red950, Tw.red300, modifier)

/** Estado vazio: ícone `size-10 text-mist-500` + texto `text-mist-200`, centralizado com `py-16`. */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = Tw.mist500)
        Text(text, style = Txt.base, color = Tw.mist200)
        action?.invoke()
    }
}

/** Texto `text-sm text-mist-400` de carregando. */
@Composable
fun LoadingText(text: String = "Carregando...") = Text(text, style = Txt.sm, color = Tw.mist400)

/** Título de seção dentro das telas (`text-lg`). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = Txt.lg, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Placeholder pulsante (skeleton) `animate-pulse rounded bg-gray-700`. */
@Composable
fun Skeleton(modifier: Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        0.5f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(1000), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.alpha(alpha).clip(RoundedCornerShape(4.dp)).background(Tw.gray700))
}

/** Bolinha de status (`size-3 rounded-full`). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) =
    Box(modifier.size(size).clip(CircleShape).background(color))

/** Título com peso semibold, `text-base`. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) = Text(text, style = Txt.base.semibold(), modifier = modifier)
