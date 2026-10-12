package br.dev.gui.integra.ui.tuya

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.dev.gui.integra.data.apiCall
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Estado do painel "Casa" (o `TuyaDashboard` do web). Sobrevive à rotação; o polling de 5 s é
 * disparado pela tela com `PollingEffect`, então para quando a tela desliga.
 */
class TuyaViewModel : ViewModel() {
    var devices by mutableStateOf<List<Device>>(emptyList())
        private set
    var sensors by mutableStateOf<List<Sensor>>(emptyList())
        private set
    var presets by mutableStateOf<List<Preset>>(emptyList())
        private set
    var cameras by mutableStateOf<List<String>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var busyDeviceIds by mutableStateOf<Set<String>>(emptySet())
        private set

    // Quem responde a um comando é o próprio dispositivo, então um polling que chegue no meio
    // sobrescreveria o valor otimista com uma leitura feita antes da mudança.
    private val inFlight = mutableSetOf<String>()

    init {
        // Carregadas uma vez, fora do polling, para os streams ao vivo nunca serem recriados.
        viewModelScope.launch {
            apiCall { tuyaApi.cameras() }.onSuccess { cameras = it }
        }
    }

    suspend fun fetchAll() = coroutineScope {
        val devicesRes = async { apiCall { tuyaApi.devices() } }
        val sensorsRes = async { apiCall { tuyaApi.sensors() } }
        val presetsRes = async { apiCall { tuyaApi.presets() } }
        devicesRes.await().onSuccess { fresh ->
            val current = devices
            devices = fresh.map { device ->
                if (device.id in inFlight) current.firstOrNull { it.id == device.id } ?: device else device
            }
        }
        sensorsRes.await().onSuccess { sensors = it }
        presetsRes.await().onSuccess { presets = it }
        isLoading = false
    }

    fun refresh() {
        viewModelScope.launch { fetchAll() }
    }

    private fun applyState(deviceId: String, transform: (DeviceState) -> DeviceState) {
        devices = devices.map { if (it.id == deviceId) it.copy(state = transform(it.state)) else it }
    }

    /** Comando com atualização otimista; volta ao estado anterior e avisa se falhar. */
    fun sendCommand(device: Device, command: DeviceCommand, onError: (String) -> Unit) {
        val previous = devices.firstOrNull { it.id == device.id }?.state ?: device.state
        applyState(device.id) { it.merge(command) }
        busyDeviceIds = busyDeviceIds + device.id
        inFlight += device.id
        viewModelScope.launch {
            val result = apiCall { tuyaApi.command(device.id, command) }
            busyDeviceIds = busyDeviceIds - device.id
            inFlight -= device.id
            result
                .onSuccess { state -> applyState(device.id) { state } }
                .onFailure {
                    applyState(device.id) { previous }
                    onError("Falha ao comandar \"${device.name}\"")
                }
        }
    }
}

/** O `{ ...state, ...command }` do web. Os canais são mesclados para os outros não sumirem enquanto o comando vai. */
private fun DeviceState.merge(command: DeviceCommand) = copy(
    power = command.power ?: power,
    brightness = command.brightness ?: brightness,
    colorTemp = command.colorTemp ?: colorTemp,
    colorHex = command.colorHex ?: colorHex,
    workMode = command.workMode ?: workMode,
    channels = command.channels?.let { (channels ?: emptyMap()) + it } ?: channels,
)
