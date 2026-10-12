package br.dev.gui.integra

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.Telemetry
import br.dev.gui.integra.voice.AssistantApi
import br.dev.gui.integra.voice.AssistantConfig
import br.dev.gui.integra.voice.AudioInput
import br.dev.gui.integra.voice.Enrollment
import br.dev.gui.integra.voice.Recorder
import br.dev.gui.integra.voice.SpeakerModel
import br.dev.gui.integra.voice.VoiceLabelBody
import br.dev.gui.integra.voice.VoiceModel
import br.dev.gui.integra.voice.VoiceProfile
import br.dev.gui.integra.voice.VoiceProfileBody
import br.dev.gui.integra.voice.WakeWord
import br.dev.gui.integra.ui.components.ButtonVariant
import br.dev.gui.integra.ui.components.IntegraButton
import br.dev.gui.integra.ui.components.IntegraSlider
import br.dev.gui.integra.ui.components.IntegraSwitch
import br.dev.gui.integra.ui.components.TextInput
import br.dev.gui.integra.ui.theme.IntegraTheme
import br.dev.gui.integra.ui.theme.Surface
import br.dev.gui.integra.ui.theme.Tw
import br.dev.gui.integra.ui.theme.Txt
import br.dev.gui.integra.ui.theme.bold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Login (usuário + App password do Authentik) e configurações do app. */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    /** Incrementado ao voltar para a tela, para reler permissões/estado. */
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val authFailed = intent.getBooleanExtra(EXTRA_AUTH_FAILED, false)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            IntegraTheme {
                BackHandler {
                    // Sem credenciais não há para onde voltar (a home é o próprio app).
                    if (prefs.hasCredentials) finish()
                }
                SettingsScreen(authFailed, refresh)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
        Telemetry.view("Settings", "Configurações")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) KnockService.startIfEnabled(this)
        refresh++
    }

    @Composable
    private fun SettingsScreen(authFailed: Boolean, refresh: Int) {
        var username by remember { mutableStateOf(prefs.username) }
        var password by remember { mutableStateOf(prefs.password) }
        var usernameError by remember { mutableStateOf(false) }
        Column(
            Modifier
                .fillMaxSize()
                .background(Surface.page)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Integra", style = Txt.x3l.bold())
            Text(Prefs.HOST, style = Txt.sm, color = Tw.mist400, modifier = Modifier.padding(bottom = 8.dp))
            if (authFailed) {
                Text(
                    "Usuário ou senha inválidos. A senha precisa ser um App password do Authentik, e o usuário precisa ter acesso ao app Integra.",
                    style = Txt.sm, color = Tw.red400,
                )
            }
            TextInput(username, { username = it; usernameError = false }, label = "Usuário do Authentik")
            if (usernameError) Text("Informe o usuário", style = Txt.xs, color = Tw.red400)
            TextInput(password, { password = it }, label = "App password do Authentik", password = true, onDone = {
                if (save(username, password)) Unit else usernameError = true
            })
            IntegraButton("Entrar", { if (!save(username, password)) usernameError = true }, Modifier.fillMaxWidth().padding(top = 4.dp))

            Section("Tela inicial") {
                val isHome = remember(refresh) {
                    packageManager.resolveActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
                    )?.activityInfo?.packageName == packageName
                }
                Text(if (isHome) "✅ O Integra é a tela inicial." else "❌ O Integra não é a tela inicial.", style = Txt.sm)
                IntegraButton("Escolher tela inicial (launcher)", { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, Modifier.fillMaxWidth())
                var idle by remember { mutableStateOf(prefs.idleLockMinutes.toString()) }
                TextInput(
                    idle,
                    { v -> idle = v.filter(Char::isDigit).take(3); idle.toIntOrNull()?.let { prefs.idleLockMinutes = it } },
                    label = "Bloquear a tela após X minutos sem toque (0 = nunca)",
                    keyboardType = KeyboardType.Number,
                )
                PrefSwitch("Mostrar por cima da tela de bloqueio", prefs.showWhenLocked) { prefs.showWhenLocked = it }
                val lockOn = remember(refresh) { LockService.isEnabled() }
                Text(if (lockOn) "✅ Bloqueio de tela ativo" else "⚠️ Ative \"Integra - bloquear tela\" em Acessibilidade", style = Txt.sm)
                if (!lockOn) IntegraButton("Abrir Acessibilidade", { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth())
            }

            Section("Ligar a tela automaticamente") {
                Text("Com a tela desligada, o app liga a tela e abre o Integra quando:", style = Txt.sm, color = Tw.mist300)
                PrefSwitch("Eu digo \"Ei Integra\" (microfone, offline)", prefs.voiceWakeEnabled) { checked ->
                    prefs.voiceWakeEnabled = checked
                    if (checked && !KnockService.hasMicPermission(this@SettingsActivity)) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                    KnockService.startIfEnabled(this@SettingsActivity)
                }
                val voice = remember(refresh) {
                    when {
                        !KnockService.hasMicPermission(this@SettingsActivity) -> "⚠️ Sem permissão de microfone"
                        VoiceModel.isReady(this@SettingsActivity) -> "✅ Modelo de voz pronto"
                        else -> "Modelo de voz: ${VoiceModel.status}"
                    }
                }
                Text(voice, style = Txt.xs, color = Tw.mist400, modifier = Modifier.padding(start = 44.dp))
                VoiceEnrollment()
                PrefSwitch("A mão se aproxima do sensor de proximidade (perto da câmera frontal)", prefs.proximityEnabled) {
                    prefs.proximityEnabled = it
                    KnockService.startIfEnabled(this@SettingsActivity)
                }
                PrefSwitch("Duas batidas no aparelho (acelerômetro, gasta mais bateria)", prefs.knockEnabled) {
                    prefs.knockEnabled = it
                    KnockService.startIfEnabled(this@SettingsActivity)
                }
                KnockSensitivity()

                val overlay = remember(refresh) { Settings.canDrawOverlays(this@SettingsActivity) }
                val battery = remember(refresh) {
                    (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
                }
                Text(
                    (if (overlay) "✅" else "⚠️") + " Sobrepor outros apps\n" + (if (battery) "✅" else "⚠️") + " Sem otimização de bateria",
                    style = Txt.sm, modifier = Modifier.padding(top = 8.dp),
                )
                IntegraButton("Permitir sobrepor outros apps", {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }, Modifier.fillMaxWidth())
                IntegraButton("Ignorar otimização de bateria", {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    runCatching { startActivity(i) }
                }, Modifier.fillMaxWidth())
            }

            Section("Outros") {
                IntegraButton("Abrir configurações do Android", { startActivity(Intent(Settings.ACTION_SETTINGS)) }, Modifier.fillMaxWidth())
                IntegraButton("Sair (apagar usuário e senha)", {
                    prefs.clearCredentials()
                    username = ""
                    password = ""
                    Toast.makeText(this@SettingsActivity, "Credenciais apagadas", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth(), variant = ButtonVariant.Danger)
                Text(
                    "Dica: para voltar a esta tela a partir do Integra, toque na tela com 3 dedos.",
                    style = Txt.sm, color = Tw.mist400, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    /** Teste ao vivo da batida dupla + sensibilidade. */
    @Composable
    private fun KnockSensitivity() {
        var threshold by remember { mutableFloatStateOf(prefs.knockThreshold) }
        var count by remember { mutableIntStateOf(0) }
        var peak by remember { mutableFloatStateOf(0f) }
        var shownPeak by remember { mutableFloatStateOf(0f) }
        val sensorManager = remember { getSystemService(SENSOR_SERVICE) as SensorManager }
        val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
        val detector = remember {
            KnockDetector(threshold, onDoubleKnock = { runOnUiThread { count++ } }, onSample = { if (it > peak) peak = it })
        }
        var active by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { active = true }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { active = false }
        DisposableEffect(active) {
            if (active && sensor != null) sensorManager.registerListener(detector, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            onDispose { sensorManager.unregisterListener(detector) }
        }
        LaunchedEffect(active) {
            while (active) { shownPeak = peak; peak = 0f; delay(700) }
        }
        // Barra = sensibilidade: mais à direita, menor o limiar (mais sensível).
        IntegraSlider(
            value = ((10f - threshold) * 10f).roundToInt().coerceIn(0, 95),
            onCommit = { p ->
                threshold = (10f - p / 10f).coerceIn(0.5f, 10f)
                prefs.knockThreshold = threshold
                detector.threshold = threshold
                KnockService.startIfEnabled(this@SettingsActivity)
            },
            max = 95,
            label = "Sensibilidade",
            formatValue = { "limiar %.1f m/s²".format(10f - it / 10f) },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (sensor == null) "Este aparelho não tem acelerômetro."
            else "Teste ao vivo — batidas duplas detectadas: $count\npico recente: %.1f (limiar %.1f)".format(shownPeak, threshold),
            style = Txt.xs.copy(fontFamily = FontFamily.Monospace), color = Tw.mist300,
        )
    }

    /**
     * Vozes do "é a minha voz?" (pessoas ou aparelhos): cada uma grava algumas vezes "Ei Integra",
     * extrai os embeddings localmente e envia para o integra. Depois, só uma voz cadastrada dispara
     * o "Ei Integra". Ajuste de rigidez (limiar de cosseno). Espelha o `SettingsModal.tsx` do web.
     */
    @Composable
    private fun VoiceEnrollment() {
        val ctx = this@SettingsActivity
        val scope = rememberCoroutineScope()
        val api = remember { Integra.api<AssistantApi>() }
        var config by remember { mutableStateOf<AssistantConfig?>(null) }
        /** As vozes do usuário com o modelo atual. */
        var voices by remember { mutableStateOf<List<VoiceProfile>>(emptyList()) }
        /** Todas as vozes do usuário, de qualquer modelo: é o que o limite do servidor conta. */
        var totalVoices by remember { mutableIntStateOf(0) }
        /** Null = parado; senão a mensagem de progresso do cadastro. */
        var status by remember { mutableStateOf<String?>(null) }
        /** Nome da voz a cadastrar, enquanto o campo está aberto. */
        var newLabel by remember { mutableStateOf<String?>(null) }
        /** A voz sendo renomeada e o nome digitado. */
        var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
        var threshold by remember { mutableFloatStateOf(prefs.voiceMatchThreshold) }

        /** Relê a lista; best-effort, para uma falha só na recarga não virar erro da ação que deu certo. */
        suspend fun reloadVoices() {
            val modelId = config?.voiceModelId ?: return
            val all = try {
                withContext(Dispatchers.IO) { api.voiceProfiles() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            voices = all.filter { it.modelId == modelId }
            totalVoices = all.size
        }

        fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

        LaunchedEffect(Unit) {
            runCatching {
                config = withContext(Dispatchers.IO) { api.config() }.takeIf { it.voiceModelUrl != null }
                reloadVoices()
            }
        }
        val current = config ?: return

        fun enroll(label: String) {
            if (!KnockService.hasMicPermission(ctx)) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                return
            }
            newLabel = null
            scope.launch {
                // Dizer "Ei Integra" para gravar não pode abrir uma sessão enquanto isso.
                val resumeWakeWord = WakeWord.pause()
                var model: SpeakerModel? = null
                try {
                    status = "Preparando o microfone…"
                    val loaded = withContext(Dispatchers.IO) { SpeakerModel.load(ctx, prefs) }
                        ?: error("Modelo de voz indisponível")
                    model = loaded
                    val voice = Enrollment.recordVoice(
                        embed = { samples -> withContext(Dispatchers.Default) { loaded.embed(samples, AudioInput.SAMPLE_RATE) } },
                        capture = { timeout -> Recorder.captureOnce(ctx, timeout) },
                        onStep = { status = Enrollment.describe(it) },
                    )
                    status = "Salvando…"
                    val body = VoiceProfileBody(label, voice.embeddings.map { it.toList() }, current.voiceModelId)
                    runCatching { withContext(Dispatchers.IO) { api.createVoiceProfile(body) } }
                        .onFailure { if (it is CancellationException) throw it; error("Falha ao salvar a voz") }
                    // Reconstrói a verificação no serviço com a voz nova.
                    KnockService.startIfEnabled(ctx)
                    reloadVoices()
                    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
                    toast(
                        if (voice.consistency < threshold) {
                            // As amostras se parecem menos do que a rigidez exige: o "Ei Integra"
                            // ignoraria muito esta voz. Avisa enquanto está fresco.
                            "Voz cadastrada, mas com consistência de ${percent(voice.consistency)}, abaixo da " +
                                "rigidez de ${percent(threshold)}: a Integra pode ignorar essa voz. " +
                                "Grave de novo num lugar silencioso ou reduza a rigidez."
                        } else {
                            "Voz cadastrada (consistência de ${percent(voice.consistency)})"
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast(e.message ?: "Falha ao cadastrar a voz")
                } finally {
                    model?.close()
                    resumeWakeWord()
                    status = null
                }
            }
        }

        fun rename(voice: VoiceProfile, label: String) {
            renaming = null
            if (label.isEmpty() || label == voice.label) return
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { api.renameVoiceProfile(voice.id, VoiceLabelBody(label)) } }
                    .onSuccess { toast("Voz renomeada") }
                    .onFailure { if (it is CancellationException) throw it; toast("Falha ao renomear a voz") }
                reloadVoices()
            }
        }

        fun remove(voice: VoiceProfile) = scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteVoiceProfile(voice.id) } }
                .onSuccess {
                    KnockService.startIfEnabled(ctx)
                    toast("Voz removida")
                }
                .onFailure { if (it is CancellationException) throw it; toast("Falha ao remover a voz") }
            reloadVoices()
        }

        Column(Modifier.padding(start = 44.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vozes — só o \"Ei Integra\" dito por uma voz cadastrada ativa a assistente", style = Txt.xs, color = Tw.mist300)
            Text(
                if (voices.isNotEmpty()) "Outras vozes (e a TV) são ignoradas."
                else "Sem voz cadastrada: o \"Ei Integra\" ativa com qualquer voz.",
                style = Txt.xs, color = Tw.mist400,
            )
            for (voice in voices) {
                val edit = renaming?.takeIf { it.first == voice.id }
                if (edit != null) {
                    TextInput(
                        edit.second, { renaming = voice.id to it.take(MAX_VOICE_LABEL_LENGTH) },
                        placeholder = "Novo nome desta voz",
                        onDone = { rename(voice, edit.second.trim()) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IntegraButton("Salvar", { rename(voice, edit.second.trim()) })
                        IntegraButton("Cancelar", { renaming = null }, variant = ButtonVariant.Secondary)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Tw.gray900).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(voice.label, style = Txt.sm, color = Tw.mist200, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IntegraButton("Renomear", { renaming = voice.id to voice.label }, variant = ButtonVariant.Secondary, enabled = status == null)
                        IntegraButton("Remover", { remove(voice) }, variant = ButtonVariant.Secondary, enabled = status == null)
                    }
                }
            }
            val label = newLabel
            when {
                status != null -> {
                    Text(status!!, style = Txt.sm, color = Tw.mist200)
                    Text("O \"Ei Integra\" fica desligado até o cadastro terminar.", style = Txt.xs, color = Tw.mist500)
                }
                label != null -> {
                    TextInput(
                        label, { newLabel = it.take(MAX_VOICE_LABEL_LENGTH) },
                        placeholder = "Nome desta voz (ex.: Gui, celular)",
                        onDone = { label.trim().takeIf { it.isNotEmpty() }?.let(::enroll) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IntegraButton("Gravar", { enroll(label.trim()) }, enabled = label.isNotBlank())
                        IntegraButton("Cancelar", { newLabel = null }, variant = ButtonVariant.Secondary)
                    }
                }
                else -> IntegraButton(
                    "Adicionar voz", { newLabel = "" }, Modifier.fillMaxWidth(),
                    enabled = totalVoices < MAX_VOICES_PER_USER,
                )
            }
            if (totalVoices >= MAX_VOICES_PER_USER) {
                Text("No máximo $MAX_VOICES_PER_USER vozes: remova uma para adicionar outra.", style = Txt.xs, color = Tw.mist500)
            }
        }
        IntegraSlider(
            value = (threshold * 100).roundToInt().coerceIn(30, 90),
            onCommit = { p ->
                threshold = (p / 100f).coerceIn(0.3f, 0.9f)
                prefs.voiceMatchThreshold = threshold
                KnockService.startIfEnabled(ctx)
            },
            min = 30,
            max = 90,
            label = "Rigidez do reconhecimento",
            formatValue = { "$it%" },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Mais alto = mais rígido (menos falso positivo). Mais baixo = ativa mais fácil.",
            style = Txt.xs, color = Tw.mist400,
        )
    }

    @Composable
    private fun PrefSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var checked by remember { mutableStateOf(initial) }
        IntegraSwitch(checked, { checked = it; onChange(it); refresh++ }, label = label, modifier = Modifier.padding(vertical = 4.dp))
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Text(title, style = Txt.lg.bold(), modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }

    private fun save(username: String, password: String): Boolean {
        val u = username.trim()
        if (u.isEmpty()) return false
        prefs.username = u
        prefs.password = password
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        return true
    }

    companion object {
        const val EXTRA_AUTH_FAILED = "auth_failed"
        private const val REQ_MIC = 2
        /** Espelham `MAX_VOICE_LABEL_LENGTH` e `MAX_VOICES_PER_USER` do servidor (model.ts). */
        private const val MAX_VOICE_LABEL_LENGTH = 60
        private const val MAX_VOICES_PER_USER = 10
    }
}
