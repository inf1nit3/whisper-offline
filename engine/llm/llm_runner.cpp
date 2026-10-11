// llm-runner: das lokale Sprachmodell als eigener Prozess (Windows, macOS-Test).
//
// Warum ein eigener Prozess: llama.cpp bringt seine eigene ggml-Version mit.
// Im Prozess der Windows-App liegen schon die ggml-DLLs von whisper.cpp —
// zwei verschiedene ggml.dll in einem Prozess gehen nicht. Als eigener
// Prozess mit eigenem Ordner (engine/llm/) lädt jede Seite ihre DLLs.
// Abbrechen = Prozess beenden; das Modell wird beim nächsten Mal neu geladen.
//
// Protokoll über stdin/stdout, binär, UTF-8. Jede Anfrage ist eine Kopfzeile,
// gefolgt von genau den angekündigten Bytes:
//
//   LOAD <n_ctx> <n_threads> <len>\n<pfad>
//   GEN <max_tokens> <len_system> <len_user>\n<system><user>
//   COUNT <len>\n<text>
//   FREE\n
//   QUIT\n
//
// Antworten: "OK <len>\n<bytes>" oder "ERR <len>\n<meldung>". Während GEN
// kommen zusätzlich Zeilen "PRG <tokens>\n" als Fortschritt.
#include "llm_engine.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#if defined(_WIN32)
#  include <fcntl.h>
#  include <io.h>
#endif

static std::mutex g_out;

static void reply(const char *kind, const std::string &payload) {
    std::lock_guard<std::mutex> lock(g_out);
    fprintf(stdout, "%s %zu\n", kind, payload.size());
    fwrite(payload.data(), 1, payload.size(), stdout);
    fflush(stdout);
}

static bool read_line(std::string &line) {
    line.clear();
    int c;
    while ((c = fgetc(stdin)) != EOF) {
        if (c == '\n') return true;
        if (c != '\r') line.push_back((char) c);
    }
    return !line.empty();
}

static bool read_bytes(size_t n, std::string &out) {
    out.resize(n);
    size_t got = 0;
    while (got < n) {
        const size_t r = fread(&out[got], 1, n - got, stdin);
        if (r == 0) return false;
        got += r;
    }
    return true;
}

int main() {
#if defined(_WIN32)
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    std::string line, a, b;
    while (read_line(line)) {
        char cmd[16] = {0};
        long p1 = 0, p2 = 0, p3 = 0;
        const int n = sscanf(line.c_str(), "%15s %ld %ld %ld", cmd, &p1, &p2, &p3);
        if (n < 1) continue;

        if (strcmp(cmd, "LOAD") == 0 && n == 4) {
            if (!read_bytes((size_t) p3, a)) break;
            if (le_load(a.c_str(), (int) p1, (int) p2)) reply("OK", le_info());
            else reply("ERR", le_last_error());
        } else if (strcmp(cmd, "GEN") == 0 && n == 4) {
            if (!read_bytes((size_t) p2, a) || !read_bytes((size_t) p3, b)) break;
            std::atomic<bool> done{false};
            std::thread progress([&] {
                int last = -1;
                while (!done.load()) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(250));
                    const int t = le_generated_tokens();
                    if (t != last && !done.load()) {
                        last = t;
                        std::lock_guard<std::mutex> lock(g_out);
                        fprintf(stdout, "PRG %d\n", t);
                        fflush(stdout);
                    }
                }
            });
            char *res = le_generate(a.c_str(), b.c_str(), (int) p1);
            done.store(true);
            progress.join();
            if (res != nullptr) { reply("OK", res); le_string_free(res); }
            else reply("ERR", le_last_error());
        } else if (strcmp(cmd, "COUNT") == 0 && n == 2) {
            if (!read_bytes((size_t) p1, a)) break;
            reply("OK", std::to_string(le_count_tokens(a.c_str())));
        } else if (strcmp(cmd, "FREE") == 0) {
            le_free();
            reply("OK", "");
        } else if (strcmp(cmd, "QUIT") == 0) {
            break;
        } else {
            reply("ERR", "unbekannter Befehl");
        }
    }
    le_free();
    return 0;
}
