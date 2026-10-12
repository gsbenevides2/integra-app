package br.dev.gui.integra.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.voice.Assistant
import br.dev.gui.integra.voice.AssistantState
import coil3.compose.AsyncImage

/**
 * Aparece sobre as outras telas enquanto o assistente está ativo, para dar para ver (por exemplo)
 * a lâmpada mudar em vez de ser levado para o chat (`VoiceAssistantOverlay.tsx`).
 * Fundo `bg-gray-900/95` + `shadow-xl backdrop-blur-sm`.
 */
@Composable
fun BoxScope.AssistantOverlay() {
    val state by Assistant.state.collectAsState()
    if (state == AssistantState.Idle) return
    val hearing by Assistant.hearingSpeech.collectAsState()
    val messages by Assistant.messages.collectAsState()
    val reply = messages.lastOrNull()?.takeIf { !it.isUser }
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp)
            .widthIn(max = 576.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tw.gray900.copy(alpha = 0.95f))
            .border(1.dp, Tw.gray700, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Orb(state, large = false, onClick = Assistant::stop)
        Column(Modifier.weight(1f)) {
            Text(state.label, style = Txt.xs, color = Tw.mist400)
            Box {
                when {
                    state == AssistantState.Listening -> Text(if (hearing) "Ouvindo você..." else "Pode falar...", style = Txt.sm, color = Tw.mist100)
                    reply?.activity != null -> Text(toolActivity(reply.activity), style = Txt.sm, color = Tw.mist100)
                    reply != null && reply.text.isNotEmpty() ->
                        androidx.compose.runtime.key(reply.id) { TypedText(reply.text, reply.live, { Assistant.markTyped(reply.id) }, maxLines = 3) }
                    state == AssistantState.Thinking -> TypingDots(color = Tw.mist100)
                }
            }
        }
        reply?.images?.firstOrNull()?.let { image ->
            AsyncImage(Integra.url(image.url), "Imagem da resposta", contentScale = ContentScale.Crop, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)))
        }
        IconBtn(Hero.XMark, "Fechar assistente", Assistant::stop)
    }
}
