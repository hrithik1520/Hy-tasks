// Usage: hosttest <model.gguf> [--vocab-only | --grammar-file <file.gbnf>]
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>

#include "llama.h"
#include "llm_core.h"

static std::string run(hy::Engine *e, int slot, const std::string &sys, const std::string &user, int max_tokens = 24) {
    hy::GenParams p;
    p.max_tokens = max_tokens;
    p.n_ctx = 1024;
    p.temperature = 0;  // greedy: deterministic, so cached and fresh runs must match
    std::string out;
    int n = hy::generate(e, slot, sys, user, p, [&](const std::string &s) { out += s; return true; });
    assert(n >= 0);
    return out;
}

int main(int argc, char **argv) {
    // UTF-8 prefix logic
    assert(hy::complete_utf8_prefix("abc") == 3);
    assert(hy::complete_utf8_prefix("a\xF0\x9F\x98") == 1);      // partial emoji held back
    assert(hy::complete_utf8_prefix("a\xF0\x9F\x98\x80") == 5);  // full emoji
    assert(hy::complete_utf8_prefix("\xC3") == 0);
    printf("utf8 ok\n");
    if (argc < 2) return 0;

    hy::backend_init();
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);

    if (argc > 2 && strcmp(argv[2], "--vocab-only") == 0) {
        auto mp = llama_model_default_params();
        mp.vocab_only = true;
        llama_model *model = llama_model_load_from_file(argv[1], mp);
        printf("--- prompt ---\n%s\n--------------\n", hy::format_chat(model, "You are helpful.", "Hi 😀 there").c_str());
        llama_model_free(model);
        return 0;
    }

    hy::Engine *e = hy::open(argv[1]);
    if (!e) {
        printf("load failed: %s\n", hy::last_error().c_str());
        return 1;
    }

    if (argc > 3 && strcmp(argv[2], "--grammar-file") == 0) {
        FILE *f = fopen(argv[3], "rb");
        std::string g;
        char buf[4096];
        size_t r;
        while ((r = fread(buf, 1, sizeof buf, f)) > 0) g.append(buf, r);
        fclose(f);
        for (int i = 0; i < 5; i++) {
            hy::GenParams p;
            p.max_tokens = 160;
            p.n_ctx = 1024;
            p.temperature = 1.0f;
            p.grammar = g;
            p.seed = 100 + i;
            std::string out;
            int n = hy::generate(e, 0, "Pick an action.", "do something", p, [&](const std::string &s) { out += s; return true; });
            printf("[%d] n=%d %s %s\n", i, n, out.c_str(), n < 0 ? hy::last_error().c_str() : "");
        }
        hy::close(e);
        return 0;
    }

    // --- KV prefix reuse must not change results ---------------------------------------
    const std::string sys = "You are an agent. Decide the next step.";
    const std::string step1 = "Goal: find the score.\nStep 1: research -> found 3 results.\n";
    const std::string step2 = step1 + "Step 2: research -> read page, score is 180/4.\n";

    std::string a1 = run(e, 1, sys, step1);
    int fresh_reuse = hy::last_reused(e, 1);
    std::string a2 = run(e, 1, sys, step2);  // grows: should reuse the shared prefix
    int grow_reuse = hy::last_reused(e, 1);
    std::string a3 = run(e, 1, sys, step2);  // identical: reuses all but one token
    int same_reuse = hy::last_reused(e, 1);
    std::string other = run(e, 0, "Different prompt.", "Hello");  // slot 0 doesn't disturb slot 1
    std::string a4 = run(e, 1, sys, step2);
    int after_other_reuse = hy::last_reused(e, 1);

    hy::Engine *fresh = hy::open(argv[1]);
    std::string b2 = run(fresh, 0, sys, step2);
    hy::close(fresh);

    printf("reuse: first=%d grow=%d same=%d after-other-slot=%d\n", fresh_reuse, grow_reuse, same_reuse, after_other_reuse);
    printf("cached==fresh: %s\n", (a2 == b2 && a3 == b2 && a4 == b2) ? "yes" : "NO");
    assert(fresh_reuse == 0);
    assert(grow_reuse > 10);
    assert(same_reuse > grow_reuse);
    assert(after_other_reuse == same_reuse);
    assert(a2 == b2 && a3 == b2 && a4 == b2);

    // Grammar + invalid grammar
    hy::GenParams p;
    p.max_tokens = 64;
    p.n_ctx = 512;
    p.temperature = 0;
    p.grammar = R"(root ::= "{\"action\":\"" ("answer" | "digest") "\"}")";
    std::string out;
    int n = hy::generate(e, 0, "Pick an action.", "what did I miss", p, [&](const std::string &s) { out += s; return true; });
    assert(n > 0 && (out == "{\"action\":\"answer\"}" || out == "{\"action\":\"digest\"}"));
    p.grammar = "root ::= (";
    assert(hy::generate(e, 0, "x", "y", p, [](const std::string &) { return true; }) == -6);
    printf("grammar ok\nall native tests passed\n");
    hy::close(e);
    return 0;
}
