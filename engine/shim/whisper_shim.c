// Flache C-Schnittstelle für die Avalonia-App unter Windows.
//
// Zweck: das Modell bleibt zwischen zwei Diktaten geladen. Der bisherige Weg
// (whisper-cli.exe als Unterprozess) las bei jedem Tastendruck 180+ MB neu von
// der Platte — für einen Dienst, der im Hintergrund auf einen Hotkey wartet,
// ist das der größte Einzelposten.
//
// Bewusst nur Primitive und Zeiger in der Signatur: so bleibt das P/Invoke auf
// C#-Seite ohne Struct-Marshalling.
#include "whisper_engine.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#  define WS_API __declspec(dllexport)
#  include <windows.h>
#else
#  define WS_API
#  include <dlfcn.h>
#endif

/// Verzeichnis dieser DLL bestimmen und dem Kern mitteilen, wo die
/// ggml-Backend-Module liegen. ggml sucht sonst nur beim EXE-Pfad und im
/// Arbeitsverzeichnis — die Module liegen aber neben dieser DLL in engine/.
/// So muss die App den Pfad nicht kennen.
static void locate_backends(void) {
    static int done = 0;
    if (done) return;
    done = 1;

    char path[1024] = {0};

#if defined(_WIN32)
    HMODULE self = NULL;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                            GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCSTR) (void *) &locate_backends, &self)) return;
    if (GetModuleFileNameA(self, path, sizeof(path) - 1) == 0) return;
    char *sep = strrchr(path, '\\');
#else
    Dl_info info;
    if (dladdr((void *) &locate_backends, &info) == 0 || info.dli_fname == NULL) return;
    snprintf(path, sizeof(path), "%s", info.dli_fname);
    char *sep = strrchr(path, '/');
#endif

    if (sep == NULL) return;
    *sep = '\0';
    we_set_backend_dir(path);
}

WS_API int ws_load(const char *model_path, int use_gpu) {
    locate_backends();
    return we_load(model_path, use_gpu != 0) ? 1 : 0;
}

WS_API int ws_is_loaded(void) {
    return we_is_loaded() ? 1 : 0;
}

WS_API void ws_free(void) {
    we_free();
}

/// Rückgabe mit ws_string_free wieder freigeben. NULL bei Fehler.
WS_API char *ws_transcribe(const float *samples, int n_samples,
                           const char *lang, int short_ctx) {
    return we_transcribe(samples, n_samples, lang, short_ctx != 0);
}

WS_API void ws_string_free(char *s) {
    we_string_free(s);
}

WS_API const char *ws_backend_info(void) { return we_backend_info(); }
WS_API const char *ws_last_timings(void) { return we_last_timings(); }
WS_API int ws_last_audio_ctx(void)       { return we_last_audio_ctx(); }
WS_API int ws_threads(void)              { return we_threads(); }

/// 0 = keins, 1 = Whisper, 2 = Parakeet.
WS_API int ws_engine_kind(void)          { return we_engine_kind(); }
