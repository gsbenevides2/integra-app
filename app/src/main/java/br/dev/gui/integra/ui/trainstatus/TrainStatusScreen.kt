package br.dev.gui.integra.ui.trainstatus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.PollingEffect
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.EmptyState
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.components.Skeleton
import br.dev.gui.integra.ui.components.StatusDot
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt

private const val POLL_INTERVAL_MS = 4000L

@Composable
fun TrainStatusScreen() {
    val toast = LocalToast.current
    var historyLineCode by remember { mutableStateOf<Int?>(null) }
    var lines by remember { mutableStateOf<List<TrainLineRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    PollingEffect(POLL_INTERVAL_MS) {
        apiCall { Integra.api<TrainStatusApi>().lines() }
            .onSuccess { lines = it }
            .onFailure { toast.error("Failed to fetch train lines") }
        isLoading = false
    }

    TrainHistoryModal(
        open = historyLineCode != null,
        onClose = { historyLineCode = null },
        lineCode = historyLineCode,
    )

    ScreenColumn("Train Status") {
        val columns = responsiveColumns()
        when {
            isLoading -> SimpleGrid(List(6) { it }, columns) { CardSkeleton() }
            lines.isEmpty() -> EmptyState(Hero.Truck, "No train lines tracked yet") {
                Text("Data will appear here once the first status check runs.", style = Txt.sm, color = Tw.mist400)
            }
            else -> SimpleGrid(lines, columns, key = { it.lineCode }) { line ->
                TrainCard(line, onOpenHistory = { historyLineCode = line.lineCode })
            }
        }
    }
}

/** `component/Card`: cor da linha, situação, status e descrição quando há alerta. */
@Composable
private fun TrainCard(row: TrainLineRow, onOpenHistory: () -> Unit) {
    val line = remember(row.lineCode) { TRAIN_LINES.firstOrNull { it.code == row.lineCode } }
    val status = TrainLineStatus.of(row.status)
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Surface.card)
            .clickable(onClick = onOpenHistory)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(
                line?.cssColor ?: androidx.compose.ui.graphics.Color.Transparent,
                Modifier.border(1.dp, Tw.gray600, CircleShape),
                size = 14.dp,
            )
            Text("Line ${row.lineCode} - ${line?.color ?: ""}", style = Txt.sm)
        }
        Text(row.situation, style = Txt.sm, color = Tw.mist300)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusDot(status?.dot ?: Tw.gray600, size = 8.dp)
                Text("Status: ${status?.label ?: "Not checked yet"}", style = Txt.sm)
            }
            val description = row.description
            if ((status == TrainLineStatus.WARNING || status == TrainLineStatus.CRITICAL) && !description.isNullOrEmpty()) {
                Text(
                    description,
                    style = Txt.xs,
                    color = if (status == TrainLineStatus.CRITICAL) Tw.red400 else Tw.yellow400,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Skeleton(Modifier.size(14.dp).clip(CircleShape))
            Skeleton(Modifier.width(80.dp).height(14.dp))
        }
        Skeleton(Modifier.width(144.dp).height(14.dp))
        Skeleton(Modifier.padding(top = 4.dp).width(112.dp).height(14.dp))
    }
}
