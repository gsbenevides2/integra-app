package br.dev.gui.integra.ui.tuya

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.dev.gui.integra.data.PollingEffect
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LoadingText
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.SelectField
import br.dev.gui.integra.ui.components.SelectOption
import br.dev.gui.integra.ui.components.SideSheet
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 5_000L

/** Painel "Casa" (public/dashboards/tuya): câmeras, lâmpadas, modos, sensores e interruptores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuyaScreen() {
    val vm: TuyaViewModel = viewModel()
    val toast = LocalToast.current
    PollingEffect(POLL_INTERVAL_MS) { vm.fetchAll() }

    var openDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var openSensorId by rememberSaveable { mutableStateOf<String?>(null) }
    var openPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var applyPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var showHiddenDevices by rememberSaveable { mutableStateOf(false) }
    var showNewDeviceForm by rememberSaveable { mutableStateOf(false) }
    var showNewSensorForm by rememberSaveable { mutableStateOf(false) }
    var showNewPresetForm by rememberSaveable { mutableStateOf(false) }

    val config = LocalConfiguration.current
    // O cabeçalho do web quebra a linha quando os botões não cabem ao lado do título.
    val wideHeader = config.screenWidthDp >= 640
    // `lg:grid-cols-2` (≥ 1024 px).
    val large = config.screenWidthDp >= 1024
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val onError: (String) -> Unit = remember(toast) { { message -> toast.error(message) } }
    val refresh: () -> Unit = remember(vm) { { vm.refresh() } }

    val devices = vm.devices
    val sensors = vm.sensors
    val presets = vm.presets
    val busy = vm.busyDeviceIds

    val visibleDevices = remember(devices) { devices.filter { !it.hidden } }
    val hiddenDevices = remember(devices) { devices.filter { it.hidden } }
    val shownDevices = if (showHiddenDevices) devices else visibleDevices
    val visibleLamps = remember(visibleDevices) { visibleDevices.filter { it.isLamp } }
    val firstOnlineLamp = visibleLamps.firstOrNull { it.state.online } ?: visibleLamps.firstOrNull()
    val visibleSwitches = remember(visibleDevices) { visibleDevices.filter { it.isSwitch } }
    val shownLamps = remember(shownDevices) { shownDevices.filter { it.isLamp } }
    val shownSwitches = remember(shownDevices) { shownDevices.filter { it.isSwitch } }
    val visibleSensors = remember(sensors) { sensors.filter { !it.hidden } }
    val hiddenSensors = remember(sensors) { sensors.filter { it.hidden } }
    val shownSensors = if (showHidden) sensors else visibleSensors

    val openDevice = devices.firstOrNull { it.id == openDeviceId }
    val openSensor = sensors.firstOrNull { it.id == openSensorId }
    val openPreset = presets.firstOrNull { it.id == openPresetId }
    val applyPreset = presets.firstOrNull { it.id == applyPresetId }

    val headerButtons: @Composable () -> Unit = {
        IntegraButton("Novo modo", { showNewPresetForm = true }, icon = Hero.Plus, variant = ButtonVariant.Secondary)
        IntegraButton("Novo sensor", { showNewSensorForm = true }, icon = Hero.Plus, variant = ButtonVariant.Secondary)
        IntegraButton("Novo dispositivo", { showNewDeviceForm = true }, icon = Hero.Plus, variant = ButtonVariant.Secondary)
    }

    ScreenColumn("Casa", actions = { if (wideHeader) headerButtons() }) {
        if (!wideHeader) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                headerButtons()
            }
        }

        NewDeviceForm(showNewDeviceForm, onCreated = refresh, onClose = { showNewDeviceForm = false })
        NewSensorForm(showNewSensorForm, onCreated = refresh, onClose = { showNewSensorForm = false })
        NewPresetForm(showNewPresetForm, onCreated = refresh, onClose = { showNewPresetForm = false })

        if (vm.isLoading) {
            LoadingText()
        } else {
            val cameras = vm.cameras
            if (cameras.isNotEmpty()) {
                TuyaSection {
                    SubHeading("Câmeras (${cameras.size})")
                    // `grid-cols-[repeat(auto-fill,minmax(min(100%,480px),1fr))] max-lg:landscape:grid-cols-2`
                    AdaptiveGrid(minItemWidth = 480.dp, columns = if (landscape && !large) 2 else null) {
                        for (name in cameras) key(name) { CameraCard(name) }
                    }
                }
            }

            val lampsSection: @Composable () -> Unit = {
                TuyaSection {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SubHeading("Iluminação (${visibleLamps.size})", Modifier.weight(1f))
                        if (hiddenDevices.isNotEmpty()) {
                            LinkButton(
                                if (showHiddenDevices) "Esconder ocultas" else "Mostrar ocultas (${hiddenDevices.size})",
                            ) { showHiddenDevices = !showHiddenDevices }
                        }
                    }
                    if (shownLamps.isEmpty()) {
                        EmptyText(
                            if (hiddenDevices.isNotEmpty()) "Todas as lâmpadas estão ocultas."
                            else "Nenhuma lâmpada ainda. Cadastre uma pelo device ID da Tuya.",
                        )
                    } else {
                        AdaptiveGrid {
                            for (device in shownLamps) key(device.id) {
                                DeviceCard(
                                    device = device,
                                    isBusy = device.id in busy,
                                    onCommand = { vm.sendCommand(device, it, onError) },
                                    onOpen = { openDeviceId = device.id },
                                )
                            }
                        }
                    }
                }
            }
            val presetsSection: @Composable () -> Unit = {
                TuyaSection {
                    SubHeading("Modos (${presets.size})")
                    if (presets.isEmpty()) {
                        EmptyText("Nenhum modo ainda. Cadastre um jeito de deixar a lâmpada para reaplicar quando quiser.")
                    } else {
                        AdaptiveGrid {
                            for (preset in presets) key(preset.id) {
                                val lamp = firstOnlineLamp
                                PresetCard(
                                    preset = preset,
                                    onOpen = { openPresetId = preset.id },
                                    onApply = { applyPresetId = preset.id },
                                    firstOnlineDeviceName = lamp?.name,
                                    onQuickApply = lamp?.let {
                                        {
                                            vm.sendCommand(
                                                lamp,
                                                DeviceCommand(
                                                    power = preset.power,
                                                    brightness = preset.brightness,
                                                    colorTemp = preset.colorTemp,
                                                    colorHex = preset.colorHex,
                                                    workMode = preset.workMode,
                                                ),
                                                onError,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            val sensorsSection: @Composable () -> Unit = {
                TuyaSection {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SubHeading("Sensores (${visibleSensors.size})", Modifier.weight(1f))
                        if (hiddenSensors.isNotEmpty()) {
                            LinkButton(
                                if (showHidden) "Esconder ocultos" else "Mostrar ocultos (${hiddenSensors.size})",
                            ) { showHidden = !showHidden }
                        }
                    }
                    if (shownSensors.isEmpty()) {
                        EmptyText(
                            if (hiddenSensors.isNotEmpty()) "Todos os sensores estão ocultos."
                            else "Nenhum sensor ainda. Cadastre um pelo device ID da Tuya.",
                        )
                    } else {
                        AdaptiveGrid {
                            for (sensor in shownSensors) key(sensor.id) {
                                SensorCard(sensor, onOpen = { openSensorId = sensor.id })
                            }
                        }
                    }
                }
            }
            val switchesSection: @Composable () -> Unit = {
                TuyaSection {
                    SubHeading("Interruptores (${visibleSwitches.size})")
                    if (shownSwitches.isEmpty()) {
                        EmptyText("Nenhum interruptor ainda. Cadastre um pelo device ID da Tuya.")
                    } else {
                        AdaptiveGrid {
                            for (device in shownSwitches) key(device.id) {
                                DeviceCard(
                                    device = device,
                                    isBusy = device.id in busy,
                                    onCommand = { vm.sendCommand(device, it, onError) },
                                    onOpen = { openDeviceId = device.id },
                                )
                            }
                        }
                    }
                }
            }

            if (large) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        lampsSection()
                        presetsSection()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        sensorsSection()
                        switchesSection()
                    }
                }
            } else {
                lampsSection()
                presetsSection()
                sensorsSection()
                switchesSection()
            }
        }
    }

    val shownDevice = rememberLast(openDevice)
    SideSheet(open = openDevice != null, onClose = { openDeviceId = null }, title = shownDevice?.name ?: "") {
        shownDevice?.let { device ->
            key(device.id) {
                DeviceDrawerContent(
                    device = device,
                    isBusy = device.id in busy,
                    onCommand = { vm.sendCommand(device, it, onError) },
                    onChanged = refresh,
                    onDeleted = { openDeviceId = null; vm.refresh() },
                )
            }
        }
    }
    val shownSensor = rememberLast(openSensor)
    SideSheet(open = openSensor != null, onClose = { openSensorId = null }, title = shownSensor?.name ?: "") {
        shownSensor?.let { sensor -> key(sensor.id) { SensorDrawerContent(sensor, onChanged = refresh) } }
    }
    val shownPreset = rememberLast(openPreset)
    SideSheet(open = openPreset != null, onClose = { openPresetId = null }, title = shownPreset?.name ?: "") {
        shownPreset?.let { preset ->
            key(preset.id) {
                PresetDrawerContent(
                    preset = preset,
                    onChanged = refresh,
                    onDeleted = { openPresetId = null; vm.refresh() },
                )
            }
        }
    }
    ApplyPresetModal(preset = applyPreset, devices = visibleLamps, onClose = { applyPresetId = null })
}

@Composable
private fun EmptyText(text: String) = Text(text, style = Txt.sm, color = Tw.mist400)

/** Modal "Novo dispositivo". */
@Composable
private fun NewDeviceForm(isOpen: Boolean, onCreated: () -> Unit, onClose: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var tuyaDeviceId by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("lamp") }
    var channelCount by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    Modal(isOpen, onClose, "Novo dispositivo") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(name, { name = it }, label = "Nome")
            TextInput(tuyaDeviceId, { tuyaDeviceId = it }, label = "Device ID (Tuya)")
            SelectField(
                value = kind,
                options = listOf(SelectOption("lamp", "Lâmpada"), SelectOption("switch", "Interruptor")),
                onChange = { kind = it },
                label = "Tipo",
            )
            if (kind == "switch") {
                TextInput(channelCount, { channelCount = it }, label = "Quantidade de canais", keyboardType = KeyboardType.Number)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            IntegraButton("Cancelar", onClose, variant = ButtonVariant.Secondary)
            IntegraButton("Cadastrar", {
                if (name.isEmpty() || tuyaDeviceId.isEmpty()) {
                    toast.error("Preencha nome e device ID")
                    return@IntegraButton
                }
                scope.launch {
                    isSaving = true
                    val body = NewDeviceBody(
                        name = name,
                        tuyaDeviceId = tuyaDeviceId,
                        kind = kind,
                        channelCount = channelCount.takeIf { it.isNotEmpty() }?.trim()?.toIntOrNull(),
                    )
                    val result = apiCall { tuyaApi.createDevice(body) }
                    isSaving = false
                    result
                        .onSuccess {
                            toast.success("Dispositivo cadastrado")
                            name = ""; tuyaDeviceId = ""; kind = "lamp"; channelCount = ""
                            onCreated()
                            onClose()
                        }
                        .onFailure { toast.error("Falha ao cadastrar o dispositivo") }
                }
            }, loading = isSaving)
        }
    }
}

/** Modal "Novo sensor". */
@Composable
private fun NewSensorForm(isOpen: Boolean, onCreated: () -> Unit, onClose: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var tuyaDeviceId by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    Modal(isOpen, onClose, "Novo sensor") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(name, { name = it }, label = "Nome")
            TextInput(tuyaDeviceId, { tuyaDeviceId = it }, label = "Device ID (Tuya)")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            IntegraButton("Cancelar", onClose, variant = ButtonVariant.Secondary)
            IntegraButton("Cadastrar", {
                if (name.isEmpty() || tuyaDeviceId.isEmpty()) {
                    toast.error("Preencha nome e device ID")
                    return@IntegraButton
                }
                scope.launch {
                    isSaving = true
                    val result = apiCall { tuyaApi.createSensor(NewSensorBody(name, tuyaDeviceId)) }
                    isSaving = false
                    result
                        .onSuccess {
                            toast.success("Sensor cadastrado")
                            name = ""; tuyaDeviceId = ""
                            onCreated()
                            onClose()
                        }
                        .onFailure { toast.error("Falha ao cadastrar o sensor") }
                }
            }, loading = isSaving)
        }
    }
}
