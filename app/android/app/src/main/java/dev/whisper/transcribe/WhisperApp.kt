package dev.whisper.transcribe

import android.app.Application

/// Früheste Stelle im Prozess: hier greift das Fehlerprotokoll auch für
/// Tastatur, Kachel und Hintergrunddienst, nicht nur für die Haupt-App.
class WhisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Replacements.init(this)
    }
}
