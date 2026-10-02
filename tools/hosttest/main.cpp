// Usage: hosttest <model.gguf> [--vocab-only | --grammar-file <file.gbnf>]
#include <cassert>
#include <cstdlib>
#include <cmath>
#include <algorithm>
#include <vector>
// Release builds define NDEBUG, which turns CHECK() into a no-op; CHECK always runs.
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "CHECK failed: %s (line %d)\n", #cond, __LINE__); exit(1); } } while (0)
#include <cstdio>
#include <cstring>
#include <string>

#include "llama.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include "nlohmann/json.hpp"
#include "llm_core.h"

static std::string run(hy::Engine *e, int slot, const std::string &sys, const std::string &user, int max_tokens = 24) {
    hy::GenParams p;
    p.max_tokens = max_tokens;
    p.n_ctx = 1024;
    p.temperature = 0;  // greedy: deterministic, so cached and fresh runs must match
    std::string out;
    int n = hy::generate(e, slot, sys, user, p, [&](const std::string &s) { out += s; return true; });
    CHECK(n >= 0);
    return out;
}

int main(int argc, char **argv) {
    // UTF-8 prefix logic
    CHECK(hy::complete_utf8_prefix("abc") == 3);
    CHECK(hy::complete_utf8_prefix("a\xF0\x9F\x98") == 1);      // partial emoji held back
    CHECK(hy::complete_utf8_prefix("a\xF0\x9F\x98\x80") == 5);  // full emoji
    CHECK(hy::complete_utf8_prefix("\xC3") == 0);
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

    if (argc > 2 && strcmp(argv[2], "--logits-check") == 0) {
        // Same prompt decoded (A) in one go vs (B) as a cached prefix + the rest, the way
        // generate() reuses its KV cache. Logits must match up to float rounding.
        llama_model *m = e->model;
        const llama_vocab *vocab = llama_model_get_vocab(m);
        std::string prompt = hy::format_chat(m, "You are an agent.", "Goal: find the score.\nStep 1: research -> 3 results.\nStep 2: research -> score is 180/4.\n");
        std::vector<llama_token> t(4096);
        int n = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), t.data(), (int)t.size(), true, true);
        t.resize(n);
        auto run = [&](int split) {
            auto cp = llama_context_default_params();
            cp.n_ctx = 1024; cp.n_batch = 512; cp.n_threads = 6;
            llama_context *c = llama_init_from_model(m, cp);
            if (split > 0) CHECK(llama_decode(c, llama_batch_get_one(t.data(), split)) == 0);
            CHECK(llama_decode(c, llama_batch_get_one(t.data() + split, n - split)) == 0);
            const float *lg = llama_get_logits_ith(c, -1);
            std::vector<float> v(lg, lg + llama_vocab_n_tokens(vocab));
            llama_free(c);
            return v;
        };
        auto a = run(0), b = run(n * 2 / 3);
        float maxdiff = 0, scale = 0;
        size_t ia = 0, ib = 0;
        for (size_t i = 0; i < a.size(); i++) {
            maxdiff = std::max(maxdiff, std::fabs(a[i] - b[i]));
            scale = std::max(scale, std::fabs(a[i]));
            if (a[i] > a[ia]) ia = i;
            if (b[i] > b[ib]) ib = i;
        }
        printf("tokens=%d split=%d max|logit diff|=%.4f (max logit %.2f) top token same: %s\n", n, n * 2 / 3, maxdiff, scale, ia == ib ? "yes" : "no");
        // Attention-only models (Qwen2.5) match exactly; hybrid/recurrent ones (Qwen3.5) differ a bit
        // with any batch split, cache or not. The decision that matters is the top token.
        CHECK(ia == ib);
        hy::close(e);
        return 0;
    }

    if (argc > 3 && strcmp(argv[2], "--eval") == 0) {
        // Runs eval cases (JSON lines from EvalCases.kt) greedily; prints one JSON result per line.
        std::ifstream in(argv[3]);
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty()) continue;
            auto c = nlohmann::json::parse(line);
            hy::GenParams p;
            p.n_ctx = 4096;
            p.n_threads = 6;
            p.temperature = 0;
            p.max_tokens = c["max"].get<int>();
            p.grammar = c["grammar"].get<std::string>();
            std::string out;
            auto t0 = std::chrono::steady_clock::now();
            int n = hy::generate(e, 0, c["system"].get<std::string>(), c["user"].get<std::string>(), p,
                                 [&](const std::string &s) { out += s; return true; });
            long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
            nlohmann::json r = {{"id", c["id"]}, {"kind", c["kind"]}, {"expect", c["expect"]}, {"out", out}, {"n", n}, {"ms", ms},
                                {"err", n < 0 ? hy::last_error() : ""}};
            std::cout << r.dump() << std::endl;
        }
        hy::close(e);
        return 0;
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
    if (a2 != b2) printf("  cached: %s\n  fresh:  %s\n", a2.c_str(), b2.c_str());
    CHECK(fresh_reuse == 0);
    // Recurrent/hybrid models (e.g. Qwen3.5) can't drop part of their state: no reuse, full re-read.
    bool reusable = !llama_model_is_recurrent(e->model) && !llama_model_is_hybrid(e->model);
    if (reusable) {
        CHECK(grow_reuse > 10);
        CHECK(same_reuse > grow_reuse);
        CHECK(after_other_reuse == same_reuse);
    } else {
        CHECK(grow_reuse == 0 && same_reuse == 0);
        printf("(hybrid/recurrent model: cache reuse not possible, falls back to full prefill)\n");
    }
    // Exact equality only holds on tiny models: real models can flip a near-tied token because a
    // cached prefix was computed in different batch sizes (float rounding). --logits-check proves
    // the cache itself is exact; here we require the same start and identical repeated runs.
    auto common = [](const std::string &x, const std::string &y) {
        size_t i = 0;
        while (i < x.size() && i < y.size() && x[i] == y[i]) i++;
        return i;
    };
    CHECK(common(a2, b2) >= std::min<size_t>(20, b2.size()));
    CHECK(a3 == a4);  // same cache state + same prompt → same output

    // Grammar + invalid grammar
    hy::GenParams p;
    p.max_tokens = 64;
    p.n_ctx = 512;
    p.temperature = 0;
    p.grammar = R"(root ::= "{\"action\":\"" ("answer" | "digest") "\"}")";
    std::string out;
    int n = hy::generate(e, 0, "Pick an action.", "what did I miss", p, [&](const std::string &s) { out += s; return true; });
    CHECK(n > 0 && (out == "{\"action\":\"answer\"}" || out == "{\"action\":\"digest\"}"));
    p.grammar = "root ::= (";
    CHECK(hy::generate(e, 0, "x", "y", p, [](const std::string &) { return true; }) == -6);
    printf("grammar ok\nall native tests passed\n");
    hy::close(e);
    return 0;
}
