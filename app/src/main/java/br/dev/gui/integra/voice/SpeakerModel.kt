package br.dev.gui.integra.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import br.dev.gui.integra.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

/**
 * Modelo de embedding de locutor do "é a minha voz?" no "Ei Integra". É o mesmo
 * arquivo ONNX que o assistente web usa (`/voice/speaker-ecapa.onnx`), baixado
 * do próprio integra com Basic Auth e guardado no armazenamento do app. Roda
 * localmente (ONNX Runtime), como o `speakerModel.ts` no web.
 *
 * As features são o `Fbank` (idêntico ao `fbank.ts`), então uma voz cadastrada
 * num dispositivo verifica no outro.
 */
class SpeakerModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
) {
    /** Extrai um embedding L2-normalizado de amostras mono em [-1, 1]. */
    fun embed(samples: FloatArray, sampleRate: Int): FloatArray {
        val feats = Fbank.compute(samples, sampleRate)
        if (feats.frames == 0) error("Áudio curto demais para reconhecer a voz")
        val shape = longArrayOf(1, feats.frames.toLong(), feats.bins.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(feats.data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = (result[0].value as Array<FloatArray>)[0]
                return Fbank.normalize(raw)
            }
        }
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "SpeakerModel"
        const val MODEL_PATH = "/voice/speaker-ecapa.onnx"

        /** Pasta do modelo (device-protected, como o Vosk, para rodar antes do desbloqueio). */
        private fun file(context: Context): File =
            File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "speaker-ecapa.onnx")

        fun isReady(context: Context) = file(context).exists()

        /**
         * Baixa o `.onnx` do integra (uma vez) e abre a sessão ONNX. Bloqueia:
         * chame fora da thread principal. Null se o download ou a abertura falharem.
         */
        fun load(context: Context, prefs: Prefs): SpeakerModel? {
            val target = file(context)
            if (!target.exists() && !download(context, prefs, target)) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(target.absolutePath, OrtSession.SessionOptions())
                SpeakerModel(
                    env,
                    session,
                    session.inputNames.first(),
                    session.outputNames.first(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao abrir o modelo de voz", e)
                null
            }
        }

        private fun download(context: Context, prefs: Prefs, target: File): Boolean {
            val tmp = File(target.parentFile, "speaker-ecapa.tmp")
            return try {
                val conn = URL(Prefs.HOME_URL.trimEnd('/') + MODEL_PATH).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", prefs.userAgent(WebSettings.getDefaultUserAgent(context)))
                for ((k, v) in prefs.authHeaders()) conn.setRequestProperty(k, v)
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(target)) error("falha ao mover o modelo")
                Log.i(TAG, "Modelo de voz baixado em $target")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao baixar o modelo de voz", e)
                tmp.delete()
                false
            }
        }
    }
}
