package dev.whisper.transcribe

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/// Lädt ein Release-APK herunter und startet die Systeminstallation.
object ApkInstaller {

    fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        val part = File(dest.absolutePath + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            check(part.renameTo(dest)) { "Speichern fehlgeschlagen" }
            return dest
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /// Öffnet den System-Installationsdialog für die heruntergeladene APK.
    /// Gibt false zurück, wenn "Unbekannte Apps installieren" für die App
    /// deaktiviert ist — dann sollte die UI zur entsprechenden Einstellung führen.
    fun startInstall(context: Context, apk: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /// Einstellungsseite "Unbekannte Apps installieren" für diese App öffnen.
    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }
}
