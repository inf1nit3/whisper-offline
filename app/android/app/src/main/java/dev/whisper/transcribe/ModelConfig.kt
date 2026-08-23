package dev.whisper.transcribe

import android.content.Context

/// Zentrale Konfiguration des Modell-Backends.
/// Pfad zu den Modelldateien = Verzeichnis des Manifests + "file"-Eintrag.
object ModelConfig {
    const val MANIFEST_URL = "https://whisper.scheisssewasser.xyz/models.json"
}

/// Persistierte Laufzeit-Einstellungen, geteilt zwischen Haupt-App und Diktat-Overlay.
object Settings {
    private const val PREFS = "settings"
    private const val KEY_GPU = "use_gpu"
    private const val KEY_SHORT_CTX = "short_ctx"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_VERSION = "settings_version"
    private const val CURRENT_VERSION = 1
    private const val KEY_ONBOARDING = "onboarding_done"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /// Einführung beim ersten Start; nach dem Durchlauf dauerhaft erledigt.
    fun onboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING, false)

    fun setOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING, true).apply()
    }

    /// Einmalige Bereinigung. `getBoolean` greift auf den Standardwert nur zurück,
    /// wenn der Schlüssel fehlt — von Hand gesetzte Schalter überleben eine
    /// Änderung des Standards also. Beide Optionen wurden auf dem X300 Pro
    /// gemessen und verworfen (siehe README), deshalb hier einmal zurücksetzen.
    fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return
        p.edit()
            .remove(KEY_SHORT_CTX)
            .remove(KEY_GPU)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
    }

    /// Vulkan-Backend. Standard aus: seit die CPU-Engine mit dotprod/i8mm gebaut
    /// wird, ist sie auf Mobil-GPUs meist schneller — und der Vulkan-Pfad kostet
    /// beim ersten Lauf spürbar Zeit für die Shader-Pipelines.
    fun useGpu(context: Context): Boolean = prefs(context).getBoolean(KEY_GPU, false)

    fun setUseGpu(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GPU, value).apply()
    }

    /// Encoder-Fenster auf die tatsächliche Audiolänge kürzen statt immer 30 s zu
    /// rechnen. Standard aus: auf einem X300 Pro brachte 768 statt 1500 keine
    /// messbare Zeitersparnis (beide Läufe 5,2 s), und whisper.h führt die Option
    /// als experimentell mit möglichem Qualitätsverlust.

    /// Standard "de" statt "auto": bei "auto" führt whisper.cpp vor der eigentlichen
    /// Transkription einen kompletten zusätzlichen Encoder-Durchlauf zur
    /// Spracherkennung aus (src/whisper.cpp, Zeile 6851 → 4066) — das verdoppelt
    /// die Rechenzeit, weil der Encoder über 90 % davon ausmacht.
    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, "de") ?: "de"

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }
}
