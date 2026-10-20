# Hy Assistant

An Android assistant for **WhatsApp** that runs an AI model **entirely on your phone**:
no account, no cloud, works offline after a one-time model download. Tuned for the
**Nothing Phone (3a) Pro**, works on any arm64 Android 10+ phone with 6 GB+ RAM.

What it does:

- **"What did I miss?"**: a one-line-per-chat catch-up of unread WhatsApp messages.
- **Summarize a chat**: short bullet points, flags questions waiting on you.
- **Suggest a reply**: drafts a reply in your tone; you edit it, then tap **Send**.
- **Ask or tell it anything, in any format**: type a request in plain English.
  Simple ones (`Reply to Rahul saying I'm in a meeting`, `What did I miss?`) run instantly.
  Everything else goes to the on-device AI, which either does it (reply, summarize,
  switch Auto/Manual for a chat…) or answers using your recent chats and notifications:
  `did anyone mention dinner?`, `list my unread chats as a table`,
  `write a leave application for tomorrow`, `stop auto replying to the office group`.
  Each chat also has an "Ask anything about this chat" box.
- **Quick web search**: when a question needs facts Hy doesn't know (news, prices,
  people, how-to…), it searches the web (Bing → DuckDuckGo → Wikipedia, no API key),
  reads the top page and answers with tappable sources. Only the search words leave the
  phone. Can be turned off in Settings.
  **Search engine** setting: *Bing* (default, free) or *Google (via browser)*, which reads Google's
  results page in a hidden in-app browser. If Google asks "I'm not a robot", a popup shows the
  check so you can solve it, then the search continues. If you skip it or anything fails, Hy
  uses Bing. Google's terms don't allow automated searches, so keep it to light personal use.
- **In-app browser**: `open youtube and search lofi`, `open github.com`. Ask questions about
  any page you're on ("summarize this", "what's the price?").
- **Terminal**: run commands in the phone's own shell or in **Termux** (full Linux:
  `pkg`, `python`, `git`). Hy can propose commands (`check storage in termux`) but they
  only run when you tap **Run**, with a red warning for risky ones (`rm`, `dd`, …).
  Termux setup: install Termux from F-Droid, run
  `echo "allow-external-apps = true" >> ~/.termux/termux.properties`, restart Termux,
  then tap *Allow Termux access* in Hy's Terminal.
- Incoming messages never trigger web searches or commands.
- **Conversation memory**: the home screen is a running conversation, so follow-ups work
  ("summarize Rahul" → "reply to him saying ok", "weather today?" → "and tomorrow?").
  **New chat** starts fresh.
- **Long-term memory**: "remember that my boss is Priya", "what do you remember?",
  "forget my boss". Used in answers and in reply drafts you review, never in Auto replies.
  Passwords, PINs and codes are refused. View or delete everything in Settings → Memory.
- **Multi-step agents**: for tasks that need several steps, an orchestrator plans and hands
  each step to a specialist sub-agent: **Research** (web), **Messages** (WhatsApp),
  **Terminal**, **Browser** and **Memory**. You see every step live. It runs up to 10 steps
  by default (5/10/15 in Settings) and pauses for your tap before sending any message or
  running any command. Start with "Agent mode", "agent: …", or just ask
  ("find today's gold price and send it to dad"). The engine reuses its cache between steps,
  so each step only processes the new part.
- **Humanized replies** (on by default): every WhatsApp reply Hy writes follows rules from the
  "humanizer" guide (Wikipedia's *Signs of AI writing*). It drops chatbot phrases ("Certainly!",
  "I hope this helps"), em dashes, curly quotes and AI vocabulary, uses contractions, and matches
  how *you* text in that chat (length, lowercase, emojis, final full stop). This is a rules pass in
  code, so it adds no extra AI time. Toggle in Settings.
- **Files: .md, .csv, .docx, .txt**: every answer has **Save as…**, and asking for a format saves it
  automatically ("make a CSV of my unread chats", "write a leave letter as a Word file",
  "agent: compare 3 phones and give me an excel file"). Files go to **Downloads/Hy**, with
  **Open** and **Share** buttons. Word files are built on the phone, with no internet.
  CSV cells that look like formulas are neutralised.
- **Two reply modes** (switch on the home screen, or per chat):
  - **Manual**: every new message gets a drafted reply in a notification. Tap **Send**
    right from the notification shade, or **Reply** to type your own.
  - **Auto**: Hy sends the reply by itself after a short countdown (default 10 s, with
    **Cancel**). Questions and requests aimed at you get a fixed "busy, will reply soon";
    the AI only writes light replies (thanks, wishes, greetings), and its text is checked
    (no numbers, links, money talk or promises) or replaced by the holding message.
    Money, emergencies, location requests and group chats are held for you as
    suggestions, OTPs/passwords get no reply, and a message suggesting someone may be in
    distress gets no drafted reply at all, only an alert. Turning Auto on by typing a
    request asks you to confirm first. Max one auto-reply per chat every 5 min. Everything
    Hy does is listed under **Hy activity**.
- **Watches everything automatically**: chats from any messenger, plus a feed of other
  notifications (codes, deliveries, payments, reminders…) that's included in
  "What did I miss?".

## Install on your phone

1. On your phone, open
   **https://github.com/hrithik1520/Hy-tasks/releases/latest/download/HyAssistant.apk**.
2. Open the downloaded file. If Android asks, allow **Install unknown apps** for
   your browser/Files app, then tap **Install**.
   (If Play Protect warns about an unknown developer, tap **More details → Install anyway**.
   That's normal for apps not from the Play Store.)
3. Open **Hy Assistant**:
   - **Step 1**: tap *Open settings* and enable notification access for *Hy Assistant*.
   - **Step 2**: tap *Choose model* and download **Qwen2.5 1.5B** (~1.1 GB, use Wi-Fi).
4. Wait for a WhatsApp message to arrive. It appears in the app's chat list.

Updates: install the newer `HyAssistant.apk` over the old one. Your data and model are kept.

- **Share → Hy** from any app (text or links): Summarize, Explain simply, Key facts,
  "Is it true?", Reply ideas, or ask anything about it.
- **Screen reading** (opt-in, Accessibility; Settings → Screen reading): on any app, tap the
  accessibility button and Hy reads the text on screen. Then Summarize, Explain, Translate,
  Reply ideas, or ask anything. Optionally (experimental) it reads the WhatsApp chat you have open
  so replies and summaries see the whole conversation, not just notifications. It never reads
  password fields, never taps or types, and keeps everything on the phone.
- **Phone actions**: "open Spotify", "set alarm for 6:30am", "timer 10 minutes", "call Dad",
  "sms Mom saying …", "navigate to Goa airport", "play lofi on YouTube".
- **WhatsApp for anyone in your contacts** (needs Screen reading): "message Rahul on WhatsApp saying
  I'm late" or "reply to Priya on WhatsApp". Hy opens the chat, reads it, drafts a reply (or polishes
  yours), types it into WhatsApp's message box, then shows **Send / Cancel** on a small panel. Nothing
  is sent until you tap Send. "Reply to X" also uses this when X isn't in Hy's notification chats.
- **Voice**: tap 🎤 to talk to Hy. It answers out loud and keeps listening for follow-ups
  ("and tomorrow?", "send that to Priya") until you say "stop" or go quiet. Drafted replies are
  read out: say **"send"**, **"cancel"** or **"change it to …"**.
- **"Hey Alfred"** (optional wake word, also "What's up Alfred" / "Alfred"): listens offline on the
  phone with a small notification (40 MB voice model, downloaded once). It pauses during calls and
  opens Hy straight into a conversation, over the lock screen for questions.
- **Quick Settings tile**: switch Manual/Auto from the notification shade (edit tiles → "Hy auto-reply").
- **Morning briefing** (optional): a daily notification at your chosen time with unread chats and
  important payments, deliveries and reminders.
- **Speed test** in Models: times a command, a cached command and a reply on *your* phone.

## How it works (and its limits)

WhatsApp has no public API, so Hy reads **WhatsApp notifications** (Android's
`NotificationListenerService`) and replies through WhatsApp's own **notification reply
button**. This means:

- It only sees messages that produced a notification, starting from when access was
  granted. Muted chats and old history aren't visible.
- Direct **Send** works while that chat's WhatsApp notification is still showing. If you
  already opened the chat in WhatsApp, Hy offers **Copy & open WhatsApp** instead.
- Messages are stored only on the phone, for 3 days. One-time codes in other apps'
  notifications are masked (••••) before they are stored.
- Allow **"Let Hy work while the phone sleeps"** on the home screen (battery optimization),
  otherwise Android may pause Hy with the screen off and replies come late.

The AI is [llama.cpp](https://github.com/ggml-org/llama.cpp) compiled into the app (CPU,
built for the Armv8.2 baseline with dot-product instructions that every recent phone has) running a
quantized GGUF model.
Commands are understood by plain rules, and contacts are matched by fuzzy name matching.
The model only writes text. It never picks recipients or triggers actions.

## Project layout

| Path | What |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose) |
| `app/src/main/cpp/` | JNI wrapper around llama.cpp |
| `core/` | Pure-Kotlin logic: command parser, contact matcher, prompts (unit-tested) |
| `tools/hosttest/` | Desktop build of the native wrapper for testing without a phone |
| `docs/` | Architecture & plan |
| `.github/workflows/build-apk.yml` | CI: tests, builds the signed APK, publishes a release |

## Evaluating models

`core/src/test/.../EvalCases.kt` holds 32 real cases (commands, agent first steps, replies) built
with the app's actual prompts and grammars. To score a GGUF model on a desktop:

```bash
scripts/fetch-llama.sh
cmake -S tools/hosttest -B build-host -DCMAKE_BUILD_TYPE=Release && cmake --build build-host -j4
HY_CORE_ONLY=1 ./gradlew :core:test -Dhy.evalOut=$PWD/cases.jsonl
build-host/hosttest model.gguf --eval cases.jsonl > results.jsonl
tools/eval/score.py results.jsonl
```

`build-host/hosttest model.gguf` runs the native checks (KV-cache reuse, grammar), and
`--logits-check` proves cached and fresh decoding give the same scores.

## Building

CI builds every push and publishes the APK as a GitHub release. Locally (Android SDK +
NDK required):

```bash
scripts/fetch-llama.sh            # clones the pinned llama.cpp (llama-version.txt)
./gradlew :core:test              # logic tests
./gradlew :app:assembleRelease    # APK (debug-signed unless HY_KEYSTORE_PATH is set)
```

Core tests without the Android SDK: `HY_CORE_ONLY=1 ./gradlew :core:test`.

Versions: the release version lives in `version.txt` (currently 1.0.0). Bump it for a new
release; pushes without a bump are published as `<version>.<build>`.

Signing: see [`signing/README.md`](signing/README.md). The sideload key is committed for
convenience and must be replaced before any public/Play Store release.

The dot-matrix display font is [Doto](https://github.com/oliverlalan/Doto) (SIL Open Font License 1.1, see `licenses/Doto-OFL.txt`).
