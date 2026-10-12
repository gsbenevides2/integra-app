package br.dev.gui.integra.ui.tuya

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSlider
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.RingPicker
import br.dev.gui.integra.ui.components.SelectField
import br.dev.gui.integra.ui.components.SelectOption
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.theme.Hero
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Rascunho do formulário de modo (`PresetDraft`). */
@Immutable
data class PresetDraft(
    val name: String = "",
    val power: Boolean = true,
    val workMode: String = WORK_MODE_COLOUR,
    val brightness: Int = 100,
    val colorTemp: Int = 50,
    val colorHex: String = "#ff0000",
)

private val EMPTY_PRESET_DRAFT = PresetDraft()

fun presetDraftFromPreset(preset: Preset) = PresetDraft(
    name = preset.name,
    power = preset.power,
    workMode = if (preset.workMode == WORK_MODE_WHITE) WORK_MODE_WHITE else WORK_MODE_COLOUR,
    brightness = preset.brightness ?: EMPTY_PRESET_DRAFT.brightness,
    colorTemp = preset.colorTemp ?: EMPTY_PRESET_DRAFT.colorTemp,
    colorHex = preset.colorHex ?: EMPTY_PRESET_DRAFT.colorHex,
)

/** Corpo do POST/PUT, com `null` explícito nos campos que não valem para o modo escolhido. */
fun presetDraftToBody(draft: PresetDraft): JsonObject {
    val colour = draft.workMode == WORK_MODE_COLOUR
    return buildJsonObject {
        put("name", JsonPrimitive(draft.name))
        put("power", JsonPrimitive(draft.power))
        put("workMode", JsonPrimitive(draft.workMode))
        put("brightness", if (colour) JsonNull else JsonPrimitive(draft.brightness))
        put("colorTemp", if (colour) JsonNull else JsonPrimitive(draft.colorTemp))
        put("colorHex", if (colour) JsonPrimitive(draft.colorHex) else JsonNull)
    }
}

/** `PresetForm`: nome, Branco/Cor, anel, brilho/saturação e "liga ao aplicar". */
@Composable
fun PresetForm(draft: PresetDraft, onChange: (PresetDraft) -> Unit) {
    val hsv = hsvFromHex(draft.colorHex)
    val showColour = draft.workMode == WORK_MODE_COLOUR
    fun commitColor(next: Hsv) = onChange(draft.copy(colorHex = hexFromHsv(next)))

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextInput(draft.name, { onChange(draft.copy(name = it)) }, Modifier.fillMaxWidth(), label = "Nome")
        ModeTabs(showColour) { colour -> onChange(draft.copy(workMode = if (colour) WORK_MODE_COLOUR else WORK_MODE_WHITE)) }

        if (showColour) {
            RingPicker(
                value = (hsv.h / 360).toFloat(),
                onCommit = { commitColor(hsv.copy(h = it * 360.0)) },
                colors = HUE_STOPS,
                knobColor = hexColor(hueHex(hsv)),
                modifier = Modifier.semantics { contentDescription = "Matiz" },
            )
        } else {
            RingPicker(
                value = draft.colorTemp / 100f,
                onCommit = { onChange(draft.copy(colorTemp = jsRound(it * 100.0))) },
                colors = WHITE_STOP_COLORS,
                startAngle = WHITE_START,
                sweep = WHITE_SWEEP,
                knobColor = hexColor(whiteHex(draft.colorTemp)),
                modifier = Modifier.semantics { contentDescription = "Temperatura de cor" },
            )
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntegraSlider(
                label = "Brilho",
                min = 1,
                max = 100,
                value = if (showColour) jsRound(hsv.v * 100) else draft.brightness,
                track = Brush.horizontalGradient(
                    listOf(Color.Black, hexColor(if (showColour) hueHex(hsv) else whiteHex(draft.colorTemp))),
                ),
                onCommit = { value ->
                    if (showColour) commitColor(hsv.copy(v = value / 100.0))
                    else onChange(draft.copy(brightness = value))
                },
            )
            if (showColour) {
                IntegraSlider(
                    label = "Saturação",
                    min = 0,
                    max = 100,
                    value = jsRound(hsv.s * 100),
                    track = Brush.horizontalGradient(listOf(Color.White, hexColor(hueHex(hsv.copy(s = 1.0))))),
                    onCommit = { commitColor(hsv.copy(s = it / 100.0)) },
                )
            }
        }

        IntegraSwitch(
            checked = draft.power,
            onChange = { onChange(draft.copy(power = it)) },
            label = if (draft.power) "Liga a lâmpada ao aplicar" else "Mantém desligada ao aplicar",
        )
    }
}

/** `PresetDrawerContent`: edita e remove um modo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetDrawerContent(preset: Preset, onChanged: () -> Unit, onDeleted: () -> Unit) {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    // Só volta ao valor do servidor quando o modo de fato mudou (o polling recria o objeto).
    var draft by remember(preset) { mutableStateOf(presetDraftFromPreset(preset)) }
    var isSaving by remember { mutableStateOf(false) }

    PresetForm(draft) { draft = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IntegraButton("Salvar", {
            scope.launch {
                isSaving = true
                val result = apiCall { tuyaApi.updatePreset(preset.id, presetDraftToBody(draft)) }
                isSaving = false
                result
                    .onSuccess { toast.success("Modo salvo"); onChanged() }
                    .onFailure { toast.error("Falha ao salvar o modo") }
            }
        }, loading = isSaving)
        IntegraButton("Remover", {
            scope.launch {
                val ok = confirm.confirm(title = "Remover modo", message = "Remover o modo \"${preset.name}\"?")
                if (!ok) return@launch
                apiCall { tuyaApi.deletePreset(preset.id) }
                    .onSuccess { toast.success("Modo removido"); onDeleted() }
                    .onFailure { toast.error("Falha ao remover o modo") }
            }
        }, icon = Hero.Trash, variant = ButtonVariant.Secondary)
    }
}

/** Modal "Novo modo". */
@Composable
fun NewPresetForm(isOpen: Boolean, onCreated: () -> Unit, onClose: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(EMPTY_PRESET_DRAFT) }
    var isSaving by remember { mutableStateOf(false) }

    Modal(isOpen, onClose, "Novo modo") {
        PresetForm(draft) { draft = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            IntegraButton("Cancelar", onClose, variant = ButtonVariant.Secondary)
            IntegraButton("Cadastrar", {
                if (draft.name.isEmpty()) {
                    toast.error("Dê um nome ao modo")
                    return@IntegraButton
                }
                scope.launch {
                    isSaving = true
                    val result = apiCall { tuyaApi.createPreset(presetDraftToBody(draft)) }
                    isSaving = false
                    result
                        .onSuccess {
                            toast.success("Modo cadastrado")
                            draft = EMPTY_PRESET_DRAFT
                            onCreated()
                            onClose()
                        }
                        .onFailure { toast.error("Falha ao cadastrar o modo") }
                }
            }, loading = isSaving)
        }
    }
}

/** `ApplyPresetModal`: escolhe a lâmpada e aplica o modo. */
@Composable
fun ApplyPresetModal(preset: Preset?, devices: List<Device>, onClose: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    // Mantém o último modo para o título não sumir durante a animação de saída.
    val shown = rememberLast(preset)
    var deviceId by remember { mutableStateOf("") }
    var isApplying by remember { mutableStateOf(false) }
    val deviceIds = devices.map { it.id }

    LaunchedEffect(preset?.id, deviceIds) { deviceId = devices.firstOrNull()?.id ?: "" }

    Modal(preset != null, onClose, "Aplicar \"${shown?.name ?: ""}\"") {
        SelectField(
            value = deviceId,
            options = devices.map { SelectOption(it.id, it.name) },
            onChange = { deviceId = it },
            label = "Lâmpada",
            placeholder = if (devices.isEmpty()) "Nenhuma lâmpada cadastrada" else null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            IntegraButton("Cancelar", onClose, variant = ButtonVariant.Secondary)
            IntegraButton("Aplicar", {
                val target = preset ?: return@IntegraButton
                if (deviceId.isEmpty()) return@IntegraButton
                scope.launch {
                    isApplying = true
                    val result = apiCall { tuyaApi.applyPreset(target.id, ApplyPresetBody(deviceId)) }
                    isApplying = false
                    result
                        .onSuccess { toast.success("\"${target.name}\" aplicado"); onClose() }
                        .onFailure { toast.error("Falha ao aplicar \"${target.name}\"") }
                }
            }, loading = isApplying, enabled = deviceId.isNotEmpty())
        }
    }
}
