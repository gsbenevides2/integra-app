package br.dev.gui.integra.ui.crons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.PollingEffect
import br.dev.gui.integra.data.apiCall
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.EmptyState
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.LoadingText
import br.dev.gui.integra.ui.components.LocalToast
import br.dev.gui.integra.ui.shell.ScreenColumn
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import kotlinx.coroutines.launch

private const val REFRESH_INTERVAL_MS = 30000L

/** Resultado da última execução nesta sessão. */
@Immutable
private sealed interface RunResult {
    data class Ok(val elapsed: Long) : RunResult
    data class Error(val message: String) : RunResult
}

/** Abaixo desta largura a tabela vira uma lista de cartões. */
private val TABLE_MIN_WIDTH = 560.dp

@Composable
fun CronsScreen() {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<CronJob>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val runningJobs = remember { mutableStateMapOf<String, Boolean>() }
    val lastResult = remember { mutableStateMapOf<String, RunResult>() }

    PollingEffect(REFRESH_INTERVAL_MS) {
        apiCall { Integra.api<CronsApi>().list() }
            .onSuccess { resp -> if (resp.ok) jobs = resp.jobs }
            .onFailure { toast.error("Falha ao buscar crons") }
        isLoading = false
    }

    fun runJob(job: CronJob) {
        runningJobs[job.name] = true
        scope.launch {
            val result = apiCall { Integra.api<CronsApi>().run(job.name) }
            runningJobs.remove(job.name)
            result
                .onSuccess { resp ->
                    if (!resp.ok) {
                        lastResult[job.name] = RunResult.Error(resp.error ?: "Erro desconhecido")
                        toast.error(resp.error ?: "Falha ao executar ${job.label}")
                    } else {
                        val elapsed = resp.elapsed ?: 0L
                        lastResult[job.name] = RunResult.Ok(elapsed)
                        toast.success("${job.label} concluído em ${elapsed}ms")
                    }
                }
                .onFailure { e ->
                    lastResult[job.name] = RunResult.Error(e.message ?: "Erro desconhecido")
                    toast.error("Falha ao executar ${job.label}")
                }
        }
    }

    ScreenColumn("Crons") {
        when {
            isLoading -> LoadingText("Carregando crons...")
            jobs.isEmpty() -> EmptyState(Hero.ServerStack, "Nenhum cron registrado")
            else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val asTable = maxWidth >= TABLE_MIN_WIDTH
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface.card)) {
                    if (asTable) TableHeader()
                    jobs.forEachIndexed { index, job ->
                        androidx.compose.runtime.key(job.name) {
                            if (index > 0 || asTable) HorizontalDivider(thickness = 1.dp, color = Tw.gray700)
                            val running = runningJobs[job.name] == true
                            val result = lastResult[job.name]
                            if (asTable) TableRow(job, running, result) { runJob(job) }
                            else StackedRow(job, running, result) { runJob(job) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth()) {
        HeaderCell("Job", 1.4f)
        HeaderCell("Schedule", 1f)
        HeaderCell("Última execução", 1f)
        Row(Modifier.width(ACTION_WIDTH)) {}
    }
}

private val ACTION_WIDTH = 128.dp

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, style = Txt.xs, color = Tw.mist400, modifier = Modifier.weight(weight).padding(horizontal = 12.dp, vertical = 8.dp))

@Composable
private fun TableRow(job: CronJob, running: Boolean, result: RunResult?, onRun: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.4f).padding(horizontal = 12.dp, vertical = 8.dp)) { JobCell(job) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) { ScheduleCell(job) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) { LastRunCell(running, result) }
        Row(Modifier.width(ACTION_WIDTH).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            RunButton(running, onRun)
        }
    }
}

/** Versão em cartão (celular em pé): mesmas informações, empilhadas. */
@Composable
private fun StackedRow(job: CronJob, running: Boolean, result: RunResult?, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { JobCell(job) }
            RunButton(running, onRun)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { ScheduleCell(job) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Última execução", style = Txt.xs.copy(fontSize = 10.sp), color = Tw.mist400)
                LastRunCell(running, result)
            }
        }
    }
}

@Composable
private fun JobCell(job: CronJob) {
    Text(job.label, style = Txt.sm, color = Tw.mist100)
    Text(job.name, style = Txt.xs.copy(fontSize = 10.sp, lineHeight = 14.sp), color = Tw.mist400)
}

@Composable
private fun ScheduleCell(job: CronJob) {
    Text(job.scheduleLabel, style = Txt.xs, color = Tw.mist300)
    Text(job.schedule, style = Txt.xs.copy(fontSize = 10.sp, lineHeight = 14.sp), color = Tw.mist500)
}

@Composable
private fun LastRunCell(running: Boolean, result: RunResult?) {
    when {
        running -> Text("Executando...", style = Txt.xs, color = Tw.yellow300)
        result is RunResult.Ok -> Text("OK — ${result.elapsed}ms", style = Txt.xs, color = Tw.green300)
        result is RunResult.Error -> Text("Erro", style = Txt.xs, color = Tw.red300)
        else -> Text("—", style = Txt.xs, color = Tw.mist400)
    }
}

@Composable
private fun RunButton(running: Boolean, onRun: () -> Unit) {
    IntegraButton(onRun, variant = ButtonVariant.Secondary, loading = running, enabled = !running) {
        if (!running) Icon(Hero.Play, null, Modifier.size(16.dp))
        Text("Executar", style = Txt.sm)
    }
}
