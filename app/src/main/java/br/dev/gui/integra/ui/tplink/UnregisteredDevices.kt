package br.dev.gui.integra.ui.tplink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.SelectField
import br.dev.gui.integra.ui.components.SelectOption
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.semibold
import kotlinx.coroutines.launch

private val Mono = Txt.xs.copy(fontFamily = FontFamily.Monospace)

/** Linha divisória `border-b border-gray-700` (sem a última, `last:border-0`). */
internal fun Modifier.rowDivider(show: Boolean) =
    if (!show) this else drawBehind {
        drawLine(Surface.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }

/** Cabeçalho de tabela `text-xs text-mist-400`. */
@Composable
internal fun RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, style = Txt.xs, color = Tw.mist400, modifier = Modifier.weight(weight).padding(horizontal = 12.dp, vertical = 8.dp))

@Composable
private fun MacLine(mac: String?) = Text(
    buildAnnotatedString {
        append("MAC ")
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(mac.orEmpty()) }
    },
    style = Txt.xs,
    color = Tw.mist400,
)

@Composable
private fun ModalActions(confirmLabel: String, saving: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        IntegraButton("Cancelar", onCancel, variant = ButtonVariant.Secondary)
        IntegraButton(confirmLabel, onConfirm, loading = saving)
    }
}

/**
 * `UnregisteredDevices`: MACs vistos na última verificação que não pertencem a nenhum dispositivo
 * cadastrado, com "Vincular" (a um dispositivo existente) e "Criar" (novo cliente + interface).
 */
@Composable
fun UnregisteredDevices(onlineDevices: List<OnlineDevice>, devices: List<TpDevice>, onChanged: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var linkTarget by remember { mutableStateOf<OnlineDevice?>(null) }
    var createTarget by remember { mutableStateOf<OnlineDevice?>(null) }
    // Mantém o último alvo para o texto não sumir durante a animação de saída do modal.
    var lastMac by remember { mutableStateOf("") }
    var interfaceName by remember { mutableStateOf("") }
    var selectedDeviceId by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var deviceBrand by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val unregistered = remember(onlineDevices, devices) {
        val registered = devices.flatMapTo(HashSet()) { d -> d.interfaces.map { it.mac.uppercase() } }
        onlineDevices.filter { it.mac.uppercase() !in registered }
    }
    val deviceOptions = remember(devices) { devices.map { SelectOption(it.id, "${it.name} (${it.brand})") } }

    fun openLink(device: OnlineDevice) {
        linkTarget = device; lastMac = device.mac
        selectedDeviceId = ""; interfaceName = ""; ip = device.ip
    }

    fun openCreate(device: OnlineDevice) {
        createTarget = device; lastMac = device.mac
        deviceName = if (device.name != "Unknown") device.name else ""
        deviceBrand = if (device.vendor != "Unknown") device.vendor else ""
        interfaceName = ""; ip = device.ip
    }

    fun submitLink() {
        val target = linkTarget
        if (target == null || selectedDeviceId.isEmpty() || interfaceName.isEmpty() || ip.isEmpty()) {
            toast.error("Selecione o dispositivo e informe nome da interface e IP")
            return
        }
        scope.launch {
            saving = true
            val result = apiCall {
                Integra.api<TpLinkApi>().createInterface(selectedDeviceId, NewInterfaceBody(interfaceName, target.mac, ip))
            }
            saving = false
            if (result.isFailure) {
                toast.error("Falha ao vincular interface")
                return@launch
            }
            toast.success("Interface vinculada")
            linkTarget = null
            onChanged()
        }
    }

    fun submitCreate() {
        val target = createTarget
        if (target == null || deviceName.isEmpty() || deviceBrand.isEmpty() || interfaceName.isEmpty() || ip.isEmpty()) {
            toast.error("Preencha nome, marca, nome da interface e IP")
            return
        }
        scope.launch {
            saving = true
            val api = Integra.api<TpLinkApi>()
            val created = apiCall { api.createDevice(NewDeviceBody(deviceName, deviceBrand, "client")) }.getOrNull()
            if (created == null) {
                saving = false
                toast.error("Falha ao criar dispositivo")
                return@launch
            }
            val result = apiCall { api.createInterface(created.id, NewInterfaceBody(interfaceName, target.mac, ip)) }
            saving = false
            if (result.isFailure) {
                toast.error("Dispositivo criado, mas falhou ao adicionar a interface")
                return@launch
            }
            toast.success("Dispositivo criado e vinculado")
            createTarget = null
            onChanged()
        }
    }

    // Modais ficam na composição mesmo sem linhas, para a animação de saída após vincular o último.
    Modal(open = linkTarget != null, onClose = { linkTarget = null }, title = "Vincular a dispositivo existente") {
        FormFields {
            MacLine(lastMac)
            SelectField(
                selectedDeviceId, deviceOptions, { selectedDeviceId = it },
                label = "Dispositivo", placeholder = "Selecione um dispositivo",
            )
            TextInput(interfaceName, { interfaceName = it }, label = "Nome da interface", placeholder = "Ex: Wi-Fi, Ethernet...")
            TextInput(ip, { ip = it }, label = "IP", placeholder = "Ex: 192.168.0.10")
        }
        ModalActions("Vincular", saving, { linkTarget = null }, ::submitLink)
    }

    Modal(open = createTarget != null, onClose = { createTarget = null }, title = "Criar novo dispositivo") {
        FormFields {
            MacLine(lastMac)
            TextInput(deviceName, { deviceName = it }, label = "Nome")
            TextInput(deviceBrand, { deviceBrand = it }, label = "Marca")
            TextInput(interfaceName, { interfaceName = it }, label = "Nome da interface", placeholder = "Ex: Wi-Fi, Ethernet...")
            TextInput(ip, { ip = it }, label = "IP", placeholder = "Ex: 192.168.0.10")
        }
        ModalActions("Criar", saving, { createTarget = null }, ::submitCreate)
    }

    if (unregistered.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dispositivos não registrados (${unregistered.size})", style = Txt.sm.semibold(), color = Tw.mist300)
        BoxWithConstraints(Modifier.fillMaxWidth().clip(PanelShape).background(Surface.card)) {
            val wide = maxWidth >= 720.dp
            Column {
                if (wide) {
                    Row(Modifier.fillMaxWidth().rowDivider(true)) {
                        HeaderCell("Nome", 1.3f)
                        HeaderCell("Fabricante", 1.3f)
                        HeaderCell("MAC", 1.2f)
                        HeaderCell("IP", 1f)
                        HeaderCell("Roteador", 1.2f)
                        Row(Modifier.weight(1.6f)) {}
                    }
                }
                unregistered.forEachIndexed { index, device ->
                    androidx.compose.runtime.key(device.mac) {
                        val divider = index < unregistered.lastIndex
                        if (wide) WideRow(device, divider, ::openLink, ::openCreate)
                        else StackedRow(device, divider, ::openLink, ::openCreate)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormFields(content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
private fun RowActions(device: OnlineDevice, onLink: (OnlineDevice) -> Unit, onCreate: (OnlineDevice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IntegraButton("Vincular", { onLink(device) }, icon = Hero.Link, variant = ButtonVariant.Secondary)
        IntegraButton("Criar", { onCreate(device) }, icon = Hero.Plus, variant = ButtonVariant.Secondary)
    }
}

@Composable
private fun WideRow(device: OnlineDevice, divider: Boolean, onLink: (OnlineDevice) -> Unit, onCreate: (OnlineDevice) -> Unit) {
    val cell = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    Row(Modifier.fillMaxWidth().rowDivider(divider), verticalAlignment = Alignment.CenterVertically) {
        Text(device.name, style = Txt.sm, modifier = Modifier.weight(1.3f).then(cell))
        Text(device.vendor, style = Txt.sm, color = Tw.mist300, modifier = Modifier.weight(1.3f).then(cell))
        Text(device.mac, style = Mono, modifier = Modifier.weight(1.2f).then(cell))
        Text(device.ip.ifEmpty { "-" }, style = Mono, modifier = Modifier.weight(1f).then(cell))
        Text(device.routerInterface.ifEmpty { "-" }, style = Txt.xs, color = Tw.mist400, modifier = Modifier.weight(1.2f).then(cell))
        Row(Modifier.weight(1.6f).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            RowActions(device, onLink, onCreate)
        }
    }
}

/** Versão empilhada da linha para telas estreitas (retrato). */
@Composable
private fun StackedRow(device: OnlineDevice, divider: Boolean, onLink: (OnlineDevice) -> Unit, onCreate: (OnlineDevice) -> Unit) {
    Column(
        Modifier.fillMaxWidth().rowDivider(divider).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(device.name, style = Txt.sm, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(device.vendor, style = Txt.xs, color = Tw.mist300, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(device.mac, style = Mono)
            Text(device.ip.ifEmpty { "-" }, style = Mono)
        }
        Text(device.routerInterface.ifEmpty { "-" }, style = Txt.xs, color = Tw.mist400)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            RowActions(device, onLink, onCreate)
        }
    }
}
