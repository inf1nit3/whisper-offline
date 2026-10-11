package dev.whisper.transcribe

import android.content.Context

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

    // ---- Diktat ----

    private fun bool(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)
    private fun setBool(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()

    /// Ins Englische übersetzen statt transkribieren (nur Whisper-Modelle).
    fun translate(context: Context) = bool(context, "translate", false)
    fun setTranslate(context: Context, v: Boolean) = setBool(context, "translate", v)

    /// Kurzer Ton bei Aufnahmestart, -ende und Fehlern.
    fun audioFeedback(context: Context) = bool(context, "audio_feedback", true)
    fun setAudioFeedback(context: Context, v: Boolean) = setBool(context, "audio_feedback", v)

    /// Andere Wiedergabe (Musik, Podcast) während der Aufnahme leiser stellen.
    fun ducking(context: Context) = bool(context, "ducking", true)
    fun setDucking(context: Context, v: Boolean) = setBool(context, "ducking", v)

    /// Bevorzugtes Mikrofon als AudioDeviceInfo-ID; 0 = Systemvorgabe.
    fun micDevice(context: Context) = prefs(context).getInt("mic_device", 0)
    fun setMicDevice(context: Context, id: Int) = prefs(context).edit().putInt("mic_device", id).apply()

    /// Wörter erscheinen schon während der Aufnahme; das Ergebnis steht nach
    /// dem Stoppen fast sofort bereit, weil schon fast alles erkannt ist.
    fun liveTranscription(context: Context) = bool(context, "live_transcription", false)
    fun setLiveTranscription(context: Context, v: Boolean) = setBool(context, "live_transcription", v)

    /// „Komma“, „Punkt“, „neuer Absatz“ … als Zeichen bzw. Umbruch umsetzen.
    fun spokenCommands(context: Context) = bool(context, "spoken_commands", true)
    fun setSpokenCommands(context: Context, v: Boolean) = setBool(context, "spoken_commands", v)

    // ---- Verlauf ----

    fun historyEnabled(context: Context) = bool(context, "history_enabled", true)
    fun setHistoryEnabled(context: Context, v: Boolean) = setBool(context, "history_enabled", v)

    // ---- Enhance (Nachbearbeitung durch ein lokales Sprachmodell) ----

    /// Diktate automatisch verbessern. Dateien und geteilte Sprachnachrichten
    /// nicht — die verbessert man bei Bedarf von Hand (✨ am Transkript).
    fun enhanceAuto(context: Context) = bool(context, "enhance_auto", false)
    fun setEnhanceAuto(context: Context, v: Boolean) = setBool(context, "enhance_auto", v)

    /// Dateiname des LLM im llm-Verzeichnis; null = keins gewählt.
    fun llmModel(context: Context): String? = prefs(context).getString("llm_model", null)
    fun setLlmModel(context: Context, file: String?) =
        prefs(context).edit().apply { if (file == null) remove("llm_model") else putString("llm_model", file) }.apply()

    /// Eigener System-Prompt; null = mitgelieferter Standard.
    fun enhancePrompt(context: Context): String? = prefs(context).getString("enhance_prompt", null)
    fun setEnhancePrompt(context: Context, v: String?) =
        prefs(context).edit().apply { if (v == null) remove("enhance_prompt") else putString("enhance_prompt", v) }.apply()

    /// Modell nach der Nutzung im Speicher lassen (schneller, kostet RAM).
    fun llmKeepLoaded(context: Context) = bool(context, "llm_keep_loaded", false)
    fun setLlmKeepLoaded(context: Context, v: Boolean) = setBool(context, "llm_keep_loaded", v)

    /// Formatierung an App und Textfeld anpassen (Diktat-Tastatur).
    fun appContext(context: Context) = bool(context, "app_context", true)
    fun setAppContext(context: Context, v: Boolean) = setBool(context, "app_context", v)

    // ---- Meeting ----

    /// Nach einem Meeting automatisch Notizen schreiben (braucht ein LLM).
    fun meetingNotesAuto(context: Context) = bool(context, "meeting_notes_auto", true)
    fun setMeetingNotesAuto(context: Context, v: Boolean) = setBool(context, "meeting_notes_auto", v)
}
