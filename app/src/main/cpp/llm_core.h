// Thin, JNI-free wrapper around llama.cpp so it can be compiled and tested on a
// desktop host as well as on Android.
#pragma once

#include <functional>
#include <string>

struct llama_model;

namespace hy {

struct GenParams {
    int n_ctx = 2048;
    int n_threads = 4;
    int max_tokens = 256;
    float temperature = 0.7f;
    float top_p = 0.9f;
    int top_k = 40;
    unsigned int seed = 0xFFFFFFFF;  // LLAMA_DEFAULT_SEED -> random
};

// Receives complete UTF-8 text chunks. Return false to stop generation.
using TextCallback = std::function<bool(const std::string &)>;

void backend_init();

// Returns nullptr on failure (see last_error()).
llama_model *load_model(const std::string &path);
void free_model(llama_model *model);

// Formats system+user with the model's built-in chat template (falls back to
// ChatML), then streams the assistant reply through `on_text`.
// Returns the number of generated tokens, or a negative error code.
int generate(llama_model *model, const std::string &system, const std::string &user,
             const GenParams &params, const TextCallback &on_text);

// Exposed for tests.
std::string format_chat(llama_model *model, const std::string &system, const std::string &user);
size_t complete_utf8_prefix(const std::string &bytes);

const std::string &last_error();

}  // namespace hy
