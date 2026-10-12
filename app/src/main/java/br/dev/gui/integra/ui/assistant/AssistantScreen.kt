package br.dev.gui.integra.ui.assistant

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import br.dev.gui.integra.KnockService
import br.dev.gui.integra.Prefs
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.LocalMe
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.FieldLabel
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.SheetSize
import br.dev.gui.integra.ui.components.SideSheet
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.shell.ScreenHeader
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.voice.Assistant
import br.dev.gui.integra.voice.AssistantApi
import br.dev.gui.integra.voice.AssistantSettings
import br.dev.gui.integra.voice.AssistantState
import br.dev.gui.integra.voice.ConversationSummary
import br.dev.gui.integra.voice.VoiceModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val SUGGESTIONS = listOf(
    "Liga a luz da sala",
    "Como está a internet?",
    "Tem alguma plataforma fora do ar?",
    "Como estão as linhas de trem?",
)

/** Dashboard "Assistente" (`dashboards/voice-assistant/index.tsx`). */
@Composable
fun AssistantScreen() {
    val me = LocalMe.current
    val allowed = me.hasRoute(Assistant.CHAT_ROUTE_KEY)
    val canConfigure = me.hasRoute(Assistant.SETTINGS_ROUTE_KEY)
    val canSeeHistory = me.hasRoute(Assistant.HISTORY_ROUTE_KEY)
    val canUpload = me.hasRoute(Assistant.UPLOAD_ROUTE_KEY)
    val state by Assistant.state.collectAsState()
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }

    // Abrir a tela é o mesmo que dizer a palavra (uma vez), a não ser que o assistente já esteja ocupado.
    LaunchedEffect(allowed) {
        if (allowed && !Assistant.autoStarted) {
            Assistant.autoStarted = true
            if (Assistant.state.value == AssistantState.Idle) Assistant.start()
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Assistente") {
            if (allowed) IntegraButton("Nova conversa", { scope.launch { Assistant.reset() } }, icon = Hero.ArrowPath, variant = ButtonVariant.Secondary)
            if (allowed && canSeeHistory) IconBtn(Hero.Clock, "Histórico", { historyOpen = true })
            if (canConfigure) IconBtn(Hero.Cog6Tooth, "Minhas preferências", { settingsOpen = true })
        }
        if (allowed && canSeeHistory) HistorySheet(historyOpen) { historyOpen = false }
        if (canConfigure) SettingsModal(settingsOpen) { settingsOpen = false }

        if (!allowed) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Hero.LockClosed, null, Modifier.size(40.dp), tint = Tw.mist500)
                Text("Peça acesso ao assistente para um administrador.", style = Txt.sm, color = Tw.mist300)
            }
            return@Column
        }
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(224.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    OrbPanel(state)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) { Chat(canUpload) }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { OrbPanel(state) }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { Chat(canUpload) }
        }
    }
}

@Composable
private fun OrbPanel(state: AssistantState) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var refresh by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val hasMic = remember(refresh) { KnockService.hasMicPermission(context) }
    val modelReady = remember(refresh) { VoiceModel.isReady(context) }
    val available = hasMic && modelReady
    var wake by remember { mutableStateOf(prefs.voiceWakeEnabled) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Orb(state, large = true, onClick = { if (state == AssistantState.Idle) Assistant.start() else Assistant.stop() })
        Text(state.label, style = Txt.sm, color = Tw.mist300)
        IntegraSwitch(
            checked = available && wake,
            onChange = { wake = it; prefs.voiceWakeEnabled = it; KnockService.startIfEnabled(context) },
            enabled = available,
            label = "Ouvir \"Ei Integra\"",
        )
        if (!available) {
            Text(
                if (!hasMic) "Wake word indisponível: permita o microfone nas configurações do app."
                else "Wake word indisponível: modelo de voz ${VoiceModel.status}.",
                style = Txt.xs.copy(fontSize = 11.sp), color = Tw.mist500, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 224.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Chat(canUpload: Boolean) {
    val messages by Assistant.messages.collectAsState()
    val state by Assistant.state.collectAsState()
    val hearing by Assistant.hearingSpeech.collectAsState()
    val attachments by Assistant.attachments.collectAsState()
    val uploading by Assistant.uploading.collectAsState()
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // Acompanha o fim do chat enquanto o texto chega (`useStickToBottom`).
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, hearing) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.scrollToItem(count - 1, Int.MAX_VALUE / 2)
    }

    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Tw.gray900)) {
        if (messages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(Hero.Microphone, null, Modifier.size(32.dp), tint = Tw.mist500)
                Text("Experimente dizer:", style = Txt.sm, color = Tw.mist300)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in SUGGESTIONS) {
                        Text(
                            s, style = Txt.xs, color = Tw.mist200,
                            modifier = Modifier.clip(CircleShape).background(Tw.gray800).clickable { Assistant.sendText(s) }.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, Assistant::retry, Assistant::markTyped)
                }
                if (state == AssistantState.Listening && hearing) {
                    item(key = "hearing") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text("Ouvindo você...", style = Txt.sm.copy(fontStyle = FontStyle.Italic), color = Tw.mist400)
                        }
                    }
                }
            }
        }
    }

    if (attachments.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (image in attachments) {
                Box {
                    AsyncImage(
                        Integra.url(image.url), "Anexo", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).clip(CircleShape).background(Tw.gray950)
                            .clickable { Assistant.removeAttachment(image.key) }.padding(2.dp),
                    ) { Icon(Hero.XMark, "Remover anexo", Modifier.size(12.dp)) }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) Assistant.attachImage(uri)
    }
    val send = { Assistant.sendText(draft); draft = "" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canUpload) {
            if (uploading) {
                Box(Modifier.padding(6.dp)) { CircularProgressIndicator(Modifier.size(20.dp), color = Tw.gray100, strokeWidth = 2.dp) }
            } else {
                IconBtn(Hero.Photo, "Anexar imagem", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            }
        }
        TextInput(
            draft, { draft = it },
            placeholder = if (attachments.isNotEmpty()) "Pergunte sobre a imagem (ou fale)..." else "Ou digite aqui...",
            modifier = Modifier.weight(1f),
            onDone = { if (draft.isNotBlank()) send() },
            textStyle = Txt.sm,
        )
        IconBtn(Hero.PaperAirplane, "Enviar", { send() }, enabled = draft.isNotBlank())
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withLocale(Locale("pt", "BR")).withZone(ZoneId.of("America/Sao_Paulo"))

/** As conversas passadas: abrir uma para continuar, ou excluir (`HistoryDrawer.tsx`). */
@Composable
private fun HistorySheet(open: Boolean, onClose: () -> Unit) {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val current by Assistant.conversationId.collectAsState()
    val conversations = remember { mutableStateListOf<ConversationSummary>() }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        loading = true
        apiCall { Integra.api<AssistantApi>().conversations() }
            .onSuccess { conversations.clear(); conversations.addAll(it) }
            .onFailure { toast.error("Falha ao carregar o histórico") }
        loading = false
    }
    SideSheet(open, onClose, "Histórico", size = SheetSize.Small) {
        when {
            loading && conversations.isEmpty() -> Text("Carregando...", style = Txt.sm, color = Tw.mist400)
            conversations.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Hero.ChatBubbleLeftRight, null, Modifier.size(32.dp), tint = Tw.mist500)
                Text("Nenhuma conversa ainda", style = Txt.sm, color = Tw.mist300)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in conversations) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (c.id == current) Tw.mist900 else androidx.compose.ui.graphics.Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            Modifier.weight(1f).clickable { onClose(); Assistant.openConversation(c.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(c.title, style = Txt.sm, color = Tw.mist100, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                runCatching { DATE_FORMAT.format(Instant.parse(c.updatedAt)) }.getOrDefault(c.updatedAt),
                                style = Txt.xs.copy(fontSize = 11.sp), color = Tw.mist400,
                            )
                        }
                        IconBtn(Hero.Trash, "Excluir ${c.title}", {
                            scope.launch {
                                val ok = confirm.confirm(
                                    "Excluir conversa",
                                    "Excluir \"${c.title}\" e as imagens dela? Não dá para desfazer.",
                                    confirmLabel = "Excluir",
                                )
                                if (!ok) return@launch
                                apiCall { Integra.api<AssistantApi>().deleteConversation(c.id) }
                                    .onSuccess {
                                        if (current == c.id) Assistant.reset()
                                        conversations.remove(c)
                                    }
                                    .onFailure { toast.error("Falha ao excluir a conversa") }
                            }
                        }, tint = Tw.mist400, size = 16.dp)
                    }
                }
            }
        }
    }
}

private const val MAX_EXTRA_INSTRUCTIONS_LENGTH = 4000

/** Instruções do usuário, somadas ao prompt só para ele (`SettingsModal.tsx`). */
@Composable
private fun SettingsModal(open: Boolean, onClose: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var instructions by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        loading = true
        apiCall { Integra.api<AssistantApi>().settings() }
            .onSuccess { instructions = it.extraInstructions }
            .onFailure { toast.error("Falha ao carregar suas preferências") }
        loading = false
    }
    Modal(open, onClose, "Minhas preferências") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel("Instruções para a Integra (só valem para você)")
            TextInput(
                instructions, { instructions = it.take(MAX_EXTRA_INSTRUCTIONS_LENGTH) },
                singleLine = false, minLines = 8, enabled = !loading,
                placeholder = "Ex.: Me chame de Gui. Responda de forma bem curta.",
            )
            Text(
                "${instructions.length}/$MAX_EXTRA_INSTRUCTIONS_LENGTH caracteres. Vale a partir da próxima mensagem.",
                style = Txt.xs.copy(fontSize = 11.sp), color = Tw.mist500,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                IntegraButton("Cancelar", onClose, variant = ButtonVariant.Secondary)
                IntegraButton("Salvar", {
                    scope.launch {
                        saving = true
                        apiCall { Integra.api<AssistantApi>().saveSettings(AssistantSettings(instructions)) }
                            .onSuccess { toast.success("Preferências salvas"); onClose() }
                            .onFailure { toast.error("Falha ao salvar suas preferências") }
                        saving = false
                    }
                }, loading = saving, enabled = !loading)
            }
        }
    }
}
