package br.dev.gui.integra.ui.tuya

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.ChartPoint
import br.dev.gui.integra.ui.components.ChartSeries
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSlider
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.RingPicker
import br.dev.gui.integra.ui.components.SeriesKind
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.components.TimeChart
import br.dev.gui.integra.ui.components.formatDateTime
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** `DeviceDrawerContent`: controles, "Salvar como modo", configuração e histórico. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceDrawerContent(
    device: Device,
    isBusy: Boolean,
    onCommand: (DeviceCommand) -> Unit,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
) {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()

    var name by remember(device.id, device.name) { mutableStateOf(device.name) }
    var isSaving by remember { mutableStateOf(false) }
    var showSaveMode by remember { mutableStateOf(false) }
    var saveModeName by remember { mutableStateOf("") }
    var isSavingMode by remember { mutableStateOf(false) }
    var history by remember(device.id) { mutableStateOf<List<HistoryPoint>>(emptyList()) }

    LaunchedEffect(device.id) {
        apiCall { tuyaApi.deviceHistory(device.id) }.onSuccess { history = it.snapshots }
    }

    fun save() = scope.launch {
        isSaving = true
        val result = apiCall { tuyaApi.updateDevice(device.id, DevicePatch(name = name)) }
        isSaving = false
        result
            .onSuccess { toast.success("Dispositivo salvo"); onChanged() }
            .onFailure { toast.error("Falha ao salvar o dispositivo") }
    }

    fun saveAsMode() = scope.launch {
        if (saveModeName.isEmpty()) return@launch
        isSavingMode = true
        val state = device.state
        val body = buildJsonObject {
            put("name", JsonPrimitive(saveModeName))
            put("power", JsonPrimitive(state.power ?: true))
            put("brightness", state.brightness?.let(::JsonPrimitive) ?: JsonNull)
            put("colorTemp", state.colorTemp?.let(::JsonPrimitive) ?: JsonNull)
            put("colorHex", state.colorHex?.let(::JsonPrimitive) ?: JsonNull)
            put("workMode", JsonPrimitive(if (state.workMode == WORK_MODE_COLOUR) WORK_MODE_COLOUR else WORK_MODE_WHITE))
        }
        val result = apiCall { tuyaApi.createPreset(body) }
        isSavingMode = false
        result
            .onSuccess {
                toast.success("Modo \"$saveModeName\" salvo")
                showSaveMode = false
                saveModeName = ""
                onChanged()
            }
            .onFailure { toast.error("Falha ao salvar o modo") }
    }

    fun remove() = scope.launch {
        val ok = confirm.confirm(
            title = "Remover dispositivo",
            message = "Remover \"${device.name}\"? O histórico dele também será apagado.",
        )
        if (!ok) return@launch
        apiCall { tuyaApi.deleteDevice(device.id) }
            .onSuccess { toast.success("Dispositivo removido"); onDeleted() }
            .onFailure { toast.error("Falha ao remover o dispositivo") }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (device.isSwitch) SwitchControls(device, isBusy, onCommand)
        else LampControls(device, isBusy, onCommand)
    }

    if (device.isLamp && device.state.online) {
        TuyaSection {
            SubHeading("Modo")
            val hex = currentHex(device.state)
            val brightness = effectiveBrightness(device.state)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorDot(hexColor(hex))
                Text(
                    (if (isColourMode(device.state)) hex else "Branco") + (if (brightness != null) " · $brightness%" else ""),
                    style = Txt.sm,
                    color = Tw.mist400,
                )
            }
            IntegraButton(
                "Salvar como modo",
                {
                    saveModeName = "${device.name} - ${localeDateTime(System.currentTimeMillis())}"
                    showSaveMode = true
                },
                Modifier.fillMaxWidth(),
                icon = Hero.Bookmark,
                variant = ButtonVariant.Secondary,
            )
        }
    }
    Modal(showSaveMode, { showSaveMode = false }, "Salvar estado de \"${device.name}\" como modo") {
        TextInput(saveModeName, { saveModeName = it }, label = "Nome do modo")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            IntegraButton("Cancelar", { showSaveMode = false }, variant = ButtonVariant.Secondary)
            IntegraButton("Salvar", { saveAsMode() }, loading = isSavingMode, enabled = saveModeName.isNotEmpty())
        }
    }

    TuyaSection {
        SubHeading("Configuração")
        TextInput(name, { name = it }, label = "Nome")
        IntegraSwitch(
            checked = device.hidden,
            onChange = { hidden ->
                scope.launch {
                    apiCall { tuyaApi.updateDevice(device.id, DevicePatch(hidden = hidden)) }
                        .onSuccess { onChanged() }
                        .onFailure { toast.error("Falha ao alterar a visibilidade") }
                }
            },
            label = if (device.hidden) "Oculta no painel" else "Visível no painel",
        )
        Text(
            "Ocultar tira o dispositivo do painel sem parar o controle nem o histórico.",
            style = Txt.xs,
            color = Tw.mist400,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoItem("Device ID", device.tuyaDeviceId, mono = true)
            InfoItem("Tipo", if (device.isLamp) "Lâmpada" else "Interruptor")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IntegraButton("Salvar", { save() }, loading = isSaving)
            IntegraButton("Remover", { remove() }, icon = Hero.Trash, variant = ButtonVariant.Secondary)
        }
    }

    if (history.isNotEmpty()) DeviceHistoryChart(history)
}

/** `SwitchControls`: um switch por canal. */
@Composable
private fun SwitchControls(device: Device, isBusy: Boolean, onCommand: (DeviceCommand) -> Unit) {
    val state = device.state
    val channels = state.channels.orEmpty()
    val keys = remember(channels) { sortedChannels(channels) }
    when {
        !state.online -> Text(
            "O interruptor está offline. Os controles voltam assim que ele responder.",
            style = Txt.sm, color = Tw.mist400,
        )
        keys.isEmpty() -> Text("Sem canais conhecidos ainda.", style = Txt.sm, color = Tw.mist400)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (channel in keys) {
                val on = channels[channel] == true
                IntegraSwitch(
                    checked = on,
                    enabled = !isBusy,
                    onChange = { onCommand(DeviceCommand(channels = mapOf(channel to it))) },
                    label = channelLabel(keys, channel, on),
                )
            }
        }
    }
}

/** `LampControls`: abas Branco/Cor, anel de matiz/temperatura com o botão de ligar no centro e sliders. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LampControls(device: Device, isBusy: Boolean, onCommand: (DeviceCommand) -> Unit) {
    val state = device.state
    val disabled = isBusy || !state.online
    val hasColor = state.colorHex != null
    val hasTemp = state.colorTemp != null
    val hasTabs = hasColor && hasTemp

    // A aba acompanha o dispositivo aberto.
    var colourTab by remember(device.id) { mutableStateOf(isColourMode(state)) }

    // Matiz, saturação e valor só sobrevivem aproximadamente à ida e volta por `#rrggbb`, então o
    // painel guarda a sua cópia e só relê quando a lâmpada mudou por outro caminho.
    var hsv by remember(device.id) { mutableStateOf(hsvFromHex(state.colorHex)) }
    val sent = remember(device.id) { Ref(hsv) }
    LaunchedEffect(device.id, state.colorHex) {
        val hex = state.colorHex ?: return@LaunchedEffect
        val reported = hsvFromHex(hex)
        if (sameColor(reported, sent.value)) return@LaunchedEffect
        sent.value = reported
        hsv = reported
    }

    fun commitColor(next: Hsv) {
        hsv = next
        sent.value = next
        onCommand(DeviceCommand(colorHex = hexFromHsv(next)))
    }

    if (!state.online) {
        Text(
            "A lâmpada está offline. Os controles voltam assim que ela responder.",
            style = Txt.sm, color = Tw.mist400,
        )
        return
    }

    val isOn = state.power == true
    val showColour = colourTab && hasColor
    val swatch = hexColor(if (showColour) hueHex(hsv) else whiteHex(state.colorTemp))
    val brightness = if (showColour) max(1, jsRound(hsv.v * 100)) else state.brightness

    fun selectTab(colour: Boolean) {
        val previous = colourTab
        colourTab = colour
        if (colour == previous || state.workMode == null) return
        // As abas são a troca de modo, como no app da própria lâmpada.
        if (colour) onCommand(DeviceCommand(colorHex = hexFromHsv(hsv)))
        else onCommand(DeviceCommand(workMode = WORK_MODE_WHITE))
    }

    val togglePower = { onCommand(DeviceCommand(power = !isOn)) }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (hasTabs) ModeTabs(colourTab, ::selectTab)

        when {
            showColour -> RingPicker(
                value = (hsv.h / 360).toFloat(),
                onCommit = { commitColor(hsv.copy(h = it * 360.0)) },
                colors = HUE_STOPS,
                knobColor = hexColor(hueHex(hsv)),
                enabled = !disabled,
                modifier = Modifier.semantics { contentDescription = "Matiz" },
            ) { PowerButton(swatch, isOn, disabled, togglePower) }
            hasTemp -> RingPicker(
                value = (state.colorTemp ?: 50) / 100f,
                onCommit = { onCommand(DeviceCommand(colorTemp = jsRound(it * 100.0))) },
                colors = WHITE_STOP_COLORS,
                startAngle = WHITE_START,
                sweep = WHITE_SWEEP,
                knobColor = hexColor(whiteHex(state.colorTemp)),
                enabled = !disabled,
                modifier = Modifier.semantics { contentDescription = "Temperatura de cor" },
            ) { PowerButton(swatch, isOn, disabled, togglePower) }
            else -> Box(Modifier.size(112.dp)) { PowerButton(hexColor(currentHex(state)), isOn, disabled, togglePower) }
        }

        Text(
            (if (isOn) "Ligada" else "Desligada") + (if (brightness != null) " · $brightness%" else ""),
            style = Txt.sm,
            color = Tw.mist300,
        )

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (brightness != null) {
                IntegraSlider(
                    label = "Brilho",
                    min = 1,
                    max = 100,
                    value = brightness,
                    enabled = !disabled,
                    track = Brush.horizontalGradient(listOf(Color.Black, swatch)),
                    onCommit = { value ->
                        if (showColour) commitColor(hsv.copy(v = value / 100.0))
                        else onCommand(DeviceCommand(brightness = value))
                    },
                )
            }
            if (showColour) {
                IntegraSlider(
                    label = "Saturação",
                    min = 0,
                    max = 100,
                    value = jsRound(hsv.s * 100),
                    enabled = !disabled,
                    track = Brush.horizontalGradient(listOf(Color.White, hexColor(hueHex(hsv.copy(s = 1.0))))),
                    onCommit = { commitColor(hsv.copy(s = it / 100.0)) },
                )
            }
        }

        if (showColour) {
            val selectedHex = hueHex(hsv).lowercase()
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in COLOR_PRESETS) {
                    ColorPresetDot(preset, selected = selectedHex == preset, disabled = disabled) {
                        commitColor(hsvFromHex(preset).copy(v = hsv.v))
                    }
                }
            }
        } else if (hasTemp) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in listOf("Quente" to 0, "Neutro" to 50, "Frio" to 100)) {
                    Row(
                        Modifier
                            .alpha(if (disabled) 0.5f else 1f)
                            .clip(CircleShape)
                            .background(Tw.gray800)
                            .clickable(enabled = !disabled) { onCommand(DeviceCommand(colorTemp = value)) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ColorDot(hexColor(whiteHex(value)), size = 12.dp, border = false)
                        Text(label, style = Txt.xs, color = Tw.mist300)
                    }
                }
            }
        }
    }
}

/** Botão redondo de ligar/desligar no centro do anel, com brilho na cor da luz quando ligada. */
@Composable
private fun PowerButton(color: Color, isOn: Boolean, disabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (disabled) 0.5f else 1f)
            .drawBehind {
                if (!isOn) return@drawBehind
                // `box-shadow: 0 0 3rem -0.5rem <cor>`
                val r = size.minDimension / 2
                val glow = r + 40.dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0.7f),
                        (r - 8.dp.toPx()) / glow to color.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                        center = center,
                        radius = glow,
                    ),
                    radius = glow,
                )
            }
            .clip(CircleShape)
            .background(if (isOn) color else Tw.gray800)
            .border(1.dp, Tw.gray700, CircleShape)
            .clickable(enabled = !disabled, onClick = onClick)
            .semantics { contentDescription = if (isOn) "Desligar" else "Ligar" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Hero.Power, null, Modifier.size(32.dp), tint = if (isOn) Tw.gray900 else Tw.mist500)
    }
}

/** Bolinha de cor rápida `size-7`, com anel branco (`ring-2 ring-offset-2`) quando selecionada. */
@Composable
private fun ColorPresetDot(hex: String, selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    val color = hexColor(hex)
    Box(
        Modifier
            .size(28.dp)
            .drawBehind {
                if (selected) drawCircle(Color.White, size.minDimension / 2 + 3.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            .alpha(if (disabled) 0.5f else 1f)
            .clip(CircleShape)
            .background(color)
            .clickable(enabled = !disabled, onClick = onClick)
            .semantics { contentDescription = "Cor $hex" },
    )
}

private const val HOUR = 3_600_000L

/** `DeviceHistoryChart`: brilho médio ponderado no tempo, em ~48 baldes de no mínimo 1 h. */
@Composable
private fun DeviceHistoryChart(data: List<HistoryPoint>) {
    val buckets = remember(data) { brightnessBuckets(data) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Histórico", style = Txt.sm.semibold())
        TimeChart(
            series = listOf(ChartSeries("Brilho médio", Color(0xFFFBBF24), buckets, kind = SeriesKind.Area)),
            height = 224.dp,
            yMin = 0f,
            yMax = 100f,
            yTicks = listOf(0f to "0%", 50f to "50%", 100f to "100%"),
            formatY = { "${it.toInt()}%" },
            formatX = tickFormatter(buckets.firstOrNull()?.x, buckets.lastOrNull()?.x),
            formatTooltipX = ::formatDateTime,
            showLegend = false,
        )
    }
}

private data class BrightnessSample(val ts: Long, val brightness: Int)

private fun brightnessBuckets(data: List<HistoryPoint>): List<ChartPoint> {
    val points = data.mapNotNull { p ->
        val ts = parseIso(p.recordedAt) ?: return@mapNotNull null
        // Desligada ou inacessível conta como 0% para a área mostrar quando a luz estava acesa.
        BrightnessSample(ts, if (p.power == true && p.online) (p.brightness ?: 100) else 0)
    }.sortedBy { it.ts }
    if (points.isEmpty()) return emptyList()
    val start = points.first().ts
    val end = points.last().ts
    val span = if (points.size > 1) end - start else 0L
    // Os snapshots vêm em rajadas (vários toques em minutos), então plota a média por balde.
    val bucketMs = max(HOUR, ceil(span / 48.0 / HOUR).toLong() * HOUR)
    val out = mutableListOf<ChartPoint>()
    var from = floor(start.toDouble() / bucketMs).toLong() * bucketMs
    while (from <= end) {
        val to = from + bucketMs
        var sum = 0.0
        points.forEachIndexed { i, point ->
            val next = points.getOrNull(i + 1)?.ts ?: max(to, end)
            val overlap = min(next, to) - max(point.ts, from)
            if (overlap > 0) sum += overlap.toDouble() * point.brightness
        }
        out += ChartPoint(from + bucketMs / 2, jsRound(sum / bucketMs).toFloat())
        from += bucketMs
    }
    return out
}
