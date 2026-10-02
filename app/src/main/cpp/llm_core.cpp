#include "llm_core.h"

#include <vector>

#include "llama.h"

namespace hy {

namespace {
thread_local std::string g_last_error;

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

llama_model *load_model(const std::string &path) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU only on phones
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) set_error("Failed to load model: " + path);
    return model;
}

void free_model(llama_model *model) {
    if (model) llama_model_free(model);
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
        if (n > 0) return std::string(buf.data(), n);
    }
    // Fallback: ChatML (Qwen and many others).
    return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user +
           "<|im_end|>\n<|im_start|>assistant\n";
}

int generate(llama_model *model, const std::string &system, const std::string &user,
             const GenParams &p, const TextCallback &on_text) {
    if (!model) {
        set_error("Model not loaded");
        return -1;
    }
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

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = p.n_ctx;
    cp.n_batch = p.n_ctx;
    cp.n_threads = p.n_threads;
    cp.n_threads_batch = p.n_threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        set_error("Failed to create context");
        return -4;
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
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
    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    llama_token tok;
    char piece[256];

    for (int i = 0; i < p.max_tokens; i++) {
        if (llama_decode(ctx, batch) != 0) {
            set_error("Decode failed");
            result = -5;
            break;
        }
        tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, n);
        result++;

        size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            bool keep_going = on_text(pending.substr(0, ready));
            pending.erase(0, ready);
            if (!keep_going) break;
        }
        batch = llama_batch_get_one(&tok, 1);
    }
    if (!pending.empty() && result >= 0) on_text(pending);

    llama_sampler_free(smpl);
    llama_free(ctx);
    return result;
}

}  // namespace hy
