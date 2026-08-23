#include "whisper_engine.h"

#include <whisper.h>
#include <parakeet.h>
#include <ggml-backend.h>
#include <ctype.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <unistd.h>
#endif

#if defined(__aarch64__) && !defined(__APPLE__)
#  include <sys/auxv.h>
#  include <asm/hwcap.h>
#  ifndef HWCAP2_I8MM
#    define HWCAP2_I8MM (1 << 13)
#  endif
#endif

static struct whisper_context  *g_ctx = NULL;
static struct parakeet_context *g_pk  = NULL;
static int  g_kind = WE_ENGINE_NONE;
static int  g_threads = 0;
static bool g_gpu = false;
static char g_path[1024] = {0};
static int  g_last_audio_ctx = 0;
static char g_backend[64] = {0};

// ---------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------
static we_log_sink g_sink = NULL;
static char   g_log[4096];
static size_t g_log_len = 0;
static bool   g_capture = false;

void we_set_log_sink(we_log_sink sink) { g_sink = sink; }

// ggml sucht Backend-Module nur im Verzeichnis der ausführbaren Datei und im
// Arbeitsverzeichnis (ggml-backend-reg.cpp) — nicht neben der DLL, die es
// geladen hat. Unter Windows liegen die Module aber in engine/, die EXE eine
// Ebene höher. Ohne diesen Hinweis findet ggml nichts.
static char g_backend_dir[1024] = {0};

void we_set_backend_dir(const char *dir) {
    if (dir == NULL) { g_backend_dir[0] = '\0'; return; }
    snprintf(g_backend_dir, sizeof(g_backend_dir), "%s", dir);
}

static void engine_log_cb(enum ggml_log_level level, const char *text, void *user_data) {
    (void) user_data;
    if (g_capture && text != NULL) {
        const size_t n = strlen(text);
        if (g_log_len + n < sizeof(g_log)) {
            memcpy(g_log + g_log_len, text, n);
            g_log_len += n;
            g_log[g_log_len] = '\0';
        }
    }
    if (g_sink != NULL) g_sink((int) level, text ? text : "");
}

// ---------------------------------------------------------------------------
// Threadzahl
// ---------------------------------------------------------------------------
// ggml synchronisiert seine Worker nach jeder Operation über Spin-Barrieren:
// der langsamste Thread taktet den gesamten Graphen. Kleine Effizienzkerne
// mitrechnen zu lassen bremst deshalb, statt zu helfen — also zählen wir nur
// die großen Kerne.

static int clamp_threads(int n, long online) {
    const int fallback = (int) (online > 6 ? 6 : (online < 1 ? 1 : online));
    if (n < 2) return fallback;
    return n > 8 ? 8 : n;
}

#if defined(_WIN32)

/// Windows kennt die Effizienzklasse jedes Kerns über die CPU-Sets-API.
/// Auf Hybrid-CPUs (P- und E-Kerne) bleiben die E-Kerne so außen vor.
static int detect_threads(void) {
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    const long online = (long) si.dwNumberOfProcessors;

    ULONG size = 0;
    GetSystemCpuSetInformation(NULL, 0, &size, GetCurrentProcess(), 0);
    if (size == 0) return clamp_threads(0, online);

    BYTE *buf = (BYTE *) malloc(size);
    if (buf == NULL) return clamp_threads(0, online);

    int big = 0;
    if (GetSystemCpuSetInformation((PSYSTEM_CPU_SET_INFORMATION) buf, size, &size,
                                   GetCurrentProcess(), 0)) {
        BYTE best = 0;
        for (ULONG off = 0; off + sizeof(SYSTEM_CPU_SET_INFORMATION) <= size; ) {
            PSYSTEM_CPU_SET_INFORMATION info = (PSYSTEM_CPU_SET_INFORMATION) (buf + off);
            if (info->Size == 0) break;
            if (info->Type == CpuSetInformation && info->CpuSet.EfficiencyClass > best)
                best = info->CpuSet.EfficiencyClass;
            off += info->Size;
        }
        for (ULONG off = 0; off + sizeof(SYSTEM_CPU_SET_INFORMATION) <= size; ) {
            PSYSTEM_CPU_SET_INFORMATION info = (PSYSTEM_CPU_SET_INFORMATION) (buf + off);
            if (info->Size == 0) break;
            if (info->Type == CpuSetInformation && info->CpuSet.EfficiencyClass == best)
                big++;
            off += info->Size;
        }
    }
    free(buf);
    return clamp_threads(big, online);
}

#else

/// Erkennung über die ARM-Part-Nummer aus /proc/cpuinfo. SoCs ohne
/// Little-Cluster (z. B. Dimensity 9500: 1× C1-Ultra, 3× C1-Premium,
/// 4× C1-Pro) bekommen so alle acht Kerne.
static int little_core_part(unsigned int part) {
    switch (part) {
        case 0xd03: // Cortex-A53
        case 0xd04: // Cortex-A35
        case 0xd05: // Cortex-A55
        case 0xd46: // Cortex-A510
        case 0xd80: // Cortex-A520
            return 1;
        default:
            return 0;
    }
}

static int detect_threads(void) {
    const long online = sysconf(_SC_NPROCESSORS_ONLN);

    FILE *f = fopen("/proc/cpuinfo", "r");
    if (f == NULL) return clamp_threads(0, online);

    int big = 0, seen = 0;
    char line[256];
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strncmp(line, "CPU part", 8) != 0) continue;
        const char *colon = strchr(line, ':');
        if (colon == NULL) continue;
        const unsigned int part = (unsigned int) strtoul(colon + 1, NULL, 0);
        seen++;
        if (!little_core_part(part)) big++;
    }
    fclose(f);

    if (seen == 0) return clamp_threads(0, online);
    return clamp_threads(big, online);
}

#endif

int we_threads(void) { return g_threads > 0 ? g_threads : detect_threads(); }

// ---------------------------------------------------------------------------
// Audio-Kontext
// ---------------------------------------------------------------------------
// Whisper encodiert immer ein volles 30-Sekunden-Fenster. `audio_ctx` kürzt es.
// whisper.cpp verlangt n_frames <= audio_ctx * 2 bei 100 Mel-Frames je Sekunde:
// nötig sind also 50 Positionen je Audiosekunde. Darauf kommt ein halber
// 256er-Block Reserve, dann wird auf ein Vielfaches von 256 aufgerundet (so legt
// whisper.cpp den Cross-Attention-Cache ohnehin an).
#define WE_CTX_PER_SECOND 50
#define WE_CTX_MAX      1500

static int audio_ctx_for(int n_samples) {
    const int needed = (n_samples / WHISPER_SAMPLE_RATE + 1) * WE_CTX_PER_SECOND;
    int ctx = ((needed + 128 + 255) / 256) * 256;
    if (ctx < 256) ctx = 256;
    if (ctx >= WE_CTX_MAX) return 0; // 0 = Modellvorgabe, kein Kürzen
    return ctx;
}

// ---------------------------------------------------------------------------
// Backend
// ---------------------------------------------------------------------------
bool we_has_gpu_backend(void) {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        if (ggml_backend_dev_type(ggml_backend_dev_get(i)) == GGML_BACKEND_DEVICE_TYPE_GPU)
            return true;
    }
    return false;
}

const char *we_backend_info(void) { return g_backend; }
int  we_last_audio_ctx(void)      { return g_last_audio_ctx; }
const char *we_last_timings(void) { return g_log; }
bool we_is_loaded(void)           { return g_ctx != NULL || g_pk != NULL; }
int  we_engine_kind(void)         { return g_kind; }

// ---------------------------------------------------------------------------
// Modellfamilie
// ---------------------------------------------------------------------------
// Beide Formate tragen dieselbe ggml-Magic, lassen sich also nicht am Header
// unterscheiden. Der Dateiname entscheidet vor — scheitert das Laden damit,
// wird die andere Familie versucht.
int we_guess_kind(const char *model_path) {
    if (model_path == NULL) return WE_ENGINE_NONE;
    for (const char *p = model_path; *p != '\0'; p++) {
        if (tolower((unsigned char) *p) != 'p') continue;
        static const char needle[] = "parakeet";
        size_t i = 0;
        while (needle[i] != '\0' && p[i] != '\0' &&
               tolower((unsigned char) p[i]) == needle[i]) i++;
        if (needle[i] == '\0') return WE_ENGINE_PARAKEET;
    }
    return WE_ENGINE_WHISPER;
}

static void free_contexts(void) {
    if (g_ctx != NULL) { whisper_free(g_ctx);  g_ctx = NULL; }
    if (g_pk  != NULL) { parakeet_free(g_pk);  g_pk  = NULL; }
    g_kind = WE_ENGINE_NONE;
    g_path[0] = '\0';
}

// ---------------------------------------------------------------------------
// Modell
// ---------------------------------------------------------------------------
bool we_load(const char *model_path, bool use_gpu) {
    if (model_path == NULL) return false;

    // Die arm64-Engine wird mit -march=armv8.6-a+i8mm gebaut. Fehlt die
    // Erweiterung, stürbe ggml mitten im Matmul mit SIGILL — hier gibt es
    // stattdessen eine saubere Fehlermeldung.
#if defined(__aarch64__) && !defined(__APPLE__)
    if ((getauxval(AT_HWCAP2) & HWCAP2_I8MM) == 0) return false;
#endif

    whisper_log_set(engine_log_cb, NULL);
    parakeet_log_set(engine_log_cb, NULL);

    // Beim Windows-Build liegen die Backends als eigene DLLs daneben
    // (GGML_BACKEND_DL) und müssen einmal eingelesen werden; ohne das findet
    // ggml nicht einmal die CPU-Variante. Beim statischen Android-Build ist der
    // Aufruf wirkungslos.
    static bool backends_loaded = false;
    if (!backends_loaded) {
        if (g_backend_dir[0] != '\0') ggml_backend_load_all_from_path(g_backend_dir);
        else                          ggml_backend_load_all();
        backends_loaded = true;
    }

    // Ohne registriertes Backend bricht whisper.cpp nicht sauber ab, sondern
    // reißt per GGML_ASSERT den ganzen Prozess mit (ggml-backend.cpp,
    // make_buft_list). Hier lieber selbst scheitern — dann greift auf Windows
    // der Rückfall auf whisper-cli, statt dass die App verschwindet.
    if (ggml_backend_dev_count() == 0) {
        if (g_sink != NULL)
            g_sink(2 /* GGML_LOG_LEVEL_ERROR */,
                   "kein ggml-Backend gefunden — liegen die ggml-*-Module neben der Engine?\n");
        return false;
    }

    // Ohne einkompiliertes GPU-Backend bleibt die Anfrage wirkungslos — dann
    // soll auch die Statuszeile nicht "GPU" behaupten.
    const bool want_gpu = use_gpu && we_has_gpu_backend();

    // Dasselbe Modell schon im Speicher? Dann nicht erneut hunderte MB von der
    // Platte lesen — genau davon lebt der Hintergrundbetrieb.
    if (we_is_loaded() && g_gpu == want_gpu && strcmp(g_path, model_path) == 0) return true;

    free_contexts();

    const int guess = we_guess_kind(model_path);
    // Erst die geratene Familie, dann die andere. Die ggml-Magic ist bei beiden
    // gleich, ein Fehlschlag ist also der einzige verlässliche Test.
    const int order[2] = {
        guess,
        guess == WE_ENGINE_PARAKEET ? WE_ENGINE_WHISPER : WE_ENGINE_PARAKEET,
    };

    for (int i = 0; i < 2 && g_kind == WE_ENGINE_NONE; i++) {
        if (order[i] == WE_ENGINE_PARAKEET) {
            struct parakeet_context_params pparams = parakeet_context_default_params();
            pparams.use_gpu = want_gpu;
            g_pk = parakeet_init_from_file_with_params(model_path, pparams);
            if (g_pk != NULL) g_kind = WE_ENGINE_PARAKEET;
        } else {
            struct whisper_context_params cparams = whisper_context_default_params();
            cparams.use_gpu = want_gpu;
            cparams.flash_attn = true; // weniger Speicher-Verkehr, auf CPU leicht schneller
            g_ctx = whisper_init_from_file_with_params(model_path, cparams);
            if (g_ctx != NULL) g_kind = WE_ENGINE_WHISPER;
        }
    }

    if (g_kind == WE_ENGINE_NONE) return false;

    snprintf(g_path, sizeof(g_path), "%s", model_path);
    g_gpu = want_gpu;
    g_threads = detect_threads();
    snprintf(g_backend, sizeof(g_backend), "%s \xC2\xB7 %s \xC2\xB7 %d Threads",
             g_kind == WE_ENGINE_PARAKEET ? "Parakeet" : "Whisper",
             g_gpu ? "GPU" : "CPU", g_threads);
    return true;
}

void we_free(void) { free_contexts(); }

void we_string_free(char *s) { free(s); }

/// Sammelt die Segmente der jeweiligen Engine zu einem Text.
/// `get` liefert das i-te Segment, `n` deren Anzahl.
static char *collect_segments(int n, const char *(*get)(int)) {
    size_t cap = 4096;
    char *out = (char *) malloc(cap);
    if (out == NULL) return NULL;
    size_t len = 0;
    out[0] = '\0';

    for (int i = 0; i < n; i++) {
        const char *seg = get(i);
        if (seg == NULL) continue;
        const size_t seg_len = strlen(seg);
        while (len + seg_len + 2 > cap) {
            cap *= 2;
            char *tmp = (char *) realloc(out, cap);
            if (tmp == NULL) { free(out); return NULL; }
            out = tmp;
        }
        memcpy(out + len, seg, seg_len);
        len += seg_len;
        out[len++] = '\n';
        out[len] = '\0';
    }
    return out;
}

static const char *whisper_seg(int i)  { return whisper_full_get_segment_text(g_ctx, i); }
static const char *parakeet_seg(int i) { return parakeet_full_get_segment_text(g_pk, i); }

/// Parakeet TDT: Transducer statt Encoder-Decoder. Kein 30-Sekunden-Fenster,
/// keine Sprachauswahl (v3 ist mehrsprachig), kein Temperatur-Fallback.
/// Gemessen auf macOS-CPU gegen Whisper small q5_1: 2,3 s Audio in 85 ms statt
/// 655 ms, weil Whispers Encoder unabhängig von der Länge ein volles Fenster
/// rechnet.
static char *transcribe_parakeet(const float *samples, int n_samples) {
    struct parakeet_full_params p = parakeet_full_default_params(PARAKEET_SAMPLING_GREEDY);
    p.n_threads = we_threads();
    p.no_context = true;

    g_last_audio_ctx = 0; // kein festes Fenster

    parakeet_reset_timings(g_pk);
    const int rc = parakeet_full(g_pk, p, samples, n_samples);

    g_log_len = 0;
    g_log[0] = '\0';
    g_capture = true;
    parakeet_print_timings(g_pk);
    g_capture = false;

    if (rc != 0) return NULL;
    return collect_segments(parakeet_full_n_segments(g_pk), parakeet_seg);
}

char *we_transcribe(const float *samples, int n_samples, const char *lang, bool short_ctx) {
    if (!we_is_loaded() || samples == NULL || n_samples <= 0) return NULL;
    if (g_kind == WE_ENGINE_PARAKEET) return transcribe_parakeet(samples, n_samples);
    if (lang == NULL) lang = "de";

    // Greedy statt Beam Search: deutlich schnellere Decoder-Phase, minimale
    // Genauigkeitseinbuße.
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.strategy = WHISPER_SAMPLING_GREEDY;
    p.n_threads = we_threads();
    p.language = lang;
    p.translate = false;
    p.print_progress = false;
    p.print_special = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_nst = true;
    p.no_context = true;

    // Passt das Audio in ein 30-s-Fenster, dann in einem Rutsch durchziehen.
    // Sonst kann whisper.cpp das Segment am Zeitstempel des letzten Tokens
    // beenden, `seek` dorthin setzen und ein weiteres volles Fenster encodieren.
    // Nur bei kurzem Audio: `seek` wird ungeprüft weitergezählt, bei längeren
    // Dateien fiele der Rest sonst hinten runter.
    p.single_segment = n_samples <= WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE;

    p.audio_ctx = short_ctx ? audio_ctx_for(n_samples) : 0;
    g_last_audio_ctx = p.audio_ctx > 0 ? p.audio_ctx : WE_CTX_MAX;

    whisper_reset_timings(g_ctx);
    const int rc = whisper_full(g_ctx, p, samples, n_samples);

    g_log_len = 0;
    g_log[0] = '\0';
    g_capture = true;
    whisper_print_timings(g_ctx);
    g_capture = false;

    if (rc != 0) return NULL;
    return collect_segments(whisper_full_n_segments(g_ctx), whisper_seg);
}
