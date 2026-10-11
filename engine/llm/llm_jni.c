// JNI-Brücke für das lokale Sprachmodell. Logik in llm_engine.cpp — hier nur
// Marshalling. Kotlin-Seite: dev.whisper.transcribe.LlmBridge
#include <jni.h>
#include <android/log.h>
#include <string.h>

#include "llm_engine.h"

#define TAG "llm-jni"

static void android_log_sink(int level, const char *text) {
    // 3 = WARN, 4 = ERROR; 5 = CONT (Fortsetzungszeilen, z. B. Ladepunkte) weglassen
    if (level < 3 || level > 4) return;
    __android_log_print(level >= 4 ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeLoad(JNIEnv *env, jobject thiz, jstring jpath,
                                                 jint n_ctx, jint n_threads) {
    (void) thiz;
    le_set_log_sink(android_log_sink);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const bool ok = le_load(path, n_ctx, n_threads);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeIsLoaded(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return le_is_loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeFree(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    le_free();
}

/// null bei Fehler oder Abbruch.
JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeGenerate(JNIEnv *env, jobject thiz, jstring jsystem,
                                                     jstring juser, jint max_tokens) {
    (void) thiz;
    const char *sys = (*env)->GetStringUTFChars(env, jsystem, NULL);
    const char *user = (*env)->GetStringUTFChars(env, juser, NULL);
    char *res = le_generate(sys, user, max_tokens);
    (*env)->ReleaseStringUTFChars(env, jsystem, sys);
    (*env)->ReleaseStringUTFChars(env, juser, user);
    if (res == NULL) return NULL;
    // NewStringUTF erwartet „modified UTF-8“; Emojis (4-Byte-Sequenzen) aus
    // dem Modell würden damit zerbrechen. Über ein byte[] und den
    // String-Konstruktor mit UTF-8 kommt alles heil an.
    const jsize len = (jsize) strlen(res);
    jbyteArray bytes = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *) res);
    le_string_free(res);
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jmethodID ctor = (*env)->GetMethodID(env, strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    return (jstring) (*env)->NewObject(env, strClass, ctor, bytes, charset);
}

JNIEXPORT void JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeCancel(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    le_cancel();
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeWasCancelled(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return le_was_cancelled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeGeneratedTokens(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) le_generated_tokens();
}

JNIEXPORT jint JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeCountTokens(JNIEnv *env, jobject thiz, jstring jtext) {
    (void) thiz;
    const char *text = (*env)->GetStringUTFChars(env, jtext, NULL);
    const int n = le_count_tokens(text);
    (*env)->ReleaseStringUTFChars(env, jtext, text);
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeNCtx(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) le_n_ctx();
}

JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeLastError(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, le_last_error());
}

JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_LlmBridge_nativeInfo(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, le_info());
}
