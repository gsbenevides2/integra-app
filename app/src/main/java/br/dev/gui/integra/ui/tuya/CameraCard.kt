package br.dev.gui.integra.ui.tuya

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.ui.theme.Surface
import kotlinx.coroutines.delay

/** Atraso (buffer à frente da reprodução) a partir do qual aceleramos para voltar ao vivo. */
private const val CATCH_UP_MS = 3_000L
/** Atraso a partir do qual vale mais reconectar do que acelerar. */
private const val RECONNECT_MS = 10_000L
/** Espera antes de tentar de novo depois de um erro ou do fim do stream. */
private const val RETRY_MS = 4_000L

/**
 * `CameraCard`: vídeo ao vivo (fMP4 do go2rtc via `/api/frigate/cameras/{name}/stream`), mudo, 16:9,
 * cantos arredondados, sem controles. Tocar abre o Frigate no navegador.
 *
 * O player só existe com a tela em primeiro plano (criado no ON_START, liberado no ON_STOP ou quando
 * o card sai da composição) e não é recriado pelo polling da tela.
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraCard(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(name, started, attempt) {
        if (!started) return@DisposableEffect onDispose { }
        failed = false
        val created = buildCameraPlayer(context, name)
        created.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // O stream nunca termina; se terminou, a conexão caiu.
                if (playbackState == Player.STATE_ENDED) failed = true
            }
        })
        created.prepare()
        player = created
        onDispose {
            player = null
            created.release()
        }
    }

    // Erro ou fim: espera um pouco e recria o player.
    LaunchedEffect(failed) {
        if (failed) {
            delay(RETRY_MS)
            attempt++
        }
    }

    // Mantém perto do ao vivo: acelera um pouco quando atrasa e reconecta se atrasar demais.
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (p.playbackState != Player.STATE_READY) continue
            val lag = p.bufferedPosition - p.currentPosition
            val speed = when {
                lag > RECONNECT_MS -> { attempt++; return@LaunchedEffect }
                lag > CATCH_UP_MS -> 1.25f
                lag < 1_000 -> 1f
                else -> p.playbackParameters.speed
            }
            if (speed != p.playbackParameters.speed) p.setPlaybackSpeed(speed)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface.card)
            .clickable { openFrigate(context, name) }
            .semantics { contentDescription = name },
    ) {
        // TextureView (e não SurfaceView): entra na composição como qualquer view, então o fundo
        // escuro de drawers/modais cobre o vídeo e o clip arredondado funciona.
        AndroidView(
            factory = { ctx -> CoverTextureView(ctx) },
            update = { view -> view.bind(player) },
            onRelease = { view -> view.bind(null) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** TextureView que recorta o vídeo para preencher a área (`object-cover`). */
private class CoverTextureView(context: Context) : android.view.TextureView(context) {
    private var player: Player? = null
    private var videoW = 0
    private var videoH = 0
    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            videoW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            videoH = videoSize.height
            applyCover()
        }
    }

    fun bind(next: Player?) {
        if (next === player) return
        player?.let { it.removeListener(listener); it.clearVideoTextureView(this) }
        player = next
        next?.let {
            it.addListener(listener)
            it.setVideoTextureView(this)
            listener.onVideoSizeChanged(it.videoSize)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCover()
    }

    private fun applyCover() {
        if (videoW <= 0 || videoH <= 0 || width == 0 || height == 0) return
        val viewRatio = width.toFloat() / height
        val videoRatio = videoW.toFloat() / videoH
        val sx = if (videoRatio > viewRatio) videoRatio / viewRatio else 1f
        val sy = if (videoRatio > viewRatio) 1f else viewRatio / videoRatio
        setTransform(android.graphics.Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }
}

@OptIn(UnstableApi::class)
private fun buildCameraPlayer(context: Context, name: String): ExoPlayer {
    // Começa a tocar com pouquíssimo buffer (latência, não suavidade). O teto é folgado de
    // propósito: o stream chega em tempo real, então o buffer só cresce quando a reprodução atrasa
    // (ex.: depois de travar), e é assim que o atraso fica visível para o laço de catch-up. Com um
    // teto baixo o player pararia de ler e o atraso ficaria escondido no socket.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 500,
            /* maxBufferMs = */ 20_000,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    // Auth e User-Agent entram pelo interceptor do cliente.
    val dataSource = OkHttpDataSource.Factory(Integra.streamingHttp)
    val source = ProgressiveMediaSource.Factory(dataSource)
        .createMediaSource(MediaItem.fromUri(Integra.url("/api/frigate/cameras/${Uri.encode(name)}/stream")))
    // H.265 pode não ter decodificador em hardware para duas câmeras: cai para o próximo disponível.
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderers)
        .setLoadControl(loadControl)
        .build()
        .apply {
            volume = 0f
            // Mudo: nem decodifica o áudio (economiza bateria).
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            playWhenReady = true
            setMediaSource(source)
        }
}

private fun openFrigate(context: Context, name: String) {
    val url = Integra.url("/api/frigate/cameras/${Uri.encode(name)}/goToFrigate")
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
