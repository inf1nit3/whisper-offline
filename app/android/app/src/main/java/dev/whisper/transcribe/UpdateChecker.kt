package dev.whisper.transcribe

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/// Prüft GitHub-Releases auf neuere Versionen.
/// API: /repos/inf1nit3/whisper-offline/releases/latest (öffentlich, ohne Auth).
object UpdateChecker {

    const val REPO_LATEST =
        "https://api.github.com/repos/inf1nit3/whisper-offline/releases/latest"

    data class Release(
        val tag: String,          // z. B. "v1.1"
        val name: String,
        val apkUrl: String?,      // Download-URL des .apk-Assets
        val body: String,         // Release-Notes
    )

    fun currentVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) { "0" }

    fun fetchLatest(): Release? {
        val conn = URL(REPO_LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "whisper-offline-updater") // GitHub verlangt UA
        return try {
            if (conn.responseCode != 200) return null
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val apk = (0 until root.getJSONArray("assets").length())
                .map { root.getJSONArray("assets").getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url")
            Release(
                tag = root.getString("tag_name"),
                name = root.optString("name", root.getString("tag_name")),
                apkUrl = apk,
                body = root.optString("body", ""),
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /// "v1.2.3" vs "1.2" → true, wenn remote neuere Versionsnummer hat.
    fun isNewer(current: String, remoteTag: String): Boolean {
        val cur = parse(current)
        val rem = parse(remoteTag)
        for (i in 0..2) if (rem[i] != cur[i]) return rem[i] > cur[i]
        return false
    }

    private fun parse(v: String): IntArray {
        val nums = v.trimStart('v', 'V').split('.', '-', '+')
        return IntArray(3) { i -> nums.getOrNull(i)?.toIntOrNull() ?: 0 }
    }
}
