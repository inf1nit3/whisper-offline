package dev.whisper.transcribe

import android.content.Context
import java.io.File

object WhisperBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    // Der Engine-Kern hält genau einen Kontext und sperrt selbst nicht. Haupt-App
    // und Diktat-Overlay laufen im selben Prozess: ein Modellwechsel während
    // einer laufenden Transkription gäbe den Kontext unter ihr frei (Absturz).
    // Laden, Freigeben und Transkribieren laufen deshalb nacheinander.
    private val lock = Any()

    /// Präfix, mit dem die JNI-Schicht Fehler statt eines Transkripts meldet.
    private const val ERROR_PREFIX = "[Fehler]"

    private const val VAD_ASSET = "vad/ggml-silero-v6.2.0.bin"
    @Volatile private var vadReady = false

    /// Lädt das Modell und schaltet dabei das Überspringen von Stille (VAD)
    /// ein. Ohne VAD erfindet Whisper in Stille gern Sätze („Thank you.“).
    fun load(context: Context, path: String, useGpu: Boolean): Boolean =
        synchronized(lock) {
            // Ohne VAD geht es auch — nur mit den bekannten Stille-Fantasien.
            runCatching { ensureVad(context) }
                .onFailure { android.util.Log.w("WhisperBridge", "VAD nicht verfügbar", it) }
            loadModel(path, useGpu)
        }

    /// ggml braucht einen Dateipfad: das VAD-Modell (885 KB) einmalig aus der
    /// APK ins App-Verzeichnis kopieren.
    private fun ensureVad(context: Context) {
        if (vadReady) return
        val f = File(context.filesDir, VAD_ASSET)
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            val part = File(f.path + ".part")
            context.assets.open(VAD_ASSET).use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.renameTo(f)) { "VAD-Modell konnte nicht abgelegt werden" }
        }
        setVadModel(f.absolutePath)
        vadReady = true
    }

    fun free() = synchronized(lock) { freeModel() }

    /// Transkript der 16-kHz-Mono-Samples; null, wenn die Engine scheitert
    /// oder per [cancel] abgebrochen wurde ([wasCancelled] unterscheidet).
    /// Wartet, falls gerade ein Modell geladen wird.
    fun transcribe(samples: FloatArray, language: String): String? =
        synchronized(lock) {
            transcribe(samples, language, false)
                .takeUnless { it.startsWith(ERROR_PREFIX) }
                // Whisper beginnt jedes Segment mit einem Leerzeichen — pro Zeile weg damit.
                ?.lines()?.joinToString("\n") { it.trim() }
                ?.let(Replacements::apply)
        }

    /// Prozent 0–100 der laufenden Transkription, außerhalb eines Laufs 0.
    /// Ohne Sperre — aus dem UI-Thread abfragbar, während [transcribe] rechnet.
    /// Whisper meldet je 30-s-Fenster, Parakeet nur Anfang und Ende.
    external fun progress(): Int

    /// Bricht die laufende Transkription ab. Greift an der nächsten Prüfstelle
    /// der Engine — bei Parakeet erst nach dem Encoder-Durchlauf.
    external fun cancel()

    /// Wurde der letzte Durchgang per [cancel] beendet?
    external fun wasCancelled(): Boolean

    private external fun loadModel(path: String, useGpu: Boolean): Boolean
    private external fun setVadModel(path: String?)
    external fun isModelLoaded(): Boolean
    private external fun freeModel()

    /// "CPU · 8 Threads" bzw. "GPU (Vulkan) · 8 Threads" — für die Statuszeile.
    external fun backendInfo(): String

    /// Anzahl der großen CPU-Kerne, die die Engine benutzt.
    external fun detectThreads(): Int

    /// Ob überhaupt ein GPU-Backend einkompiliert ist — sonst hat der Schalter keinen Sinn.
    external fun hasGpuBackend(): Boolean

    /// Geladene Modellfamilie: 0 = keine, 1 = Whisper, 2 = Parakeet.
    /// Parakeet kennt weder Sprachauswahl noch ein festes Encoder-Fenster.
    external fun engineKind(): Int

    const val ENGINE_WHISPER = 1
    const val ENGINE_PARAKEET = 2

    /// Fenstergröße des letzten Laufs; 1500 = ungekürzt.
    external fun lastAudioCtx(): Int

    /// mel/encode/decode/prompt-Aufschlüsselung des letzten Laufs aus whisper.cpp.
    external fun lastTimings(): String

    /// Konkrete Ursache des letzten Ladefehlers; leer, wenn kein Fehler.
    external fun lastError(): String

    /// [shortCtx] kürzt das 30-s-Encoder-Fenster auf die tatsächliche Audiolänge.
    private external fun transcribe(samples: FloatArray, language: String, shortCtx: Boolean): String
}
