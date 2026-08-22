package dev.whisper.transcribe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/// Ein Modell-Eintrag aus dem Server-Manifest.
data class ModelInfo(
    val file: String,
    val label: String,
    val tagline: String,
    val size: Long,
    val sha256: String?,
    val pros: List<String>,
    val cons: List<String>,
) {
    fun sizeText(): String =
        if (size >= 1L shl 30) "%.1f GB".format(size / 1073741824.0)
        else "%.0f MB".format(size / 1048576.0)
}

class ModelDownloadException(message: String) : Exception(message)

/// Lädt das Modell-Manifest und Modell-Dateien vom VPS.
object ModelRegistry {

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    fun localModelFiles(context: Context): List<File> =
        modelsDir(context).listFiles()?.filter { it.name.endsWith(".bin") } ?: emptyList()

    fun fetchManifest(): List<ModelInfo> {
        val json = fetchText(ModelConfig.MANIFEST_URL)
        val arr = JSONObject(json).getJSONArray("models")
        val out = ArrayList<ModelInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ModelInfo(
                    file = o.getString("file"),
                    label = o.getString("label"),
                    tagline = o.optString("tagline"),
                    size = o.optLong("size", 0),
                    sha256 = o.optString("sha256").ifEmpty { null },
                    pros = o.optJSONArray("pros")?.let { a ->
                        List(a.length()) { a.getString(it) }
                    } ?: emptyList(),
                    cons = o.optJSONArray("cons")?.let { a ->
                        List(a.length()) { a.getString(it) }
                    } ?: emptyList(),
                )
            )
        }
        return out
    }

    private fun baseUrl(): String = ModelConfig.MANIFEST_URL.substringBeforeLast('/') + "/"

    /// Lädt ein Modell herunter, prüft die SHA256-Summe und legt es atomar ab.
    /// Liefert die fertige Datei. Ruft onProgress(fer­tig, gesamt) auf dem IO-Thread auf.
    fun downloadModel(
        info: ModelInfo,
        context: Context,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val dest = File(modelsDir(context), info.file)
        val part = File(dest.absolutePath + ".part")
        val url = URL(baseUrl() + info.file)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode !in 200..299) {
                throw ModelDownloadException("Server antwortete ${conn.responseCode}")
            }
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
            var digest: MessageDigest? = null
            if (!info.sha256.isNullOrEmpty()) digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) {
                throw ModelDownloadException("Download unvollständig (${done}/${total} Bytes)")
            }
            if (digest != null) {
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hash.equals(info.sha256, ignoreCase = true)) {
                    throw ModelDownloadException("Prüfsumme falsch — Download beschädigt")
                }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                throw ModelDownloadException("Datei konnte nicht gespeichert werden")
            }
            return dest
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        try {
            if (conn.responseCode !in 200..299) {
                throw ModelDownloadException("Manifest nicht erreichbar (${conn.responseCode})")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
