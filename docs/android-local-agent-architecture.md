# Android Local Agent — Architecture & v1 Plan

An Android assistant that runs an open-source LLM fully on-device to read,
summarize, and help reply to messages, and to turn plain-language commands
into safe, confirmed actions. No cloud backend.

> Status: draft. Items marked **[verify]** are assumptions that must be checked
> against current Android / Play Store docs or on real hardware before we commit
> to them.

---

## 1. Guiding principles

1. **Platform first, model second.** What Android and Play policy allow decides
   the feature set. The LLM is a component, not the product.
2. **Deterministic core, LLM at the edges.** Intents, contact matching,
   permissions, and execution are plain Kotlin. The LLM drafts text, summarizes,
   and parses messy language into a strict schema.
3. **The model never executes anything.** It proposes; app code validates,
   asks for confirmation, and executes.
4. **Swap-able inference.** One `LlmEngine` interface so we can switch between
   runtimes and models without touching features.
5. **Measure on real phones.** Every model/runtime choice is backed by our own
   eval set run on target devices.

---

## 2. v1 feature scope

Each feature is listed with the Android mechanism it depends on. If the
mechanism isn't available, the feature is cut — not hacked around.

| # | Feature | Android mechanism | Feasibility |
|---|---|---|---|
| F1 | Summarize recent notifications ("what did I miss?") | `NotificationListenerService` (user grants in system settings) | ✅ Good |
| F2 | Draft & send a reply to a message notification (WhatsApp, Telegram, SMS apps, etc.) | Notification's `RemoteInput` reply action | ⚠️ Only while the notification exists and the app exposes a reply action |
| F3 | Reminders / notes / to-dos from plain language | App's own storage + `AlarmManager` / `WorkManager` | ✅ Good |
| F4 | Call or message a contact ("text Rahul I'm late") | `ACTION_DIAL` / `ACTION_SENDTO` intents (user taps send) + `READ_CONTACTS` | ✅ Good via intents; no silent sending |
| F5 | Voice input / spoken output | `SpeechRecognizer` or on-device STT; `TextToSpeech` | ✅ Good (on-device STT quality varies by device) **[verify]** |

### Explicitly out of scope for v1

- **Reading full chat history of third-party messengers.** No public API
  (e.g. WhatsApp). `AccessibilityService` scraping is fragile and heavily
  restricted by Play policy for non-accessibility use. **[verify current policy]**
- **Sending SMS silently.** `SEND_SMS` is a restricted permission on Play unless
  the app is the default SMS handler. v1 hands off to the SMS app via intent.
- **Autonomous multi-step agents.** Small models + long chains = unreliable.
- **Controlling other apps' UI.**

---

## 3. High-level architecture

```
 ┌──────────────┐   ┌──────────────────┐
 │  UI / Voice  │   │ Notification     │
 │ (Compose,    │   │ Listener Service │
 │  STT / TTS)  │   └────────┬─────────┘
 └──────┬───────┘            │ notifications
        │ user command       ▼
        │           ┌──────────────────┐
        └──────────►│   Orchestrator   │◄──── Local Memory (Room DB)
                    │  (Kotlin, rules) │
                    └──┬────────────┬──┘
           needs LLM?  │            │ action plan (validated)
                       ▼            ▼
              ┌──────────────┐  ┌───────────────────┐
              │  LlmEngine   │  │  Action Executor   │
              │ (interface)  │  │ + Guardrails +     │
              └──────┬───────┘  │ Confirmation UI    │
                     │          └───────────────────┘
       ┌─────────────┼──────────────┐
       ▼             ▼              ▼
  llama.cpp     MediaPipe /     ML Kit GenAI
  (GGUF, JNI)   LiteRT-LM       (Gemini Nano)
```

### Modules

| Module | Responsibility |
|---|---|
| `app` | Compose UI, settings, confirmation dialogs |
| `core-orchestrator` | Routes a command: rules → LLM fallback → action plan |
| `core-intents` | Intent definitions, rule-based classifier, JSON schemas |
| `core-contacts` | Contact lookup + fuzzy matching (no LLM) |
| `core-actions` | Executors for each action type + guardrails |
| `core-notifications` | `NotificationListenerService`, store, reply via `RemoteInput` |
| `core-memory` | Room DB: preferences, tone samples, recent context |
| `llm-api` | `LlmEngine` interface, prompt templates, output validation |
| `llm-llamacpp` / `llm-mediapipe` / `llm-mlkit` | Runtime implementations |
| `model-manager` | Download, verify, store, select, delete GGUF/other models |
| `eval` | Test set + benchmark harness (runs on device) |

---

## 4. Command pipeline

1. **Input** — text or STT transcript.
2. **Rule-based intent pass** — regex/keyword matching for the fixed intent
   list. Fast, deterministic. If confident → skip the LLM for classification.
3. **LLM parse (fallback)** — only for ambiguous phrasing. Output is
   **grammar-constrained** to the intent JSON schema (see §5).
4. **Entity resolution in code** — contact names → `core-contacts` fuzzy match;
   times → date parser. If multiple matches, ask the user (chips UI), never
   let the model guess.
5. **LLM drafting (if needed)** — reply text, summary text.
6. **Validation** — schema check, allowed-action check, length limits.
7. **Confirmation** — any outward action (send, call, delete) requires an
   explicit tap. Read-only actions (summaries) don't.
8. **Execute** and log locally.

### Intent list (v1)

```
summarize_notifications   reply_to_notification   create_reminder
create_note               call_contact            message_contact
read_aloud                unknown
```

### Example plan object (validated before execution)

```json
{
  "intent": "reply_to_notification",
  "target_notification_id": "n_8231",
  "contact": { "id": "c_42", "display_name": "Rahul" },
  "draft_reply": "I'm in a meeting. I'll call you shortly.",
  "speak_aloud": false,
  "needs_confirmation": true
}
```

Note: the model only produces `intent`, a contact *name string*, and
`draft_reply`. IDs and `needs_confirmation` are filled in by app code.

---

## 5. LLM layer

### 5.1 Interface

```kotlin
interface LlmEngine {
    val info: EngineInfo                      // runtime, model, ctx size
    suspend fun load(model: ModelRef)
    suspend fun generate(
        prompt: Prompt,
        schema: JsonSchema? = null,           // constrained output if set
        maxTokens: Int = 256,
    ): LlmResult
    fun unload()
}
```

### 5.2 Runtimes to compare (prototype phase)

| Runtime | Pros | Cons |
|---|---|---|
| **llama.cpp (GGUF via JNI)** | Any GGUF model; GBNF / JSON-schema constrained decoding; huge ecosystem | We own the JNI/NDK build; CPU-mostly on many devices |
| **MediaPipe LLM Inference / LiteRT-LM** | Official Google SDK; GPU support; less native glue | Smaller model catalog; constrained-output support **[verify]** |
| **ML Kit GenAI (Gemini Nano via AICore)** | No download, system-managed, efficient | Only on supported devices; less control **[verify device list]** |

Decision rule: pick the runtime that passes the eval (§8) on our target
low-end device with the least integration cost. Keep a second runtime as fallback.

### 5.3 Models to evaluate

Start in the 1–2B class, Q4 quantization; try 3–4B only on high-RAM devices.

- Qwen small instruct (≈1.5B) — strong instruction following
- Gemma small instruct (≈1–2B) — Android-friendly
- Phi small (≈3–4B) — quality ceiling candidate

Check each model's **license** (Apache-2.0 / MIT / Gemma terms / Llama
community license) before shipping it in the downloader.

### 5.4 Reliability rules

- **Always constrain structured output** (GBNF/JSON schema). Never regex a
  free-form answer into JSON.
- Short prompts, few-shot examples per intent, low temperature for parsing;
  slightly higher for reply drafting.
- On invalid output: one retry → fall back to `unknown` and ask the user.
- Cap generation length; time out after N seconds.

### 5.5 Resource budget (rough targets) **[verify on device]**

| Item | Target |
|---|---|
| Model RAM (1.5B Q4) | ~1–1.2 GB |
| Cold load | < 5 s |
| Parse latency | < 1.5 s |
| Reply draft (~40 tokens) | < 4 s |
| Min device | 6 GB RAM, Android 10+ (target: Nothing Phone (3a) Pro) |

Lifecycle: load on demand when the user opens the assistant, keep warm for a
short idle window, then unload. Do **not** hold the model in a background
service — the OS will kill it and it drains battery.

---

## 6. Model management

- Models are **downloaded after install**, not bundled in the APK.
- `WorkManager` download with resume, Wi-Fi-only option, free-space check.
- SHA-256 verification against a pinned manifest shipped with the app.
- Stored in app-private storage (`filesDir/models/`).
- Settings: **Small / Balanced / Quality** presets mapped to concrete
  model+quant entries; the app recommends one based on device RAM.
- User can delete models to free space.

---

## 7. Safety & privacy guardrails

- All processing on-device; no network calls except model downloads.
- Allow-list of executable actions; anything else is rejected.
- Every outward action (send, call, post) needs an explicit confirmation tap
  showing the exact text and recipient.
- Per-app toggles: the user chooses which apps' notifications the assistant
  may read or reply to.
- Notification content kept only for a short rolling window (e.g. 24 h),
  user-clearable.
- Prompt-injection awareness: notification text is **data**. It is placed in a
  clearly delimited block and can never change the intent or recipient chosen
  by the user. A message saying "reply to everyone with X" does nothing.
- No sensitive data in logs.

---

## 8. Evaluation

- `eval/commands.jsonl`: ~50–100 real commands (English + Hinglish if
  targeted), each with expected intent, entities, and a pass/fail check.
- `eval/replies.jsonl`: notification + context → reply quality, rated manually
  on a small scale.
- Metrics per (runtime, model, device): intent accuracy, valid-JSON rate,
  p50/p95 latency, peak RAM, battery drain over a 10-minute session.
- Run on at least one low-end (6 GB) and one high-end device before choosing.

---

## 9. Local memory (v1, minimal)

- User preferences: name, preferred tone (casual/formal), language.
- A few user-approved example replies used as few-shot tone samples.
- Frequent contacts (for disambiguation ranking).
- No embeddings / RAG in v1; revisit only if a feature needs it.

---

## 10. Milestones

| Phase | Goal | Exit criteria |
|---|---|---|
| **M0 — Spike** | Notification listener + `RemoteInput` reply working end-to-end with a hard-coded reply | Reply sent in 3 target messengers on a real device |
| **M1 — Deterministic core** | Intents, rules classifier, contacts matching, confirmations, reminders | All v1 intents work with rules only on simple phrasing |
| **M2 — LLM bake-off** | `LlmEngine` with 2 runtimes × 2–3 models, eval harness | Eval table filled; runtime + default model chosen |
| **M3 — LLM integration** | LLM fallback parsing + reply drafting + summaries | ≥ 90% intent accuracy, ≥ 99% valid JSON on eval set |
| **M4 — Model manager & voice** | Downloads, presets, STT/TTS | Fresh install → first answer works offline |
| **M5 — Hardening** | Guardrails review, battery/thermal tests, Play policy review | Ready for closed testing |

---

## 11. Decisions (answered)

| Question | Decision | Consequence |
|---|---|---|
| Messengers | **WhatsApp** (+ WhatsApp Business toggle) | Only notification-based read + `RemoteInput` reply; no history access |
| Languages | **English** | Qwen2.5 Instruct models; English-only prompts |
| Distribution | **Both** sideload and Play Store | v1 ships as a sideload APK via GitHub Releases. Play needs a private signing key and a notification-listener policy declaration first |
| Target device | **Nothing Phone (3a) Pro** (Dimensity 7300, 8/12 GB RAM, Android 15) | arm64-only build, `-march=armv8.2-a+dotprod+fp16`, 4 threads (A78 cores), 1.5B Q4_K_M default |
| Voice | **Not in v1** | No STT/TTS; F5 deferred |

## 12. What v1 actually implements

- F1 catch-up ("What did I miss?"), with a plain no-AI fallback when no model is installed
- Per-chat summary and suggested reply
- F2 reply via WhatsApp's notification action, always behind a confirmation card;
  "Copy & open WhatsApp" fallback when the notification is gone
- Rule-based commands: catch-up / summarize X / suggest reply to X / reply to X saying Y
- Fuzzy contact resolution with a "Which Rahul?" picker on ambiguity
- Model manager: catalog download (DownloadManager), import from file, custom URL,
  GGUF header check, idle unload after 5 min
- Deferred: F3 reminders, F4 call/SMS intents, F5 voice, JSON-schema constrained output
  (v1 only asks the model for plain text, so grammar constraints aren't needed yet)

## 13. Evaluation results (2 Oct 2026, desktop CPU, 6 threads)

32 cases from `EvalCases.kt` (25 commands, 4 agent first steps, 3 free-text replies), greedy decoding.

| Model | Correct | Avg time/case | KV-cache reuse | Notes |
|---|---|---|---|---|
| Qwen2.5 1.5B Q4_K_M (default) | 29/32 | 1.5 s | yes | ~0.85 s per command once the router prompt is cached |
| Qwen3.5 2B Q4_K_M | 31/32 | 7.1 s | no (hybrid model) | best planner; needs the empty `<think>` block to stop thinking |
| Qwen3.5 0.8B Q4_K_M | 28/32 | 4.0 s | no (hybrid model) | |

Fixes found by the evaluation: clearer router examples (26→29 for Qwen2.5), research vs terminal
roles for the orchestrator (3/4→4/4), thinking disabled for Qwen3-family templates, a dedicated
KV slot for the router (cold 6.2 s → warm 0.85 s), and native test checks that actually run in
Release builds (`CHECK` instead of `assert`).
