package br.dev.gui.integra.ui.statusplatform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.PollingEffect
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.EmptyState
import br.dev.gui.integra.ui.components.IconBtn
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LocalConfirm
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Skeleton
import br.dev.gui.integra.ui.components.StatusDot
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.trainstatus.SimpleGrid
import br.dev.gui.integra.ui.trainstatus.responsiveColumns
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 4000L

private data class HistoryTarget(val id: String, val name: String)

@Composable
fun StatusPlatformScreen() {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var isFormModalOpen by remember { mutableStateOf(false) }
    var editingPlatform by remember { mutableStateOf<PlatformFormValues?>(null) }
    var historyPlatform by remember { mutableStateOf<HistoryTarget?>(null) }
    var platforms by remember { mutableStateOf<List<PlatformRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun fetchPlatforms(useLoading: Boolean) {
        if (useLoading) isLoading = true
        apiCall { Integra.api<StatusPlatformApi>().list() }
            .onSuccess { platforms = it }
            .onFailure { toast.error("Failed to fetch platforms") }
        isLoading = false
    }

    PollingEffect(POLL_INTERVAL_MS) { fetchPlatforms(false) }

    val reload: () -> Unit = { scope.launch { fetchPlatforms(true) } }
    val openCreateModal = {
        editingPlatform = null
        isFormModalOpen = true
    }

    PlatformFormModal(
        open = isFormModalOpen,
        onClose = { isFormModalOpen = false },
        onSaved = reload,
        platform = editingPlatform,
    )

    PlatformHistoryModal(
        open = historyPlatform != null,
        onClose = { historyPlatform = null },
        platformId = historyPlatform?.id,
        platformName = historyPlatform?.name,
    )

    ScreenColumn(
        "Status Platform",
        actions = { IntegraButton("Add Platform", openCreateModal, icon = Hero.Plus) },
    ) {
        val columns = responsiveColumns()
        when {
            isLoading -> SimpleGrid(List(6) { it }, columns) { CardSkeleton() }
            platforms.isEmpty() -> EmptyState(Hero.ServerStack, "No platforms registered yet") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a platform to start tracking its status here.", style = Txt.sm, color = Tw.mist400)
                    IntegraButton("Add Platform", openCreateModal, icon = Hero.Plus)
                }
            }
            else -> SimpleGrid(platforms, columns, key = { it.id }) { platform ->
                PlatformCard(
                    platform = platform,
                    onDeleted = reload,
                    onEdit = {
                        editingPlatform = PlatformFormValues(platform.id, platform.name, platform.url, platform.type)
                        isFormModalOpen = true
                    },
                    onOpenHistory = { historyPlatform = HistoryTarget(platform.id, platform.name) },
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}

/** `component/Card`: dados da plataforma, ações (editar, excluir, abrir URL) e status. */
@Composable
private fun PlatformCard(platform: PlatformRow, onDeleted: () -> Unit, onEdit: () -> Unit, onOpenHistory: () -> Unit) {
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDeleting by remember { mutableStateOf(false) }

    fun deletePlatform() {
        scope.launch {
            val confirmed = confirm.confirm(
                title = "Delete platform",
                message = "Are you sure you want to delete \"${platform.name}\"? This action cannot be undone.",
                confirmLabel = "Delete",
            )
            if (!confirmed) return@launch
            isDeleting = true
            apiCall { Integra.api<StatusPlatformApi>().delete(platform.id) }
                .onSuccess {
                    toast.success("Platform deleted successfully")
                    onDeleted()
                }
                .onFailure {
                    toast.error("Failed to delete platform")
                    isDeleting = false
                }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .alpha(if (isDeleting) 0.5f else 1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface.card)
            .clickable(enabled = !isDeleting, onClick = onOpenHistory)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Name: ${platform.name}", style = Txt.sm)
                Text("URL: ${platform.url}", style = Txt.sm)
                Text("Type: ${platform.type}", style = Txt.sm)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IconBtn(Hero.Pencil, "Edit", onEdit, enabled = !isDeleting, size = 18.dp)
                IconBtn(Hero.Trash, "Delete", ::deletePlatform, enabled = !isDeleting, size = 18.dp)
                IconBtn(
                    Hero.ArrowTopRightOnSquare, "Open URL", { openUrl(context, platform.url) },
                    enabled = !isDeleting, size = 18.dp,
                )
            }
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusDot(
                    when (platform.status) {
                        "OK" -> Tw.green700
                        "DOWN" -> Tw.red700
                        else -> Tw.gray600
                    },
                    size = 8.dp,
                )
                Text(
                    "Status: " + when (platform.status) {
                        "OK" -> "Operational"
                        "DOWN" -> "Down"
                        else -> "Not checked yet"
                    },
                    style = Txt.sm,
                )
            }
            val problem = platform.problemDescription
            if (platform.status == "DOWN" && !problem.isNullOrEmpty()) {
                Text(problem, style = Txt.xs, color = Tw.red400, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun CardSkeleton() {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Surface.card).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Skeleton(Modifier.width(128.dp).height(14.dp))
                Skeleton(Modifier.width(176.dp).height(14.dp))
                Skeleton(Modifier.width(96.dp).height(14.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { Skeleton(Modifier.size(26.dp).clip(CircleShape)) }
            }
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Skeleton(Modifier.width(112.dp).height(14.dp))
            Skeleton(Modifier.width(144.dp).height(14.dp))
        }
    }
}
