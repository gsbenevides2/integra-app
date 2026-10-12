package br.dev.gui.integra.ui.trainstatus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.Portal
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant

// Código compartilhado entre Train Status e Status Platform (grade responsiva e o HistoryModal).

/** Converte um timestamp ISO do servidor em epoch ms (null se inválido). */
fun parseIsoMs(value: String?): Long? =
    if (value.isNullOrBlank()) null else runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

/** Quantidade de colunas de `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3` para a largura da tela. */
@Composable
fun responsiveColumns(sm: Int = 2, lg: Int = 3): Int {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width >= 1024 -> lg
        width >= 640 -> sm
        else -> 1
    }
}

/**
 * Grade simples (não lazy) para listas curtas dentro de uma tela que já rola, como o
 * `grid gap-2` do web. Cada linha tem a altura do item mais alto.
 */
@Composable
fun <T> SimpleGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    key: ((T) -> Any)? = null,
    content: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in items.chunked(columns.coerceAtLeast(1))) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (item in row) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (key != null) androidx.compose.runtime.key(key(item)) { content(item) } else content(item)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Uma página do histórico (`checks`, `segments`, `hasMore`, `nextCursor`). */
interface HistoryPage {
    val hasMore: Boolean
    val nextCursor: String?
}

/**
 * Paginação por cursor do HistoryModal: `before` atual + pilha dos cursores anteriores
 * (chevron esquerdo volta para os mais recentes, o direito avança para os mais antigos).
 */
@Stable
class HistoryPager<P : HistoryPage>(
    private val scope: CoroutineScope,
    private val load: suspend (before: String?) -> Result<P>,
    private val onError: () -> Unit,
) {
    var isLoading by mutableStateOf(true)
        private set
    var page by mutableStateOf<P?>(null)
        private set
    private val cursorStack = mutableStateListOf<String?>()
    private var currentBefore: String? = null
    private var job: Job? = null

    val canGoNewer: Boolean get() = cursorStack.isNotEmpty()
    val canGoOlder: Boolean get() = page?.hasMore == true

    fun reset() {
        cursorStack.clear()
        currentBefore = null
        fetch(null)
    }

    fun goOlder() {
        val next = page?.nextCursor ?: return
        cursorStack.add(currentBefore)
        currentBefore = next
        fetch(next)
    }

    fun goNewer() {
        if (cursorStack.isEmpty()) return
        val previous = cursorStack.removeAt(cursorStack.lastIndex)
        currentBefore = previous
        fetch(previous)
    }

    private fun fetch(before: String?) {
        job?.cancel()
        job = scope.launch {
            isLoading = true
            load(before).onSuccess { page = it }.onFailure { onError() }
            isLoading = false
        }
    }
}

/**
 * Moldura do HistoryModal do web: fundo `bg-mist-950/90`, cartão `max-w-2xl rounded-lg bg-gray-800 p-4`,
 * título `text-lg font-semibold` com X, área do gráfico `h-64` (carregando / vazio / gráfico),
 * navegação entre páginas e a lista de segmentos (`max-h-48`, rolável).
 */
@Composable
fun HistoryModalFrame(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    isLoading: Boolean,
    isEmpty: Boolean,
    rangeLabel: String,
    canGoNewer: Boolean,
    canGoOlder: Boolean,
    onNewer: () -> Unit,
    onOlder: () -> Unit,
    chart: @Composable (height: Dp) -> Unit,
    segments: @Composable ColumnScope.() -> Unit,
) {
    // Em telas baixas (celular deitado) o gráfico encolhe para caber melhor.
    val chartHeight = if (LocalConfiguration.current.screenHeightDp < 480) 200.dp else 256.dp
    Portal {
        AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            BackHandler(open, onClose)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Tw.mist950.copy(alpha = 0.9f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .animateEnterExit(enter = scaleIn(initialScale = 0.95f), exit = scaleOut(targetScale = 0.95f))
                        .widthIn(max = 672.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface.card)
                        .clickable(remember { MutableInteractionSource() }, null) {}
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = Txt.lg.semibold(), modifier = Modifier.weight(1f))
                        IconBtn(Hero.XMark, "Close", onClose, size = 18.dp)
                    }

                    when {
                        isLoading -> ChartMessage("Loading history...", chartHeight)
                        isEmpty -> ChartMessage("No history available for this period.", chartHeight)
                        else -> chart(chartHeight)
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(
                            Hero.ChevronLeft, "Newer", onNewer, enabled = canGoNewer, size = 18.dp,
                            modifier = if (canGoNewer) Modifier else Modifier.alpha30(),
                        )
                        Text(
                            rangeLabel,
                            style = Txt.xs,
                            color = Tw.mist400,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconBtn(
                            Hero.ChevronRight, "Older", onOlder, enabled = canGoOlder, size = 18.dp,
                            modifier = if (canGoOlder) Modifier else Modifier.alpha30(),
                        )
                    }

                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 192.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = segments,
                    )
                }
            }
        }
    }
}

/** `disabled:opacity-30` (o IconBtn já aplica 0.5; 0.5 × 0.6 = 0.3). */
private fun Modifier.alpha30() = alpha(0.6f)

@Composable
private fun ChartMessage(text: String, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Text(text, style = Txt.sm, color = Tw.mist400)
    }
}

/** Linha de segmento: bolinha + rótulo, "since …" e "for …" (`rounded-md bg-gray-900 px-2 py-1 text-xs`). */
@Composable
fun SegmentRow(dotColor: androidx.compose.ui.graphics.Color, label: String, since: String, duration: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Tw.gray900)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            br.dev.gui.integra.ui.components.StatusDot(dotColor, size = 8.dp)
            Text(label, style = Txt.xs)
        }
        Text("since $since", style = Txt.xs, color = Tw.mist400)
        Text("for $duration", style = Txt.xs, color = Tw.mist400)
    }
}
