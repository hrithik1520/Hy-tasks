// Usage: hosttest <model.gguf> [--vocab-only]
#include <cassert>
#include <cstdio>
#include <cstring>
#include "llm_core.h"
#include "llama.h"

int main(int argc, char **argv) {
    // UTF-8 prefix logic
    assert(hy::complete_utf8_prefix("abc") == 3);
    assert(hy::complete_utf8_prefix("a\xF0\x9F\x98") == 1);          // partial emoji held back
    assert(hy::complete_utf8_prefix("a\xF0\x9F\x98\x80") == 5);      // full emoji
    assert(hy::complete_utf8_prefix("\xC3") == 0);
    printf("utf8 ok\n");
    if (argc < 2) return 0;

    hy::backend_init();
    bool vocab_only = argc > 2 && strcmp(argv[2], "--vocab-only") == 0;
    llama_model *model;
    if (vocab_only) {
        auto mp = llama_model_default_params();
        mp.vocab_only = true;
        model = llama_model_load_from_file(argv[1], mp);
    } else {
        model = hy::load_model(argv[1]);
    }
    if (!model) { printf("load failed: %s\n", hy::last_error().c_str()); return 1; }
    printf("--- prompt ---\n%s\n--------------\n", hy::format_chat(model, "You are helpful.", "Hi 😀 there").c_str());
    if (!vocab_only) {
        hy::GenParams p; p.max_tokens = 48; p.n_threads = 4; p.n_ctx = 512;
        int n = hy::generate(model, "You are helpful.", "Say hello in five words.", p,
                             [](const std::string &s) { printf("%s", s.c_str()); fflush(stdout); return true; });
        printf("\n[generated %d tokens] %s\n", n, n < 0 ? hy::last_error().c_str() : "");
    }
    hy::free_model(model);
    return 0;
}
