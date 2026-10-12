package br.dev.gui.integra.ui.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import br.dev.gui.integra.voice.AssistantImage
import br.dev.gui.integra.voice.AssistantMessage
import br.dev.gui.integra.voice.AssistantState
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.max

/** O que o chat mostra enquanto uma ferramenta roda (`toolLabels.ts`). */
private val TOOL_ACTIVITY = mapOf(
    "gerar_imagem" to "Gerando imagem…",
    "ver_camera" to "Olhando a câmera…",
    "listar_cameras" to "Procurando câmeras…",
    "listar_dispositivos" to "Consultando a casa…",
    "controlar_dispositivo" to "Ajustando o dispositivo…",
    "controlar_varios_dispositivos" to "Ajustando os dispositivos…",
    "listar_cenas" to "Consultando as cenas…",
    "aplicar_cena" to "Aplicando a cena…",
    "ler_sensores" to "Lendo os sensores…",
    "status_servidor" to "Verificando o servidor…",
    "status_plataformas" to "Verificando as plataformas…",
    "status_linhas_trem" to "Consultando as linhas de trem…",
    "status_roteador" to "Verificando o roteador…",
    "dispositivos_conectados" to "Vendo quem está na rede…",
    "agenda" to "Consultando a agenda…",
    "listar_rotinas" to "Consultando as rotinas…",
    "executar_rotina" to "Executando a rotina…",
)

fun toolActivity(tool: String) = TOOL_ACTIVITY[tool] ?: "Trabalhando nisso…"

/** Três pontinhos pulsando em sequência (`TypingDots.tsx`, keyframe `typing-dot` 1,2 s). */
@Composable
fun TypingDots(dotSize: Dp = 8.dp, color: Color = LocalContentColor.current) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(dotSize / 2), verticalAlignment = Alignment.CenterVertically) {
        for (delayMs in listOf(0, 160, 320)) {
            val alpha by transition.animateFloat(
                initialValue = 0.3f, targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1200
                        delayMillis = 0
                        0.3f at 0
                        1f at 480 using FastOutSlowInEasing
                        0.3f at 960
                    },
                    initialStartOffset = androidx.compose.animation.core.StartOffset(delayMs),
                ),
                label = "dot",
            )
            Box(Modifier.size(dotSize).alpha(alpha).clip(CircleShape).background(color))
        }
    }
}

/** Círculo colorido e animado com o estado do assistente (`Orb.tsx`). */
@Composable
fun Orb(state: AssistantState, large: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(
        when (state) {
            AssistantState.Idle -> Tw.mist900
            AssistantState.Listening -> Tw.blue600
            AssistantState.Thinking -> Tw.violet700
            AssistantState.Speaking -> Tw.emerald600
        },
        tween(200), label = "orb",
    )
    val fg = if (state == AssistantState.Idle) Tw.mist300 else Color.White
    val pulsing = state == AssistantState.Listening || state == AssistantState.Speaking
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(1f, 0.5f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha")
    val size = if (large) 128.dp else 48.dp
    Box(
        modifier
            .size(size)
            .alpha(if (pulsing) alpha else 1f)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(if (large) 32.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // No orb pequeno os pontinhos encolhem para caber no círculo.
        if (state == AssistantState.Thinking) TypingDots(if (large) 16.dp else 6.dp, fg)
        else Icon(Hero.Microphone, if (state == AssistantState.Idle) "Falar com a Integra" else "Parar", Modifier.fillMaxSize(), tint = fg)
    }
}

/**
 * Revela [text] num ritmo de digitação (~50 caracteres/s, acelera se ficar muito atrás), como o
 * `useTypewriter`. Com [animate] falso (histórico) mostra tudo de uma vez.
 */
@Composable
fun rememberTypewriter(text: String, animate: Boolean, onCaughtUp: () -> Unit): String {
    var shown by remember { mutableIntStateOf(if (animate) 0 else text.length) }
    val caughtUp by rememberUpdatedState(onCaughtUp)
    LaunchedEffect(text, animate) {
        if (!animate) { shown = text.length; return@LaunchedEffect }
        while (shown < text.length) {
            delay(20)
            val behind = text.length - shown
            shown = minOf(text.length, shown + max(1, behind / 150))
        }
        if (text.isNotEmpty()) caughtUp()
    }
    return text.take(shown.coerceAtMost(text.length))
}

/** Markdown da resposta, digitado quando é ao vivo (`TypedText.tsx`). */
@Composable
fun TypedText(text: String, live: Boolean, onTyped: () -> Unit, maxLines: Int = Int.MAX_VALUE) {
    // Decidido uma vez: a resposta nunca recomeça a digitar ao recompor.
    val animate = remember { live }
    MarkdownText(rememberTypewriter(text, animate, onTyped), maxLines = maxLines)
}

/**
 * Markdown leve (`MessageMarkdown.tsx`): parágrafos, listas, títulos, **negrito**, *itálico*,
 * `código` e links (só o texto). HTML e imagens nunca são mostrados.
 */
@Composable
fun MarkdownText(text: String, maxLines: Int = Int.MAX_VALUE) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(inline(block.text), style = Txt.sm.semibold(), maxLines = maxLines)
                is MdBlock.Paragraph -> Text(inline(block.text), style = Txt.sm, maxLines = maxLines)
                is MdBlock.Item -> Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(block.marker, style = Txt.sm, color = Tw.mist400)
                    Text(inline(block.text), style = Txt.sm, maxLines = maxLines)
                }
                is MdBlock.Code -> Text(
                    block.text, style = Txt.xs.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Tw.gray900).padding(8.dp),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Item(val marker: String, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

private val BULLET = Regex("^\\s*[-*+]\\s+(.*)")
private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)")
private val HEADING = Regex("^#{1,6}\\s+(.*)")

private fun parseBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) out += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            line.isBlank() -> flush()
            HEADING.matches(line) -> { flush(); out += MdBlock.Heading(HEADING.find(line)!!.groupValues[1]) }
            BULLET.matches(line) -> { flush(); out += MdBlock.Item("•", BULLET.find(line)!!.groupValues[1]) }
            NUMBERED.matches(line) -> { flush(); NUMBERED.find(line)!!.groupValues.let { out += MdBlock.Item("${it[1]}.", it[2]) } }
            line.trim().startsWith("|") -> { flush(); out += MdBlock.Paragraph(line.trim().trim('|').split('|').joinToString(" · ") { it.trim() }) }
            else -> { if (paragraph.isNotEmpty()) paragraph.append('\n'); paragraph.append(line) }
        }
        i++
    }
    flush()
    return out.filterNot { it is MdBlock.Paragraph && it.text.matches(Regex("^[-|:\\s·]+$")) }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\([^)]*\)|!\[[^\]]*]\([^)]*\)|\*(?!\s)(.+?)\*|_(?!\s)(.+?)_""")

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Tw.gray900)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(g[4]) }
            g[5].isNotEmpty() || g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5].ifEmpty { g[6] }) }
            else -> Unit // imagem em markdown: descartada
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

private fun imageCaption(image: AssistantImage) = when {
    image.source == "camera" && image.label != null -> "Câmera ${image.label}"
    image.source == "camera" -> "Câmera"
    image.source == "generated" -> "Imagem gerada"
    else -> "Imagem enviada"
}

/** Miniaturas que abrem a imagem inteira (URL pré-assinada) no navegador. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageImages(images: List<AssistantImage>) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (image in images) {
            Column(
                Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Integra.url(image.url)))) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AsyncImage(
                    model = Integra.url(image.url),
                    contentDescription = imageCaption(image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.sizeIn(maxWidth = 256.dp, maxHeight = 256.dp).size(192.dp).clip(RoundedCornerShape(8.dp)),
                )
                Text(imageCaption(image), style = Txt.xs.copy(fontSize = 11.sp), color = Tw.mist400)
            }
        }
    }
}

/** Uma mensagem do chat (`Message` do dashboard do assistente). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(message: AssistantMessage, onRetry: (Long) -> Unit, onTyped: (Long) -> Unit) {
    val isUser = message.isUser
    // `max-w-[85%]`: as bolhas ocupam só o necessário, até 85% da largura do chat.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val limit = maxWidth * 0.85f
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .widthIn(max = limit)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Tw.mist900 else Tw.gray800)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when {
                isUser -> Text(message.text, style = Txt.sm, color = Tw.mist100)
                message.pending && message.text.isEmpty() -> TypingDots()
                message.error != null && message.text.isEmpty() -> Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Hero.ExclamationCircle, null, Modifier.size(16.dp), tint = Tw.red300)
                    Text(message.error, style = Txt.sm, color = Tw.red300)
                }
                else -> TypedText(message.text, message.live, { onTyped(message.id) })
            }
        }
        if (message.retry != null) {
            Row(
                Modifier.clip(CircleShape).background(Tw.gray800).clickable { onRetry(message.id) }.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Hero.ArrowPath, null, Modifier.size(12.dp), tint = Tw.mist200)
                Text("Tentar de novo", style = Txt.xs, color = Tw.mist200)
            }
        }
        message.activity?.let { Activity(it) }
        if (message.images.isNotEmpty()) MessageImages(message.images)
        if (message.actions.isNotEmpty()) {
            FlowRow(Modifier.widthIn(max = limit), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (action in message.actions) {
                    Row(
                        Modifier.clip(CircleShape).background(if (action.ok) Tw.emerald950 else Tw.red950).padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val color = if (action.ok) Tw.emerald300 else Tw.red300
                        Icon(if (action.ok) Hero.CheckCircle else Hero.ExclamationCircle, null, Modifier.size(12.dp), tint = color)
                        Text(action.summary, style = Txt.xs.copy(fontSize = 11.sp), color = color)
                    }
                }
            }
        }
    }
    }
}

/** Progresso de uma ferramenta enquanto a resposta é escrita. */
@Composable
fun Activity(tool: String) {
    if (tool == "gerar_imagem") {
        val pulse = rememberInfiniteTransition(label = "img")
        val alpha by pulse.animateFloat(1f, 0.5f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "a")
        Column(
            Modifier.size(192.dp).alpha(alpha).clip(RoundedCornerShape(8.dp)).background(Tw.gray800),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Hero.Photo, null, Modifier.size(32.dp), tint = Tw.mist300)
            Text(toolActivity(tool), style = Txt.xs, color = Tw.mist300)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TypingDots(6.dp, Tw.mist400)
            Text(toolActivity(tool), style = Txt.xs, color = Tw.mist400)
        }
    }
}
