package dev.whisper.transcribe

object WhisperBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun loadModel(path: String, useGpu: Boolean): Boolean
    external fun isModelLoaded(): Boolean
    external fun freeModel()

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

    /// [shortCtx] kürzt das 30-s-Encoder-Fenster auf die tatsächliche Audiolänge.
    external fun transcribe(samples: FloatArray, language: String, shortCtx: Boolean): String
}
