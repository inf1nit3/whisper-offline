package dev.whisper.transcribe

object WhisperBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun loadModel(path: String): Boolean
    external fun isModelLoaded(): Boolean
    external fun freeModel()
    external fun transcribe(samples: FloatArray, language: String): String
}
