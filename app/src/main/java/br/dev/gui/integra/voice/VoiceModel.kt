package br.dev.gui.integra.voice

import br.dev.gui.integra.Prefs

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Modelo do Vosk usado pelo "Ei Integra" nativo. É o mesmo arquivo que o assistente web usa
 * (`/vosk/vosk-model-small-pt.tar.gz`, ~32 MB), baixado do próprio integra com o Basic Auth e
 * extraído uma vez para o armazenamento do app.
 */
object VoiceModel {
    private const val TAG = "VoiceModel"
    const val MODEL_PATH = "/vosk/vosk-model-small-pt.tar.gz"
    private const val READY_MARKER = ".ready"

    @Volatile var status: String = "não baixado"
        private set

    /** Pasta do modelo (device-protected storage, como as [Prefs], para funcionar antes do desbloqueio). */
    fun dir(context: Context): File =
        File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "vosk-model")

    fun isReady(context: Context) = File(dir(context), READY_MARKER).exists()

    /** Baixa e extrai o modelo. Bloqueia: chame fora da thread principal. */
    fun download(context: Context, prefs: Prefs): Boolean {
        if (isReady(context)) return true
        status = "baixando…"
        val target = dir(context)
        val tmp = File(target.parentFile, "vosk-model.tmp")
        return try {
            tmp.deleteRecursively()
            val conn = URL(Prefs.HOME_URL.trimEnd('/') + MODEL_PATH).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", prefs.userAgent(WebSettings.getDefaultUserAgent(context)))
            for ((k, v) in prefs.authHeaders()) conn.setRequestProperty(k, v)
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { untarGz(it, tmp) }
            File(tmp, READY_MARKER).createNewFile()
            target.deleteRecursively()
            if (!tmp.renameTo(target)) error("falha ao mover o modelo")
            status = "pronto"
            Log.i(TAG, "Modelo extraído em $target")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao baixar o modelo", e)
            status = "erro: ${e.message}"
            tmp.deleteRecursively()
            false
        }
    }

    /**
     * Extrai um .tar.gz removendo o primeiro componente do caminho (o arquivo traz tudo dentro de
     * `vosk-model-small-pt-0.3/`, como o vosk-browser espera).
     */
    private fun untarGz(input: InputStream, out: File) {
        val tar = GZIPInputStream(input.buffered())
        val header = ByteArray(512)
        val base = out.canonicalPath + File.separator
        while (true) {
            if (!readFully(tar, header)) break
            if (header.all { it == 0.toByte() }) break
            val name = cString(header, 0, 100).let { n ->
                val prefix = cString(header, 345, 155) // ustar
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            val size = cString(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val rel = name.substringAfter('/', "")
            if ((type == '0' || type == '\u0000') && rel.isNotEmpty()) {
                val file = File(out, rel)
                if (!file.canonicalPath.startsWith(base)) error("caminho inválido no tar: $name")
                file.parentFile?.mkdirs()
                file.outputStream().use { copy(tar, it, size) }
            } else {
                skip(tar, size)
            }
            skip(tar, (512 - size % 512) % 512)
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return if (read == 0) false else error("tar truncado")
            read += n
        }
        return true
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("tar truncado")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        val buf = ByteArray(8 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("tar truncado")
            left -= n
        }
    }
}
