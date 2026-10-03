// Thin, JNI-free wrapper around llama.cpp so it can be compiled and tested on a
// desktop host as well as on Android.
#pragma once

#include <functional>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;

namespace hy {

struct GenParams {
    int n_ctx = 2048;
    int n_threads = 4;
    int max_tokens = 256;
    float temperature = 0.7f;
    // Both Qwen families recommend these for instruct (non-thinking) use.
    float top_p = 0.8f;
    int top_k = 20;
    unsigned int seed = 0xFFFFFFFF;  // LLAMA_DEFAULT_SEED -> random
    // Optional GBNF grammar (root rule "root") that constrains the output, e.g. to JSON actions.
    std::string grammar;
};

// A persistent context whose KV cache is reused across calls: only the part of a new prompt
// that differs from what was last processed is decoded again. Agent loops append to the same
// prompt every step, so each step only pays for the new tokens.
struct Session {
    llama_context *ctx = nullptr;
    int n_ctx = 0;
    int n_threads = 0;
    std::vector<int> cached;  // tokens whose KV is in ctx (sequence 0)
};

constexpr int kSlots = 3;  // 0 = general, 1 = agent orchestrator, 2 = command router

struct Engine {
    llama_model *model = nullptr;
    Session sessions[kSlots];
};

// Receives complete UTF-8 text chunks. Return false to stop generation.
using TextCallback = std::function<bool(const std::string &)>;

void backend_init();

// Returns nullptr on failure (see last_error()).
Engine *open(const std::string &path);
void close(Engine *engine);

// Formats system+user with the model's built-in chat template (falls back to
// ChatML), then streams the assistant reply through `on_text`.
// Returns the number of generated tokens, or a negative error code.
int generate(Engine *engine, int slot, const std::string &system, const std::string &user,
             const GenParams &params, const TextCallback &on_text);

// Tokens reused from the cache on the last generate() call for `slot` (for tests/logging).
int last_reused(Engine *engine, int slot);

// Exposed for tests.
std::string format_chat(llama_model *model, const std::string &system, const std::string &user);
size_t complete_utf8_prefix(const std::string &bytes);

const std::string &last_error();

}  // namespace hy
