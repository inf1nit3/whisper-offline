package dev.whisper.transcribe

/// JNI-Zugang zum lokalen Sprachmodell (llama.cpp, libllm_jni.so).
///
/// Eigene Bibliothek neben der Whisper-Engine: llama.cpp bringt eine eigene
/// ggml-Version mit, beide exportieren nur ihre JNI-Funktionen. Ein Modell zur
/// Zeit; Laden und Generieren laufen nacheinander, Abbrechen jederzeit.
object LlmBridge {

    /// Fehlt die Bibliothek (alte APK, fremde ABI), ist Enhance einfach nicht da.
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("llm_jni") }
            .onFailure { android.util.Log.w("LlmBridge", "libllm_jni nicht ladbar", it) }
            .isSuccess
    }

    private val lock = Any()

    fun load(path: String, nCtx: Int, threads: Int): Boolean =
        available && synchronized(lock) { nativeLoad(path, nCtx, threads) }

    val isLoaded: Boolean get() = available && nativeIsLoaded()

    fun free() { if (available) synchronized(lock) { nativeFree() } }

    /// null bei Fehler oder Abbruch ([wasCancelled] unterscheidet).
    fun generate(system: String, user: String, maxTokens: Int): String? =
        if (!available) null else synchronized(lock) { nativeGenerate(system, user, maxTokens) }

    fun cancel() { if (available) nativeCancel() }
    fun wasCancelled(): Boolean = available && nativeWasCancelled()
    fun generatedTokens(): Int = if (available) nativeGeneratedTokens() else 0
    fun countTokens(text: String): Int = if (available) nativeCountTokens(text) else -1
    fun nCtx(): Int = if (available) nativeNCtx() else 0
    fun lastError(): String = if (available) nativeLastError() else "Enhance-Engine fehlt in dieser App-Version"
    fun info(): String = if (available) nativeInfo() else ""

    private external fun nativeLoad(path: String, nCtx: Int, threads: Int): Boolean
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeFree()
    private external fun nativeGenerate(system: String, user: String, maxTokens: Int): String?
    private external fun nativeCancel()
    private external fun nativeWasCancelled(): Boolean
    private external fun nativeGeneratedTokens(): Int
    private external fun nativeCountTokens(text: String): Int
    private external fun nativeNCtx(): Int
    private external fun nativeLastError(): String
    private external fun nativeInfo(): String
}
