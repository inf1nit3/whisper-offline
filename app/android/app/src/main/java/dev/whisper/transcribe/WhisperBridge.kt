package dev.whisper.transcribe

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

    fun load(path: String, useGpu: Boolean): Boolean =
        synchronized(lock) { loadModel(path, useGpu) }

    fun free() = synchronized(lock) { freeModel() }

    /// Transkript der 16-kHz-Mono-Samples; null, wenn die Engine scheitert.
    /// Wartet, falls gerade ein Modell geladen wird.
    fun transcribe(samples: FloatArray, language: String): String? =
        synchronized(lock) {
            transcribe(samples, language, false).takeUnless { it.startsWith(ERROR_PREFIX) }
        }

    private external fun loadModel(path: String, useGpu: Boolean): Boolean
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
