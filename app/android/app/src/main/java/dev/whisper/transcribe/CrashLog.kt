package dev.whisper.transcribe

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// Lokales Fehlerprotokoll: Abstürze landen in filesDir/crash.log, nichts geht
/// irgendwohin. Über ⋮ → „Fehlerbericht“ lässt sich der Inhalt ansehen und
/// bei Bedarf selbst teilen.
object CrashLog {

    private const val MAX_BYTES = 64 * 1024
    private const val KEY_SEEN = "crash_log_seen_length"

    fun file(context: Context) = File(context.filesDir, "crash.log")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { append(app, t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    private fun append(context: Context, t: Thread, e: Throwable) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            append("=== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
            append(" · App ").append(version)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Thread ").append(t.name).append('\n')
            append(trace).append('\n')
        }
        val f = file(context)
        // Nur die jüngsten Einträge behalten
        val old = if (f.exists()) f.readText() else ""
        f.writeText((old + entry).takeLast(MAX_BYTES))
    }

    fun read(context: Context): String = file(context).takeIf { it.exists() }?.readText().orEmpty()

    fun hasLog(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    /// Neuer Eintrag seit dem letzten Hinweis? Markiert ihn gleich als gesehen.
    fun takeNewCrashNotice(context: Context): Boolean {
        val len = file(context).takeIf { it.exists() }?.length() ?: 0L
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        if (len == seen) return false
        prefs.edit().putLong(KEY_SEEN, len).apply()
        return len > seen
    }

    fun clear(context: Context) {
        file(context).delete()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putLong(KEY_SEEN, 0L).apply()
    }
}
