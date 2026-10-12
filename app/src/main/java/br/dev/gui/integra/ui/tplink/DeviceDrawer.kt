package br.dev.gui.integra.ui.tplink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.components.formatDateTime
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.medium
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Fundo dos cartões de interface: `rounded-md bg-gray-800/60 p-3`. */
private val InnerCard = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface.card.copy(alpha = 0.6f)).padding(12.dp)

@Composable
private fun DrawerSubtitle(text: String) = Text(text, style = Txt.sm.semibold(), color = Tw.mist300)

/** Conteúdo do drawer de um dispositivo (`DeviceDrawerContent`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceDrawerContent(device: TpDevice, onChanged: () -> Unit) {
    var history by remember(device.id) { mutableStateOf<List<DeviceHistoryEntry>?>(null) }
    LaunchedEffect(device.id) {
        history = null
        val now = System.currentTimeMillis()
        history = apiCall { Integra.api<TpLinkApi>().history(device.id, (now - DAY_MS).toString(), now.toString()) }
            .getOrDefault(emptyList())
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Marca:", style = Txt.sm, color = Tw.mist400)
            Text(device.brand, style = Txt.sm)
            Text("Tipo:", style = Txt.sm, color = Tw.mist400, modifier = Modifier.padding(start = 16.dp))
            Text(if (device.isRouter) "Roteador" else "Cliente", style = Txt.sm)
            if (device.isController) {
                Text(
                    "Controller",
                    style = Txt.xs,
                    modifier = Modifier.align(Alignment.CenterVertically).clip(CircleShape).background(Tw.mist900).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerSubtitle("Interfaces")
            for (iface in device.interfaces) {
                androidx.compose.runtime.key(iface.id) { InterfaceRow(iface, device, onChanged) }
            }
            if (device.interfaces.isEmpty()) Text("Nenhuma interface cadastrada.", style = Txt.sm, color = Tw.mist400)
            if (!device.isRouter || device.interfaces.isEmpty()) NewInterfaceForm(device.id, onChanged)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerSubtitle("Histórico de conexão (24h)")
            val current = history
            if (current == null) Text("Carregando histórico...", style = Txt.sm, color = Tw.mist400)
            else DeviceHistoryChart(current)
        }
    }
}

@Composable
private fun NewInterfaceForm(deviceId: String, onCreated: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun submit() {
        if (name.isEmpty() || mac.isEmpty() || ip.isEmpty()) {
            toast.error("Preencha nome, MAC e IP")
            return
        }
        scope.launch {
            saving = true
            val result = apiCall { Integra.api<TpLinkApi>().createInterface(deviceId, NewInterfaceBody(name, mac, ip)) }
            saving = false
            if (result.isFailure) {
                toast.error("Falha ao criar interface")
                return@launch
            }
            name = ""; mac = ""; ip = ""
            onCreated()
        }
    }

    Column(InnerCard, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInput(name, { name = it }, label = "Nome")
        TextInput(mac, { mac = it }, label = "MAC")
        TextInput(ip, { ip = it }, label = "IP")
        IntegraButton("Adicionar interface", ::submit, Modifier.fillMaxWidth(), icon = Hero.Plus, loading = saving)
    }
}

/** "MAC xx" / "IP xx": rótulo mist-400 + valor mono mist-200. */
@Composable
private fun MonoField(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Tw.mist200)) { append(value) }
        },
        style = Txt.xs,
        color = Tw.mist400,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterfaceRow(iface: TpInterface, device: TpDevice, onChanged: () -> Unit) {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val isControllerRouter = device.isRouter && device.isController

    // Valor otimista enquanto o PUT não volta (o web espera o refetch; aqui evita o "pulo" do switch).
    var reservedIp by remember(iface.reservedIp) { mutableStateOf(iface.reservedIp) }
    var allowList by remember(iface.allowList) { mutableStateOf(iface.allowList) }

    fun toggle(body: InterfaceFlagsBody, revert: () -> Unit) {
        scope.launch {
            val result = apiCall { Integra.api<TpLinkApi>().updateInterface(device.id, iface.id, body) }
            if (result.isFailure) {
                revert()
                toast.error("Falha ao atualizar interface")
                return@launch
            }
            onChanged()
        }
    }

    fun remove() {
        scope.launch {
            if (!confirm.confirm("Confirmar ação", "Remover a interface \"${iface.name}\"?")) return@launch
            val result = apiCall { Integra.api<TpLinkApi>().deleteInterface(device.id, iface.id) }
            if (result.isFailure) {
                toast.error("Falha ao remover interface")
                return@launch
            }
            onChanged()
        }
    }

    Column(InnerCard, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(iface.name, style = Txt.sm.medium(), modifier = Modifier.weight(1f))
            if (!isControllerRouter) IconBtn(Hero.Trash, "Remover interface", ::remove, tint = Tw.red400, size = 16.dp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonoField("MAC", iface.mac)
            MonoField("IP", iface.ip)
        }
        if (isControllerRouter) {
            Text("Interface do controller não pode ser editada.", style = Txt.xs, color = Tw.mist500)
        } else {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IntegraSwitch(reservedIp, { v ->
                    reservedIp = v
                    toggle(InterfaceFlagsBody(reservedIp = v)) { reservedIp = !v }
                }, label = "IP fixo")
                IntegraSwitch(allowList, { v ->
                    allowList = v
                    toggle(InterfaceFlagsBody(allowList = v)) { allowList = !v }
                }, label = "Permitido")
            }
        }
    }
}

/**
 * `DeviceHistoryChart`: uma barrinha por verificação (verde online, vermelha offline), com o
 * período e a porcentagem online embaixo. Tocar/arrastar mostra a hora da barra (o `title` do web).
 */
@Composable
fun DeviceHistoryChart(history: List<DeviceHistoryEntry>) {
    val sorted = remember(history) { history.sortedBy { it.createdAt } }
    if (sorted.isEmpty()) {
        Text("Sem histórico de conexão no período.", style = Txt.sm, color = Tw.mist400)
        return
    }
    val onlinePercent = remember(sorted) { (sorted.count { it.online } * 100.0 / sorted.size).roundToInt() }
    var hovered by remember(sorted) { mutableStateOf<Int?>(null) }
    val green = Tw.green500
    val red = Tw.red600

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .pointerInput(sorted) {
                    fun indexAt(x: Float): Int {
                        val gap = 2.dp.toPx()
                        val seg = max(2.dp.toPx(), (size.width - gap * (sorted.size - 1)) / sorted.size)
                        return (x / (seg + gap)).toInt().coerceIn(0, sorted.lastIndex)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        hovered = indexAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            hovered = indexAt(change.position.x)
                        }
                        hovered = null
                    }
                },
        ) {
            // gap-0.5 (2 px) entre barras, `min-w-[2px] flex-1`; o que não cabe é cortado (overflow-hidden).
            val gap = 2.dp.toPx()
            val seg = max(2.dp.toPx(), (size.width - gap * (sorted.size - 1)) / sorted.size)
            val radius = CornerRadius(2.dp.toPx())
            var x = 0f
            for (entry in sorted) {
                if (x > size.width) break
                drawRoundRect(if (entry.online) green else red, Offset(x, 0f), Size(seg, size.height), radius)
                x += seg + gap
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDateTime(sorted.first().createdAt), style = Txt.xs, color = Tw.mist400)
            val middle = hovered?.let { i ->
                val entry = sorted[i]
                "${formatDateTime(entry.createdAt)} · ${if (entry.online) "Online" else "Offline"}"
            } ?: "$onlinePercent% online no período"
            Text(
                middle,
                style = Txt.xs,
                color = if (hovered != null) Tw.mist200 else Tw.mist400,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatDateTime(sorted.last().createdAt), style = Txt.xs, color = Tw.mist400)
        }
    }
}
