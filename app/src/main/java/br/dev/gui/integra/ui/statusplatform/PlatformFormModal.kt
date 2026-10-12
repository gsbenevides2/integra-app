package br.dev.gui.integra.ui.statusplatform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Modal
import br.dev.gui.integra.ui.components.SelectField
import br.dev.gui.integra.ui.components.SelectOption
import br.dev.gui.integra.ui.components.TextInput
import kotlinx.coroutines.launch

@Immutable
data class PlatformFormValues(val id: String, val name: String, val url: String, val type: String)

private val TYPE_OPTIONS = PLATFORMS.map { SelectOption(it, it) }

/** `component/PlatformFormModal`: cria (POST) ou edita (PATCH) uma plataforma. */
@Composable
fun PlatformFormModal(open: Boolean, onClose: () -> Unit, onSaved: () -> Unit, platform: PlatformFormValues?) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val isEditing = platform != null
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    // Cada abertura começa com os valores da plataforma (ou vazia), como o `defaultValue` do form.
    LaunchedEffect(open, platform) {
        if (open) {
            name = platform?.name.orEmpty()
            url = platform?.url.orEmpty()
            type = platform?.type.orEmpty()
        }
    }

    fun save() {
        if (name.isEmpty()) return toast.error("Missing name!")
        if (type.isEmpty()) return toast.error("Missing type")
        if (url.isEmpty()) return toast.error("Missing url")
        if (type !in PLATFORMS) return toast.error("Invalid type")
        val body = PlatformBody(name = name, url = url, type = type)
        isSaving = true
        scope.launch {
            val api = Integra.api<StatusPlatformApi>()
            apiCall { if (platform != null) api.update(platform.id, body) else api.create(body) }
                .onSuccess {
                    toast.success(if (isEditing) "Platform updated successfully" else "Saved successfully")
                    onSaved()
                    onClose()
                }
                .onFailure { toast.error(if (isEditing) "Failed to update" else "Failed to save") }
            isSaving = false
        }
    }

    Modal(open, onClose, if (isEditing) "Edit Platform" else "Add Platform") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(name, { name = it }, label = "Name", placeholder = "Type the platform name")
            TextInput(url, { url = it }, label = "Url", placeholder = "Type the platform url")
            SelectField(
                value = type,
                options = TYPE_OPTIONS,
                onChange = { type = it },
                label = "Type",
                placeholder = "Select the platform type",
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                IntegraButton("Cancel", onClose, variant = ButtonVariant.Secondary, enabled = !isSaving)
                IntegraButton("Save", ::save, loading = isSaving)
            }
        }
    }
}
