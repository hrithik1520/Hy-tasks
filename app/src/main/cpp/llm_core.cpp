#include "llm_core.h"

#include <algorithm>
#include <vector>

#include "llama.h"

namespace hy {

namespace {
thread_local std::string g_last_error;
int g_last_reused[kSlots] = {};
constexpr int kBatch = 512;

void set_error(const std::string &msg) { g_last_error = msg; }

// Length in bytes of the UTF-8 sequence started by lead byte `c`, or 0 if `c`
// is not a valid lead byte.
int utf8_seq_len(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c & 0xE0) == 0xC0) return 2;
    if ((c & 0xF0) == 0xE0) return 3;
    if ((c & 0xF8) == 0xF0) return 4;
    return 0;
}
}  // namespace

const std::string &last_error() { return g_last_error; }

void backend_init() { llama_backend_init(); }

Engine *open(const std::string &path) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU only on phones
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        set_error("Failed to load model: " + path);
        return nullptr;
    }
    Engine *e = new Engine();
    e->model = model;
    return e;
}

void close(Engine *engine) {
    if (!engine) return;
    for (Session &s : engine->sessions) {
        if (s.ctx) llama_free(s.ctx);
    }
    if (engine->model) llama_model_free(engine->model);
    delete engine;
}

int last_reused(Engine *engine, int slot) {
    if (!engine || slot < 0 || slot >= kSlots) return 0;
    return g_last_reused[slot];
}

size_t complete_utf8_prefix(const std::string &bytes) {
    size_t i = 0;
    const size_t n = bytes.size();
    while (i < n) {
        int len = utf8_seq_len(static_cast<unsigned char>(bytes[i]));
        if (len == 0) {  // stray continuation / invalid byte: let it through
            i += 1;
            continue;
        }
        if (i + len > n) break;  // incomplete trailing sequence
        i += len;
    }
    return i;
}

std::string format_chat(llama_model *model, const std::string &system, const std::string &user) {
    llama_chat_message msgs[2] = {
        {"system", system.c_str()},
        {"user", user.c_str()},
    };
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) {
        std::vector<char> buf(2 * (system.size() + user.size()) + 512);
        int32_t n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t)buf.size());
        if (n > (int32_t)buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t)buf.size());
        }
        if (n > 0) {
            std::string out(buf.data(), n);
            // Reasoning models (Qwen3 family) think out loud unless the turn starts with an empty
            // think block; their own template adds it when enable_thinking is false.
            if (std::string(tmpl).find("enable_thinking") != std::string::npos) out += "<think>\n\n</think>\n\n";
            return out;
        }
    }
    // Fallback: ChatML (Qwen and many others).
    return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user +
           "<|im_end|>\n<|im_start|>assistant\n";
}

namespace {

// (Re)creates the slot's context when settings change.
bool ensure_session(Engine *e, Session &s, const GenParams &p) {
    if (s.ctx && s.n_ctx == p.n_ctx && s.n_threads == p.n_threads) return true;
    if (s.ctx) llama_free(s.ctx);
    s.ctx = nullptr;
    s.cached.clear();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = p.n_ctx;
    cp.n_batch = kBatch;
    cp.n_threads = p.n_threads;
    cp.n_threads_batch = p.n_threads;
    cp.no_perf = true;
    s.ctx = llama_init_from_model(e->model, cp);
    if (!s.ctx) return false;
    s.n_ctx = p.n_ctx;
    s.n_threads = p.n_threads;
    return true;
}

void reset_cache(Session &s) {
    llama_memory_clear(llama_get_memory(s.ctx), true);
    s.cached.clear();
}

}  // namespace

int generate(Engine *engine, int slot, const std::string &system, const std::string &user,
             const GenParams &p, const TextCallback &on_text) {
    if (!engine || !engine->model) {
        set_error("Model not loaded");
        return -1;
    }
    if (slot < 0 || slot >= kSlots) slot = 0;
    llama_model *model = engine->model;
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const std::string prompt = format_chat(model, system, user);

    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        set_error("Tokenization failed");
        return -2;
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        set_error("Tokenization failed");
        return -2;
    }
    if (n_prompt + p.max_tokens > p.n_ctx) {
        set_error("Prompt too long (" + std::to_string(n_prompt) + " tokens)");
        return -3;
    }

    Session &s = engine->sessions[slot];
    if (!ensure_session(engine, s, p)) {
        set_error("Failed to create context");
        return -4;
    }
    llama_context *ctx = s.ctx;

    // Reuse the longest common prefix with what's already in the KV cache. At least one prompt
    // token is always decoded so there are fresh logits to sample from.
    size_t n_keep = 0;
    while (n_keep < s.cached.size() && n_keep < tokens.size() && s.cached[n_keep] == tokens[n_keep]) n_keep++;
    if (n_keep >= tokens.size()) n_keep = tokens.size() - 1;
    if (!llama_memory_seq_rm(llama_get_memory(ctx), 0, (llama_pos)n_keep, -1)) {
        reset_cache(s);  // e.g. recurrent models can't drop a partial sequence
        n_keep = 0;
    }
    s.cached.resize(n_keep);
    g_last_reused[slot] = (int)n_keep;

    for (size_t i = n_keep; i < tokens.size(); i += kBatch) {
        int n = (int)std::min<size_t>(kBatch, tokens.size() - i);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            reset_cache(s);
            set_error("Decode failed");
            return -5;
        }
        s.cached.insert(s.cached.end(), tokens.begin() + i, tokens.begin() + i + n);
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    if (!p.grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, p.grammar.c_str(), "root");
        if (!g) {
            set_error("Invalid grammar");
            llama_sampler_free(smpl);
            return -6;
        }
        llama_sampler_chain_add(smpl, g);
    }
    if (p.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(p.top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(p.top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(p.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(p.seed));
    }

    int result = 0;
    std::string pending;
    char piece[256];
    for (int i = 0; i < p.max_tokens; i++) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, n);
        result++;

        size_t ready = complete_utf8_prefix(pending);
        bool keep_going = true;
        if (ready > 0) {
            keep_going = on_text(pending.substr(0, ready));
            pending.erase(0, ready);
        }
        if (!keep_going || i + 1 == p.max_tokens) break;
        if (llama_decode(ctx, llama_batch_get_one(&tok, 1)) != 0) {
            reset_cache(s);
            set_error("Decode failed");
            result = -5;
            break;
        }
        s.cached.push_back(tok);
    }
    if (!pending.empty() && result >= 0) on_text(pending);

    llama_sampler_free(smpl);
    return result;
}

}  // namespace hy
