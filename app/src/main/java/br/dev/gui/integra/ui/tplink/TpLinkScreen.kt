package br.dev.gui.integra.ui.tplink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.PollingEffect
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LoadingText
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.Pill
import br.dev.gui.integra.ui.components.SelectField
import br.dev.gui.integra.ui.components.SelectOption
import br.dev.gui.integra.ui.components.SideSheet
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val POLL_MS = 15_000L

/** Controller → roteadores (agentes) → clientes. */
private fun deviceSortOrder(device: TpDevice): Int = when {
    device.isRouter && device.isController -> 0
    device.isRouter -> 1
    else -> 2
}

private fun deviceTypeLabel(device: TpDevice) = when {
    device.isRouter && device.isController -> "Roteador (Controller)"
    device.isRouter -> "Roteador (Agente)"
    else -> "Cliente"
}

/** TP-Link Center (`public/dashboards/tplink`). */
@Composable
fun TpLinkScreen() {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<TpDevice>>(emptyList()) }
    var onlineDevices by remember { mutableStateOf<List<OnlineDevice>>(emptyList()) }
    var routerStatus by remember { mutableStateOf<RouterStatus?>(null) }
    var routerStatusHistory by remember { mutableStateOf<List<RouterStatusHistoryPoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var showNewDeviceForm by remember { mutableStateOf(false) }
    var openDeviceId by remember { mutableStateOf<String?>(null) }

    /** Busca tudo em paralelo; cada resposta que falhar mantém o valor anterior (como o web). */
    suspend fun fetchAll() = coroutineScope {
        val api = Integra.api<TpLinkApi>()
        val devicesRes = async { apiCall { api.devices() } }
        val statusRes = async { apiCall { api.latestRouterStatus() } }
        val checkRes = async { apiCall { api.latestCheck() } }
        val historyRes = async { apiCall { api.routerStatusHistory() } }
        devicesRes.await().onSuccess { devices = it }
        statusRes.await().onSuccess { routerStatus = it }
        checkRes.await().onSuccess { onlineDevices = it.devices.orEmpty() }
        historyRes.await().onSuccess { routerStatusHistory = it.snapshots.orEmpty() }
        isLoading = false
    }

    val refresh: () -> Unit = remember { { scope.launch { fetchAll() } } }

    PollingEffect(POLL_MS) { fetchAll() }

    fun sync() {
        scope.launch {
            isSyncing = true
            val result = apiCall { Integra.api<TpLinkApi>().sync() }
            isSyncing = false
            if (result.isFailure) {
                toast.error("Falha ao sincronizar com o roteador")
                return@launch
            }
            toast.success("Sincronização concluída")
            fetchAll()
        }
    }

    fun restartNetwork() {
        scope.launch {
            val ok = confirm.confirm(
                "Reiniciar rede",
                "Isso vai reiniciar todos os roteadores (agentes e controller). Continuar?",
            )
            if (!ok) return@launch
            val result = apiCall { Integra.api<TpLinkApi>().restartNetwork() }
            if (result.isFailure) {
                toast.error("Falha ao reiniciar a rede")
                return@launch
            }
            toast.success("Reinício disparado")
        }
    }

    fun removeDevice(device: TpDevice) {
        scope.launch {
            if (!confirm.confirm("Confirmar ação", "Remover o dispositivo \"${device.name}\"?")) return@launch
            val result = apiCall { Integra.api<TpLinkApi>().deleteDevice(device.id) }
            if (result.isFailure) {
                toast.error("Falha ao remover dispositivo")
                return@launch
            }
            fetchAll()
        }
    }

    val onlineMacs = remember(onlineDevices) { onlineDevices.mapTo(HashSet()) { it.mac.uppercase() } }
    val sortedDevices = remember(devices) { devices.sortedBy(::deviceSortOrder) }

    ScreenColumn(
        title = "TP-Link Center",
        actions = {
            // Em retrato não cabem os rótulos ao lado do título: ficam só os ícones.
            if (LocalConfiguration.current.screenWidthDp >= 600) {
                IntegraButton("Sincronizar", ::sync, icon = Hero.ArrowPath, loading = isSyncing)
                IntegraButton("Reiniciar rede", ::restartNetwork, icon = Hero.Power, variant = ButtonVariant.Secondary)
            } else {
                IntegraButton(::sync, loading = isSyncing) {
                    if (!isSyncing) Icon(Hero.ArrowPath, "Sincronizar", Modifier.size(16.dp))
                }
                IntegraButton(::restartNetwork, variant = ButtonVariant.Secondary) {
                    Icon(Hero.Power, "Reiniciar rede", Modifier.size(16.dp))
                }
            }
        },
    ) {
        if (isLoading) {
            LoadingText()
        } else {
            RouterStatusPanel(routerStatus)
            if (routerStatusHistory.isNotEmpty()) RouterStatusChart(routerStatusHistory)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Dispositivos registrados (${devices.size})",
                        style = Txt.sm.semibold(),
                        color = Tw.mist300,
                        modifier = Modifier.weight(1f),
                    )
                    IntegraButton("Novo dispositivo", { showNewDeviceForm = true }, icon = Hero.Plus, variant = ButtonVariant.Secondary)
                }
                DevicesTable(
                    devices = sortedDevices,
                    isOnline = { d -> d.interfaces.any { it.mac.uppercase() in onlineMacs } },
                    onOpen = { openDeviceId = it.id },
                    onRemove = ::removeDevice,
                )
            }

            UnregisteredDevices(onlineDevices, devices, refresh)
        }
    }

    NewDeviceForm(open = showNewDeviceForm, onCreated = refresh, onClose = { showNewDeviceForm = false })

    // Guarda o último dispositivo aberto para o drawer não ficar vazio na animação de saída.
    val openDevice = devices.firstOrNull { it.id == openDeviceId }
    var shownDevice by remember { mutableStateOf<TpDevice?>(null) }
    if (openDevice != null) shownDevice = openDevice
    SideSheet(open = openDevice != null, onClose = { openDeviceId = null }, title = shownDevice?.name.orEmpty()) {
        shownDevice?.let { DeviceDrawerContent(it, refresh) }
    }
}

@Composable
private fun DevicesTable(
    devices: List<TpDevice>,
    isOnline: (TpDevice) -> Boolean,
    onOpen: (TpDevice) -> Unit,
    onRemove: (TpDevice) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(PanelShape).background(Surface.card)) {
        val wide = maxWidth >= 560.dp
        Column {
            if (wide) {
                Row(Modifier.fillMaxWidth().rowDivider(true)) {
                    HeaderCell("Nome", 2f)
                    HeaderCell("Marca", 1.6f)
                    HeaderCell("Tipo", 1.4f)
                    HeaderCell("Status", 1.1f)
                    Row(Modifier.weight(0.5f)) {}
                }
            }
            devices.forEachIndexed { index, device ->
                androidx.compose.runtime.key(device.id) {
                    DeviceRow(device, isOnline(device), wide, index < devices.lastIndex, onOpen, onRemove)
                }
            }
            if (devices.isEmpty()) {
                Text("Nenhum dispositivo cadastrado.", style = Txt.sm, color = Tw.mist400, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun StatusPill(online: Boolean) =
    if (online) Pill("Conectado", Tw.green900, Tw.green300) else Pill("Desconectado", Tw.red950, Tw.red300)

@Composable
private fun DeviceRow(
    device: TpDevice,
    online: Boolean,
    wide: Boolean,
    divider: Boolean,
    onOpen: (TpDevice) -> Unit,
    onRemove: (TpDevice) -> Unit,
) {
    val rowModifier = Modifier.fillMaxWidth().rowDivider(divider).clickable { onOpen(device) }
    val trash = @Composable {
        IconBtn(Hero.Trash, "Remover dispositivo", { onRemove(device) }, tint = Tw.red400, size = 16.dp)
    }
    if (wide) {
        val cell = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(2f).then(cell), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Hero.Wifi, null, Modifier.size(16.dp), tint = Tw.mist400)
                Text(device.name, style = Txt.sm)
            }
            Text(device.brand, style = Txt.sm, color = Tw.mist300, modifier = Modifier.weight(1.6f).then(cell))
            Text(deviceTypeLabel(device), style = Txt.sm, modifier = Modifier.weight(1.4f).then(cell))
            Row(Modifier.weight(1.1f).then(cell)) { StatusPill(online) }
            Row(Modifier.weight(0.5f).padding(end = 8.dp), horizontalArrangement = Arrangement.End) { trash() }
        }
    } else {
        // Retrato: linha empilhada (nome + status em cima, marca · tipo embaixo).
        Row(rowModifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Hero.Wifi, null, Modifier.size(16.dp), tint = Tw.mist400)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, style = Txt.sm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${device.brand} · ${deviceTypeLabel(device)}",
                    style = Txt.xs, color = Tw.mist300, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(online)
            trash()
        }
    }
}

/** Modal "Novo dispositivo" (`NewDeviceForm`). */
@Composable
private fun NewDeviceForm(open: Boolean, onCreated: () -> Unit, onClose: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("client") }
    var isController by remember { mutableStateOf(false) }
    var routerPassword by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val typeOptions = remember { listOf(SelectOption("client", "Cliente"), SelectOption("router", "Roteador")) }

    fun submit() {
        if (name.isEmpty() || brand.isEmpty()) {
            toast.error("Preencha nome e marca")
            return
        }
        scope.launch {
            saving = true
            val body = NewDeviceBody(
                name = name,
                brand = brand,
                type = type,
                isController = if (type == "router") isController else null,
                routerPassword = if (type == "router" && routerPassword.isNotEmpty()) routerPassword else null,
            )
            val result = apiCall { Integra.api<TpLinkApi>().createDevice(body) }
            saving = false
            if (result.isFailure) {
                toast.error("Falha ao criar dispositivo")
                return@launch
            }
            toast.success("Dispositivo criado")
            name = ""; brand = ""; routerPassword = ""; isController = false
            onCreated()
            onClose()
        }
    }

    Modal(open = open, onClose = onClose, title = "Novo dispositivo") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(name, { name = it }, label = "Nome")
            TextInput(brand, { brand = it }, label = "Marca")
            SelectField(type, typeOptions, { type = it }, label = "Tipo")
            if (type == "router") {
                TextInput(routerPassword, { routerPassword = it }, label = "Senha de admin do roteador", password = true)
                Row(
                    Modifier.clickable { isController = !isController },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = isController,
                        onCheckedChange = { isController = it },
                        modifier = Modifier.size(20.dp).minimumInteractiveComponentSize(),
                        colors = CheckboxDefaults.colors(checkedColor = Tw.mist500, uncheckedColor = Tw.mist400, checkmarkColor = Tw.gray100),
                    )
                    Text("Este roteador é o controller", style = Txt.xs, color = Tw.mist300)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            IntegraButton("Cancelar", onClose, variant = ButtonVariant.Secondary)
            IntegraButton("Criar", ::submit, loading = saving)
        }
    }
}
