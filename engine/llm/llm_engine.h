// Lokales Sprachmodell (llama.cpp) für „Enhance“: Transkripte bereinigen,
// formatieren, markierten Text umformen, Meeting-Notizen schreiben.
//
// Gemeinsamer Kern für Android (JNI, libllm_jni.so) und Windows (eigener
// Prozess llm-runner.exe). Hält genau ein Modell und einen Kontext; die
// Plattform-Wrapper sperren selbst, hier wird nichts gesperrt.
#ifndef LLM_ENGINE_H
#define LLM_ENGINE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*le_log_sink)(int level, const char *text);

/// Ziel für Logausgaben; NULL = nur Fehler auf stderr.
void le_set_log_sink(le_log_sink sink);

/// Lädt ein GGUF-Modell. Ist dasselbe Modell mit mindestens diesem Kontext
/// schon geladen, passiert nichts. n_threads <= 0: Engine wählt selbst.
bool le_load(const char *model_path, int n_ctx, int n_threads);

bool le_is_loaded(void);
void le_free(void);

/// Ein Durchgang ohne Verlauf: System-Anweisung + Nutzertext → Antwort.
/// max_tokens <= 0: bis der Kontext voll ist. Rückgabe mit le_string_free
/// freigeben; NULL bei Fehler oder Abbruch (le_was_cancelled unterscheidet).
/// Denkblöcke (<think>…</think>) sind bereits entfernt.
char *le_generate(const char *system, const char *user, int max_tokens);

void le_string_free(char *s);

/// Bricht die laufende Generierung ab (aus einem anderen Thread).
void le_cancel(void);
bool le_was_cancelled(void);

/// Bisher erzeugte Tokens im laufenden Durchgang (für eine Fortschrittsanzeige).
int le_generated_tokens(void);

/// Anzahl Tokens eines Texts mit dem geladenen Modell; -1 ohne Modell.
int le_count_tokens(const char *text);

/// Kontextgröße des geladenen Modells.
int le_n_ctx(void);

/// Ursache des letzten Fehlers; leer, wenn keiner auftrat.
const char *le_last_error(void);

/// "Gemma 3 1B · 8 Threads" o. ä.
const char *le_info(void);

#ifdef __cplusplus
}
#endif

#endif // LLM_ENGINE_H
