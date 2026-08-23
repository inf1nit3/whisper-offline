// Gemeinsamer Engine-Kern für Android (JNI) und Windows (DLL-Shim).
//
// Hält genau einen Whisper-Kontext am Leben und kapselt die Tuning-Arbeit, die
// auf beiden Plattformen gleich ist: Threadzahl nach großen Kernen, greedy
// statt Beam Search, `single_segment` bei kurzem Audio, optional gekürztes
// Encoder-Fenster. Die Plattform-Wrapper enthalten nur noch Marshalling.
#ifndef WHISPER_ENGINE_H
#define WHISPER_ENGINE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Ziel für Logausgaben der Engine. level entspricht ggml_log_level.
typedef void (*we_log_sink)(int level, const char *text);

/// Welche der beiden Modellfamilien geladen ist.
enum we_engine_kind {
    WE_ENGINE_NONE = 0,
    WE_ENGINE_WHISPER = 1,
    WE_ENGINE_PARAKEET = 2,
};

/// Familie des geladenen Modells; vor we_load: WE_ENGINE_NONE.
int we_engine_kind(void);

/// Rät die Familie anhand des Dateinamens, ohne etwas zu laden.
/// Nützlich für die Oberfläche: Parakeet kennt keine Sprachauswahl.
int we_guess_kind(const char *model_path);

/// Einmalig vor we_load setzen; NULL schaltet die Weiterleitung ab.
void we_set_log_sink(we_log_sink sink);

/// Verzeichnis, in dem die ggml-Backend-Module liegen. Muss vor we_load gesetzt
/// werden, wenn sie nicht neben der ausführbaren Datei liegen: ggml sucht sonst
/// nur im EXE-Verzeichnis und im Arbeitsverzeichnis, nicht neben der DLL.
void we_set_backend_dir(const char *dir);

/// Lädt das Modell. Ist dasselbe Modell mit derselben GPU-Einstellung bereits
/// geladen, passiert nichts (kein erneutes Lesen von 180+ MB).
/// Gibt false zurück, wenn die CPU die nötigen Erweiterungen nicht hat oder
/// die Datei nicht ladbar ist.
bool we_load(const char *model_path, bool use_gpu);

bool we_is_loaded(void);
void we_free(void);

/// Transkribiert 16-kHz-Mono-Samples in [-1, 1].
/// `lang` ist ein Sprachcode ("de", "en"); "auto" kostet bei Whisper einen
/// kompletten zusätzlichen Encoder-Durchlauf zur Spracherkennung. Parakeet ist
/// von Haus aus mehrsprachig und ignoriert den Parameter.
/// `short_ctx` kürzt das 30-s-Encoder-Fenster auf die tatsächliche Audiolänge;
/// bei Parakeet wirkungslos, weil es kein festes Fenster gibt.
/// Rückgabe: mit malloc angelegter Text, den der Aufrufer per we_string_free
/// wieder freigibt. NULL bei Fehler.
char *we_transcribe(const float *samples, int n_samples, const char *lang, bool short_ctx);

void we_string_free(char *s);

/// Anzahl der großen CPU-Kerne, die die Engine benutzt.
int we_threads(void);

/// Ist ein GPU-Backend einkompiliert und ein Gerät vorhanden?
bool we_has_gpu_backend(void);

/// "CPU · 8 Threads" bzw. "GPU · 8 Threads".
const char *we_backend_info(void);

/// Encoder-Fenster des letzten Laufs; 1500 = ungekürzt.
int we_last_audio_ctx(void);

/// Zeitaufschlüsselung des letzten Laufs, so wie whisper.cpp sie meldet.
const char *we_last_timings(void);

#ifdef __cplusplus
}
#endif

#endif // WHISPER_ENGINE_H
