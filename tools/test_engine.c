// Testharness: lädt den gemeinsamen Engine-Kern (whisper_shim) via dlopen
// und prüft, ob ein Parakeet-ggml-Modell geladen und transkribiert wird.
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>

typedef int  (*we_load_fn)(const char *, int);
typedef const char *(*we_backend_fn)(void);
typedef const char *(*we_err_fn)(void);
typedef char *(*we_tr_fn)(const float *, int, const char *, int);
typedef void (*we_free_str_fn)(char *);

int main(int argc, char **argv) {
    if (argc < 4) { printf("usage: test <shim.dylib> <wav> <model>\n"); return 1; }
    void *h = dlopen(argv[1], RTLD_NOW);
    if (!h) { printf("dlopen fehlgeschlagen: %s\n", dlerror()); return 1; }
    we_load_fn load = (we_load_fn) dlsym(h, "ws_load");
    we_backend_fn info = (we_backend_fn) dlsym(h, "ws_backend_info");
    we_err_fn err = (we_err_fn) dlsym(h, "ws_last_error");
    we_tr_fn tr = (we_tr_fn) dlsym(h, "ws_transcribe");
    we_free_str_fn freestr = (we_free_str_fn) dlsym(h, "ws_string_free");
    if (!load || !tr) { printf("Symbole fehlen\n"); return 1; }

    // 16-kHz-Mono-WAV (16-Bit-PCM, 44-Byte-Header) als Floats einlesen
    FILE *f = fopen(argv[2], "rb");
    if (!f) { printf("wav fehlt\n"); return 1; }
    fseek(f, 0, SEEK_END); long size = ftell(f); fseek(f, 44, SEEK_SET);
    int n = (int)((size - 44) / 2);
    float *samples = malloc((size_t)n * sizeof(float));
    short *pcm = malloc((size_t)n * sizeof(short));
    if (fread(pcm, 2, (size_t)n, f) != (size_t)n) { printf("wav kurz\n"); return 1; }
    fclose(f);
    for (int i = 0; i < n; i++) samples[i] = pcm[i] / 32768.0f;

    printf("Lade %s …\n", argv[3]);
    if (!load(argv[3], 0)) {
        printf("LOAD FEHLGESCHLAGEN: %s\n", err ? err() : "(keine Ursache hinterlegt)");
        return 2;
    }
    printf("geladen — Backend: %s\n", info ? info() : "?");
    char *text = tr(samples, n, "de", 0);
    printf("Transkript: %s\n", text ? text : "(NULL)");
    if (text && freestr) freestr(text);
    return 0;
}
