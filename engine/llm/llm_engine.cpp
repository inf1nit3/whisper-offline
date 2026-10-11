#include "llm_engine.h"

#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "common.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

llama_model   *g_model = nullptr;
llama_context *g_ctx   = nullptr;
common_chat_templates_ptr g_tmpls;
std::string g_path;
int  g_n_ctx = 0;
int  g_threads = 0;
std::string g_err;
std::string g_info;

std::atomic<bool> g_abort{false};
std::atomic<bool> g_cancelled{false};
std::atomic<int>  g_generated{0};

le_log_sink g_sink = nullptr;

void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (g_sink != nullptr) { g_sink((int) level, text ? text : ""); return; }
    if (level >= GGML_LOG_LEVEL_ERROR && text != nullptr) fputs(text, stderr);
}

bool abort_cb(void *) { return g_abort.load(); }

void free_all() {
    g_tmpls.reset();
    if (g_ctx != nullptr)   { llama_free(g_ctx);         g_ctx = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    g_path.clear();
    g_n_ctx = 0;
    g_info.clear();
}

/// Wie bei Whisper: nur die großen Kerne, höchstens 8. Auf Android kommt die
/// Zahl von der Whisper-Engine (gleiche Erkennung), sonst grob die Hälfte der
/// logischen Kerne (Hyperthreading bringt bei Matmuls nichts).
int default_threads() {
    const unsigned hw = std::thread::hardware_concurrency();
    int n = hw > 1 ? (int) hw / 2 : 1;
    return std::clamp(n, 1, 8);
}

/// Denkmodelle (Qwen3 u. a.) schreiben trotz abgeschaltetem Denkmodus
/// manchmal einen leeren <think></think>-Block vorweg.
std::string strip_thinking(std::string s) {
    for (;;) {
        const size_t a = s.find("<think>");
        if (a == std::string::npos) break;
        const size_t b = s.find("</think>", a);
        if (b == std::string::npos) { s.erase(a); break; }
        s.erase(a, b + 8 - a);
    }
    const size_t first = s.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    const size_t last = s.find_last_not_of(" \t\r\n");
    return s.substr(first, last - first + 1);
}

/// Prompt in Stücken von höchstens n_batch Tokens auswerten; nur das letzte
/// liefert Logits.
bool eval_tokens(llama_batch_ext *batch, const llama_token *tokens, int n, int pos0, int n_batch) {
    for (int off = 0; off < n; off += n_batch) {
        const int len = std::min(n_batch, n - off);
        llama_batch_ext_clear(batch);
        for (int i = 0; i < len; i++) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[off + i]);
            const llama_pos pos = pos0 + off + i;
            llama_batch_ext_set_pos(batch, idx, &pos);
        }
        if (off + len == n) llama_batch_ext_set_output_logits(batch, len - 1, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) return false;
    }
    return true;
}

} // namespace

extern "C" {

void le_set_log_sink(le_log_sink sink) { g_sink = sink; }

bool le_load(const char *model_path, int n_ctx, int n_threads) {
    if (model_path == nullptr) return false;
    g_err.clear();
    if (n_ctx <= 0) n_ctx = 4096;

    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(log_cb, nullptr);
        // Unter Windows liegen die CPU-Varianten als eigene DLLs neben der
        // EXE (GGML_BACKEND_DL); beim statischen Android-Build ist das ein No-op.
        ggml_backend_load_all();
        llama_backend_init();
        backend_ready = true;
    }

    if (g_model != nullptr && g_path == model_path && g_n_ctx >= n_ctx) return true;
    free_all();

    g_threads = n_threads > 0 ? n_threads : default_threads();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU: auf Handys und Büro-PCs der verlässliche Weg
    g_model = llama_model_load_from_file(model_path, mparams);
    if (g_model == nullptr) {
        g_err = "Modell nicht ladbar — Datei beschädigt oder zu wenig Speicher";
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = g_threads;
    cparams.n_threads_batch = g_threads;
    cparams.abort_callback = abort_cb;
    cparams.abort_callback_data = nullptr;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        g_err = "Kontext nicht anlegbar — zu wenig Arbeitsspeicher?";
        free_all();
        return false;
    }

    try {
        g_tmpls = common_chat_templates_init(g_model, "");
    } catch (const std::exception &e) {
        g_err = std::string("Chat-Vorlage des Modells nicht lesbar: ") + e.what();
        free_all();
        return false;
    }

    g_path = model_path;
    g_n_ctx = (int) llama_n_ctx(g_ctx);

    char desc[128] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    g_info = std::string(desc) + " \xC2\xB7 " + std::to_string(g_threads) + " Threads";
    return true;
}

bool le_is_loaded(void) { return g_model != nullptr && g_ctx != nullptr; }

void le_free(void) { free_all(); }

void le_string_free(char *s) { free(s); }

void le_cancel(void) { g_abort.store(true); }
bool le_was_cancelled(void) { return g_cancelled.load(); }
int  le_generated_tokens(void) { return g_generated.load(); }
int  le_n_ctx(void) { return g_n_ctx; }
const char *le_last_error(void) { return g_err.c_str(); }
const char *le_info(void) { return g_info.c_str(); }

int le_count_tokens(const char *text) {
    if (!le_is_loaded() || text == nullptr) return -1;
    return (int) common_tokenize(llama_model_get_vocab(g_model), text, false, false).size();
}

char *le_generate(const char *system, const char *user, int max_tokens) {
    g_err.clear();
    g_abort.store(false);
    g_cancelled.store(false);
    g_generated.store(0);
    if (!le_is_loaded()) { g_err = "kein Modell geladen"; return nullptr; }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    common_chat_templates_inputs inputs;
    if (system != nullptr && system[0] != '\0') {
        common_chat_msg m; m.role = "system"; m.content = system;
        inputs.messages.push_back(m);
    }
    common_chat_msg u; u.role = "user"; u.content = user ? user : "";
    inputs.messages.push_back(u);
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = false; // Bereinigen braucht kein Nachdenken — nur Zeit

    std::string prompt;
    try {
        prompt = common_chat_templates_apply(g_tmpls.get(), inputs).prompt;
    } catch (const std::exception &e) {
        g_err = std::string("Chat-Vorlage fehlgeschlagen: ") + e.what();
        return nullptr;
    }

    std::vector<llama_token> tokens = common_tokenize(vocab, prompt, true, true);
    // Viele Vorlagen schreiben das BOS-Token selbst; mit add_special käme es doppelt.
    const llama_token bos = llama_vocab_bos(vocab);
    if (tokens.size() >= 2 && tokens[0] == bos && tokens[1] == bos) tokens.erase(tokens.begin());

    const int n_prompt = (int) tokens.size();
    if (n_prompt >= g_n_ctx - 8) {
        g_err = "Text zu lang für das Modell (" + std::to_string(n_prompt) + " von " +
                std::to_string(g_n_ctx) + " Tokens)";
        return nullptr;
    }
    int budget = g_n_ctx - n_prompt - 1;
    if (max_tokens > 0) budget = std::min(budget, max_tokens);

    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Niedrige Temperatur: Bereinigen soll den Text treu wiedergeben, nicht
    // kreativ umschreiben. Leichte Wiederholungsstrafe gegen Endlosschleifen
    // kleiner Modelle.
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch_ext *batch = llama_batch_ext_init(g_ctx);
    std::string out;
    bool ok = eval_tokens(batch, tokens.data(), n_prompt, 0, 512);
    int pos = n_prompt;

    for (int i = 0; ok && i < budget; i++) {
        if (g_abort.load()) { ok = false; break; }
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char buf[256];
        const int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, (size_t) n);
        g_generated.store(i + 1);
        ok = eval_tokens(batch, &tok, 1, pos++, 512);
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);

    if (!ok) {
        if (g_abort.load()) g_cancelled.store(true);
        else if (g_err.empty()) g_err = "Berechnung fehlgeschlagen";
        g_abort.store(false);
        return nullptr;
    }
    g_abort.store(false);

    const std::string clean = strip_thinking(out);
    char *res = (char *) malloc(clean.size() + 1);
    if (res == nullptr) return nullptr;
    memcpy(res, clean.c_str(), clean.size() + 1);
    return res;
}

} // extern "C"
