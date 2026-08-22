// JNI-Brücke zwischen der Kotlin-App und der whisper.cpp C-API.
// Paket/Objekt auf Kotlin-Seite: dev.whisper.transcribe.WhisperBridge
#include <jni.h>
#include <whisper.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#define TAG "whisper-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct whisper_context *g_ctx = NULL;

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_WhisperBridge_loadModel(JNIEnv *env, jobject thiz, jstring jpath) {
    (void) thiz;
    if (g_ctx != NULL) {
        whisper_free(g_ctx);
        g_ctx = NULL;
    }
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = true;
    g_ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (g_ctx == NULL) {
        LOGE("Modell konnte nicht geladen werden");
        return JNI_FALSE;
    }
    LOGI("Modell geladen");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_whisper_transcribe_WhisperBridge_isModelLoaded(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_ctx != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_whisper_transcribe_WhisperBridge_freeModel(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_ctx != NULL) {
        whisper_free(g_ctx);
        g_ctx = NULL;
    }
}

JNIEXPORT jstring JNICALL
Java_dev_whisper_transcribe_WhisperBridge_transcribe(JNIEnv *env, jobject thiz,
                                                     jfloatArray jsamples, jstring jlang) {
    (void) thiz;
    if (g_ctx == NULL) {
        return (*env)->NewStringUTF(env, "[Fehler] Modell nicht geladen");
    }

    const jsize n = (*env)->GetArrayLength(env, jsamples);
    jfloat *samples = (*env)->GetFloatArrayElements(env, jsamples, NULL);
    if (samples == NULL) {
        return (*env)->NewStringUTF(env, "[Fehler] Sample-Puffer konnte nicht gelesen werden");
    }

    const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
    if (lang == NULL) lang = "de";

    struct whisper_full_params p =
        whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.strategy = WHISPER_SAMPLING_BEAM_SEARCH;
    p.n_threads = sysconf(_SC_NPROCESSORS_ONLN) > 8 ? 8 : (int) sysconf(_SC_NPROCESSORS_ONLN);
    if (p.n_threads < 2) p.n_threads = 2;
    p.language = lang;
    p.translate = false;
    p.print_progress = false;
    p.print_special = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_nst = true;
    p.no_context = true;

    const int rc = whisper_full(g_ctx, p, samples, n);
    (*env)->ReleaseStringUTFChars(env, jlang, lang);

    if (rc != 0) {
        (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);
        LOGE("whisper_full fehlgeschlagen: %d", rc);
        return (*env)->NewStringUTF(env, "[Fehler] Transkription fehlgeschlagen");
    }

    const int n_seg = whisper_full_n_segments(g_ctx);
    size_t cap = 4096;
    char *out = malloc(cap);
    if (out == NULL) {
        (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);
        return (*env)->NewStringUTF(env, "[Fehler] Kein Speicher");
    }
    size_t len = 0;
    out[0] = '\0';

    for (int i = 0; i < n_seg; i++) {
        const char *seg = whisper_full_get_segment_text(g_ctx, i);
        const size_t seg_len = strlen(seg);
        while (len + seg_len + 2 > cap) {
            cap *= 2;
            char *tmp = realloc(out, cap);
            if (tmp == NULL) {
                free(out);
                (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);
                return (*env)->NewStringUTF(env, "[Fehler] Kein Speicher");
            }
            out = tmp;
        }
        memcpy(out + len, seg, seg_len);
        len += seg_len;
        out[len++] = '\n';
        out[len] = '\0';
    }

    (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
