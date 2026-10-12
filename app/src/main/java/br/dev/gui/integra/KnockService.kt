package br.dev.gui.integra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.dev.gui.integra.voice.Assistant
import br.dev.gui.integra.voice.SpeakerGate
import br.dev.gui.integra.voice.VoiceModel
import br.dev.gui.integra.voice.WakeWord

/**
 * Serviço em primeiro plano que fica ouvindo os sensores (e o microfone, para o "Ei Integra")
 * enquanto a tela está desligada. Ao detectar um gatilho, liga a tela e traz o app (que é a home
 * screen) para frente.
 */
class KnockService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var cpuLock: PowerManager.WakeLock? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var detector: KnockDetector? = null
    private var listening = false
    private var proximityListener: SensorEventListener? = null
    private var voice: WakeWord? = null
    /** Thread só da voz: baixar/carregar o modelo é lento e não pode atrasar os sensores. */
    private var voiceThread: HandlerThread? = null
    private var voiceHandler: Handler? = null
    /** O serviço está em primeiro plano com o tipo "microphone" (sem isso o Android entrega silêncio). */
    private var micForeground = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        createChannels()
        enterForeground()
        thread = HandlerThread("knock").also { it.start() }
        handler = Handler(thread!!.looper)
        voiceThread = HandlerThread("voice").also { it.start() }
        voiceHandler = Handler(voiceThread!!.looper)
        // Os sensores ficam registrados o tempo todo (e só agem com a tela desligada):
        // o broadcast de SCREEN_OFF chega com ~2s de atraso e perderíamos gestos nessa janela.
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.anyWakeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Chamado também pela MainActivity em primeiro plano: é quando o Android deixa o serviço
        // ganhar o tipo "microphone" (iniciado pelo boot ele não pode).
        if (!micForeground) enterForeground()
        // Reaplica as preferências (limiar, sensores ligados/desligados).
        stopListening()
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (listening) return
        listening = true
        if (prefs.proximityEnabled) startProximity()
        if (prefs.knockEnabled) startKnock()
        // O modelo é pesado para carregar: o detector sobrevive aos reinícios do onStartCommand.
        val wantVoice = prefs.voiceWakeEnabled && hasMicPermission(this)
        if (wantVoice) startVoice() else releaseVoice()
    }

    private fun releaseVoice() {
        voice?.let { v -> voice = null; voiceHandler?.post { v.release() } }
    }

    private fun enterForeground() {
        val types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val wantMic = prefs.voiceWakeEnabled && hasMicPermission(this) && Build.VERSION.SDK_INT >= 30
        if (wantMic) {
            micForeground = runCatching {
                ServiceCompat.startForeground(
                    this, NOTIF_ID_SERVICE, buildServiceNotification(),
                    types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }.onFailure {
                // Ex.: Android 14+ iniciando em segundo plano (boot). Fica sem microfone até o app abrir.
                Log.w(TAG, "Sem tipo microphone agora", it)
            }.isSuccess
            if (micForeground) return
        }
        ServiceCompat.startForeground(this, NOTIF_ID_SERVICE, buildServiceNotification(), types)
    }

    /** Idempotente: também tenta de novo se o download do modelo falhou antes. */
    private fun startVoice() {
        val detector = voice ?: WakeWord(this, ::onWakeWord).also { voice = it }
        voiceHandler?.post {
            // Baixa o modelo do integra na 1ª vez (precisa das credenciais) e carrega na memória.
            if (!VoiceModel.isReady(this)) {
                if (!prefs.hasCredentials) return@post
                if ((1..3).none { VoiceModel.download(this, prefs) }) return@post
            }
            if (voice !== detector || !detector.load()) return@post
            // Verificação de locutor ("é a minha voz?"): só a voz cadastrada dispara.
            // Null quando não há perfil/modelo — aí dispara com qualquer voz (como antes).
            detector.setGate(runCatching { SpeakerGate.build(this, prefs) }.getOrNull())
            // Ouve sempre (tela ligada ou desligada), como o "Ei Integra" do web.
            detector.start()
        }
    }

    private fun startKnock() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // O acelerômetro não é "wake-up": mantém a CPU acordada para ele continuar
        // entregando amostras com a tela desligada (por isso gasta mais bateria).
        cpuLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "integra:knock").apply {
            setReferenceCounted(false)
            acquire()
        }
        var count = 0
        var windowMax = 0f
        var windowStart = 0L
        detector = KnockDetector(prefs.knockThreshold, ::onDoubleKnock, onSample = if (!BuildConfig.DEBUG) null else { mag ->
            count++
            if (mag > windowMax) windowMax = mag
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - windowStart > 250) {
                if (windowMax > 0.8f && !powerManager.isInteractive) Log.i(TAG, "pico=%.2f amostras=%d".format(windowMax, count))
                windowMax = 0f; count = 0; windowStart = now
            }
        })
        sensorManager.registerListener(detector, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        Log.i(TAG, "Ouvindo batidas (limiar=${prefs.knockThreshold})")
    }

    private fun startProximity() {
        // Preferimos a versão wake-up do sensor: o hardware acorda a CPU sozinho.
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            ?: return
        val listener = object : SensorEventListener {
            private var first = true
            private var wasNear = false
            override fun onSensorChanged(event: SensorEvent) {
                val near = event.values[0] < sensor.maximumRange
                if (BuildConfig.DEBUG) Log.i(TAG, "proximidade=${event.values[0]} near=$near")
                // O 1º evento é só o estado atual (ex.: já está no bolso); dispara apenas
                // na transição longe -> perto.
                if (!first && near && !wasNear) onWakeTrigger("mão aproximou")
                first = false
                wasNear = near
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        proximityListener = listener
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        Log.i(TAG, "Ouvindo proximidade (wakeUp=${sensor.isWakeUpSensor})")
    }

    private fun stopListening() {
        if (!listening) return
        detector?.let { sensorManager.unregisterListener(it) }
        detector = null
        proximityListener?.let { sensorManager.unregisterListener(it) }
        proximityListener = null
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        listening = false
        Log.i(TAG, "Parou de ouvir sensores")
    }

    private fun onDoubleKnock() = onWakeTrigger("batida dupla")

    private fun onWakeTrigger(reason: String) {
        if (powerManager.isInteractive) return
        if (android.os.SystemClock.elapsedRealtime() < suppressWakeUntil) return
        Log.i(TAG, "Ligando a tela: $reason")
        wakeScreen(this)
    }

    /** "Ei Integra": liga a tela se estiver desligada e o assistente já começa a ouvir o pedido. */
    private fun onWakeWord() {
        Log.i(TAG, "Ei Integra")
        if (!powerManager.isInteractive && Assistant.allowed) wakeScreen(this, fromVoice = true)
        Assistant.onWakeWord()
    }

    override fun onDestroy() {
        stopListening()
        releaseVoice()
        thread?.quitSafely()
        voiceThread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Detecção de batidas", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Ligar tela", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) }
        )
    }

    private fun buildServiceNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Integra")
            .setContentText("Diga \"Ei Integra\", aproxime a mão ou bata duas vezes para ligar a tela")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val TAG = "KnockService"
        private const val CHANNEL_SERVICE = "knock_service"
        private const val CHANNEL_WAKE = "wake"
        private const val NOTIF_ID_SERVICE = 1
        private const val NOTIF_ID_WAKE = 2

        @Volatile private var suppressWakeUntil = 0L

        /** Ignora os gatilhos de ligar a tela pelos próximos [ms] milissegundos. */
        fun suppressWake(ms: Long) {
            suppressWakeUntil = android.os.SystemClock.elapsedRealtime() + ms
        }

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun startIfEnabled(context: Context) {
            val prefs = Prefs(context)
            val intent = Intent(context, KnockService::class.java)
            if (prefs.anyWakeEnabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w(TAG, "Não foi possível iniciar o serviço", it) }
            } else {
                context.stopService(intent)
            }
        }

        /** Liga a tela e abre a tela principal do app. */
        @Suppress("DEPRECATION")
        fun wakeScreen(context: Context, fromVoice: Boolean = false) {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "integra:wake"
            ).acquire(3_000)

            val activity = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_FROM_KNOCK, true)
                .putExtra(MainActivity.EXTRA_FROM_VOICE, fromVoice)

            if (Settings.canDrawOverlays(context)) {
                // Com a permissão de sobreposição podemos abrir a activity a partir do serviço.
                runCatching { context.startActivity(activity) }
                    .onSuccess { return }
            }
            // Alternativa: notificação com full-screen intent (mesmo mecanismo de despertadores).
            val pi = PendingIntent.getActivity(
                context, 1, activity,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL_WAKE)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Integra")
                .setContentText("Abrindo…")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(5_000)
                .build()
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID_WAKE, n)
            }
        }

        fun cancelWakeNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_WAKE)
        }
    }
}
