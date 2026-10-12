package br.dev.gui.integra.ui.tuya

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSlider
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.OnlinePill
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt

private val CardShape = RoundedCornerShape(6.dp)

/** Canais ordenados numericamente, como `Object.keys(channels).sort((a, b) => Number(a) - Number(b))`. */
internal fun sortedChannels(channels: Map<String, Boolean>?): List<String> =
    channels.orEmpty().keys.sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.NaN }, { it }))

/** Rótulo de um canal: "Ligado"/"Desligado" se for o único, senão "Canal N". */
internal fun channelLabel(keys: List<String>, channel: String, on: Boolean) =
    if (keys.size == 1) (if (on) "Ligado" else "Desligado") else "Canal $channel"

/** `DeviceCard`: lâmpada ou interruptor com liga/desliga, brilho e canais. */
@Composable
fun DeviceCard(
    device: Device,
    isBusy: Boolean,
    onCommand: (DeviceCommand) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = device.state
    val isOn = state.power == true
    val channels = state.channels.orEmpty()
    val channelKeys = remember(channels) { sortedChannels(channels) }
    val anyChannelOn = channelKeys.any { channels[it] == true }
    val tintHex = remember(state) { currentHex(state) }
    val tint = hexColor(tintHex)
    val brightness = remember(state) { effectiveBrightness(state) }
    val disabled = isBusy || !state.online

    Column(
        modifier
            .alpha(if (device.hidden) 0.5f else 1f)
            .clip(CardShape)
            .background(Surface.card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (device.isSwitch) {
                    Icon(Hero.Bolt, null, Modifier.size(20.dp), tint = if (anyChannelOn) Tw.amber300 else Tw.mist500)
                } else {
                    Icon(Hero.LightBulb, null, Modifier.size(20.dp), tint = if (isOn) tint else Tw.mist500)
                }
                Text(device.name, style = Txt.base, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (device.hidden) HiddenPill("Oculta")
                OnlinePill(state.online)
            }
        }

        if (device.isSwitch) {
            if (channelKeys.isEmpty()) {
                Text("Sem canais conhecidos ainda.", style = Txt.xs, color = Tw.mist400)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (channel in channelKeys) {
                        val on = channels[channel] == true
                        IntegraSwitch(
                            checked = on,
                            enabled = !disabled,
                            onChange = { onCommand(DeviceCommand(channels = mapOf(channel to it))) },
                            label = channelLabel(channelKeys, channel, on),
                        )
                    }
                }
            }
        } else {
            IntegraSwitch(
                checked = isOn,
                enabled = !disabled,
                onChange = { onCommand(DeviceCommand(power = it)) },
                label = if (isOn) "Ligada" else "Desligada",
            )
        }

        if (!device.isSwitch && brightness != null) {
            IntegraSlider(
                label = "Brilho",
                min = 1,
                max = 100,
                value = brightness,
                enabled = !disabled,
                track = Brush.horizontalGradient(listOf(Color.Black, tint)),
                onCommit = { onCommand(brightnessCommand(state, it)) },
            )
        }

        if (!device.isSwitch && state.colorHex != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorDot(tint)
                Text(if (isColourMode(state)) tintHex else "Modo branco", style = Txt.xs, color = Tw.mist400)
            }
        }
    }
}

/** `PresetCard`: amostra do modo e botões de aplicar. */
@Composable
fun PresetCard(
    preset: Preset,
    onOpen: () -> Unit,
    onApply: () -> Unit,
    firstOnlineDeviceName: String?,
    onQuickApply: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isColour = preset.workMode == WORK_MODE_COLOUR && preset.colorHex != null
    val hsv = if (isColour) hsvFromHex(preset.colorHex) else null
    val tintHex = if (hsv != null) hueHex(hsv) else whiteHex(preset.colorTemp)
    val tint = hexColor(tintHex)
    val brightness = if (hsv != null) jsRound(hsv.v * 100) else preset.brightness

    Column(
        modifier.clip(CardShape).background(Surface.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Hero.LightBulb, null, Modifier.size(20.dp), tint = tint)
            Text(preset.name, style = Txt.base, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(tint)
            Text(
                (if (isColour) tintHex else "Branco") + (if (brightness != null) " · $brightness%" else ""),
                style = Txt.xs,
                color = Tw.mist400,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onQuickApply != null && firstOnlineDeviceName != null) {
                IntegraButton(
                    "Aplicar na $firstOnlineDeviceName", onQuickApply,
                    Modifier.fillMaxWidth(), icon = Hero.Play, variant = ButtonVariant.Secondary,
                )
            }
            IntegraButton(
                if (onQuickApply != null) "Aplicar em outra" else "Aplicar", onApply,
                Modifier.fillMaxWidth(), icon = Hero.Play, variant = ButtonVariant.Secondary,
            )
        }
    }
}

/** A Tuya manda décimos de grau e porcentagem inteira. */
private fun formatTemperature(raw: String?): String? {
    val value = raw?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return String.format(java.util.Locale.US, "%.1f °C", value / 10)
}

private fun formatHumidity(raw: String?): String? {
    val value = raw?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return "${jsRound(value)} %"
}

private fun batteryLabel(readings: Map<String, String>): String? {
    readings["battery_percentage"]?.let { return "$it%" }
    readings["battery_state"]?.let { state ->
        return mapOf("high" to "alta", "middle" to "média", "low" to "baixa")[state] ?: state
    }
    return null
}

/** `SensorCard`: o card inteiro abre o drawer do sensor. */
@Composable
fun SensorCard(sensor: Sensor, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val readings = sensor.readings
    val battery = batteryLabel(readings)
    val tamper = readings["temper_alarm"] == "true"
    val isSilent = readings.isEmpty()
    val doorOpen = readings["doorcontact_state"] == "true"
    // O PIR manda um enum: "pir" (movimento) ou "none" (livre).
    val motionRaw = readings["pir_state"] ?: readings["pir"]
    val motionDetected = motionRaw == "pir" || motionRaw == "true"
    val temperature = formatTemperature(readings["va_temperature"])
    val humidity = formatHumidity(readings["va_humidity"])

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier
            .alpha(if (sensor.hidden) 0.5f else 1f)
            .clip(CardShape)
            .background(if (pressed) Tw.gray700.copy(alpha = 0.6f).compositeOver(Surface.card) else Surface.card)
            .clickable(interaction, null, onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (icon, color) = when (sensor.kind) {
                    "door" -> if (doorOpen) Hero.LockOpen to Tw.amber300 else Hero.LockClosed to Tw.mist400
                    "temperature_humidity" -> Hero.Sun to Tw.sky300
                    "motion" -> Hero.Eye to (if (motionDetected) Tw.violet300 else Tw.mist400)
                    else -> Hero.Signal to Tw.mist400
                }
                Icon(icon, null, Modifier.size(20.dp), tint = color)
                Text(sensor.name, style = Txt.base, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sensor.hidden) HiddenPill("Oculto")
                OnlinePill(sensor.online)
            }
        }

        if (sensor.kind == "door") Reading("Porta", if (doorOpen) "Aberta" else "Fechada")
        if (sensor.kind == "motion" && motionRaw != null) Reading("Movimento", if (motionDetected) "Detectado" else "Nenhum")
        if (sensor.kind == "temperature_humidity") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (temperature != null) Reading("Temperatura", temperature)
                if (humidity != null) Reading("Umidade", humidity)
            }
        }
        if (isSilent) {
            Text(
                "Este dispositivo não publica nenhum dado — nem na nuvem, nem na rede local. Verifique se ele " +
                    "mostra leitura no app Smart Life; se não mostrar, re-pareie.",
                style = Txt.xs,
                color = Tw.mist400,
            )
        }
        if (battery != null || tamper) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (battery != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Hero.Bolt, null, Modifier.size(14.dp), tint = Tw.mist400)
                        Text(battery, style = Txt.xs, color = Tw.mist400)
                    }
                }
                if (tamper) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Hero.ExclamationTriangle, null, Modifier.size(14.dp), tint = Tw.amber300)
                        Text("Violação", style = Txt.xs, color = Tw.amber300)
                    }
                }
            }
        }
    }
}
