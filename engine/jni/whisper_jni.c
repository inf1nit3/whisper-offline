// JNI-Brücke zwischen der Kotlin-App und dem gemeinsamen Engine-Kern.
// Die eigentliche Logik steht in engine/core/whisper_engine.c — hier nur
// Marshalling. Paket/Objekt auf Kotlin-Seite: dev.whisper.transcribe.WhisperBridge
#include <jni.h>
#include <android/log.h>
#include <string.h>

#include "whisper_engine.h"

#define TAG "whisper-jni"

static void android_log_sink(int level, const char *text) {
    __android_log_print(level == 2 /* GGML_LOG_LEVEL_ERROR */ ? ANDROID_LOG_ERROR
                                                              : ANDROID_LOG_INFO,
                        TAG, "%s", text);
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_WhisperBridge_loadModel(JNIEnv *env, jobject thiz,
                                                    jstring jpath, jboolean juse_gpu) {
    (void) thiz;
    we_set_log_sink(android_log_sink);

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const bool ok = we_load(path, juse_gpu == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (!ok) __android_log_print(ANDROID_LOG_ERROR, TAG, "Modell konnte nicht geladen werden");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_WhisperBridge_isModelLoaded(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return we_is_loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_whisper_transcribe_WhisperBridge_freeModel(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    we_free();
}

JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_WhisperBridge_backendInfo(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, we_backend_info());
}

JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_WhisperBridge_lastTimings(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, we_last_timings());
}

JNIEXPORT jint JNICALL
Java_dev_whisper_transcribe_WhisperBridge_lastAudioCtx(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) we_last_audio_ctx();
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_WhisperBridge_hasGpuBackend(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return we_has_gpu_backend() ? JNI_TRUE : JNI_FALSE;
}

/// 0 = keins, 1 = Whisper, 2 = Parakeet.
JNIEXPORT jint JNICALL
Java_dev_whisper_transcribe_WhisperBridge_engineKind(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) we_engine_kind();
}

JNIEXPORT jint JNICALL
Java_dev_whisper_transcribe_WhisperBridge_detectThreads(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) we_threads();
}

JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_WhisperBridge_transcribe(JNIEnv *env, jobject thiz,
                                                     jfloatArray jsamples, jstring jlang,
                                                     jboolean jshort_ctx) {
    (void) thiz;
    if (!we_is_loaded()) return (*env)->NewStringUTF(env, "[Fehler] Modell nicht geladen");

    const jsize n = (*env)->GetArrayLength(env, jsamples);
    jfloat *samples = (*env)->GetFloatArrayElements(env, jsamples, NULL);
    if (samples == NULL)
        return (*env)->NewStringUTF(env, "[Fehler] Sample-Puffer konnte nicht gelesen werden");

    const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
    char *text = we_transcribe(samples, (int) n, lang, jshort_ctx == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, jlang, lang);
    (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);

    if (text == NULL) return (*env)->NewStringUTF(env, "[Fehler] Transkription fehlgeschlagen");

    jstring result = (*env)->NewStringUTF(env, text);
    we_string_free(text);
    return result;
}
