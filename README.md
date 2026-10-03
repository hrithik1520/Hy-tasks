# Alfrid

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
- **Two reply modes** (switch on the home screen, or per chat):
  - **Manual**: every new message gets a drafted reply in a notification. Tap **Send**
    right from the notification shade, or **Reply** to type your own.
  - **Auto**: Alfrid handles **every chat** by itself — direct chats, groups and other
    messengers — with a fixed two-step script, so no model is needed and nothing is ever
    invented:
    1. the first time someone writes in: *"Hi! Do you have any messages for <you>? — I'm
       Alfrid, <your> AI assistant."*
    2. once they answer with an actual message or task: *"Got it — I'll make sure <you>
       get it."* Small talk ("no", "ok", "hey") gets no second reply.

    Each message is sent after a short countdown (default 10 s, with **Cancel**). The
    relayed message is logged as **Message for you**. Safety rules always hold back
    OTPs/codes, money, passwords and emergencies — those come to you as suggestions
    instead. After confirming a message, Alfrid stays quiet in that chat for 5 min, and
    nobody is introduced to twice. Everything Alfrid does is listed under
    **Alfrid activity**.
- **Watches everything automatically**: chats from any messenger, plus a feed of other
  notifications (codes, deliveries, payments, reminders…) that's included in
  "What did I miss?".

## Install on your phone

1. On your phone, open
   **https://github.com/hrithik1520/Hy-tasks/releases/latest/download/Alfrid.apk**.
2. Open the downloaded file. If Android asks, allow **Install unknown apps** for
   your browser/Files app, then tap **Install**.
   (If Play Protect warns about an unknown developer, tap **More details → Install anyway**.
   That's normal for apps not from the Play Store.)
3. Open **Alfrid**:
   - **Step 1**: tap *Open settings* and enable notification access for *Alfrid*.
   - **Step 2**: tap *Choose model* and download **Qwen3.5 2B** (~1.2 GB, use Wi-Fi).
     Also on that screen: **Qwen3.5 0.8B** (508 MB, fastest), **Qwen3.5 4B** (2.6 GB, best for
     back-and-forth chat), Google's **Gemma 4 E2B / E4B** (2.5 / 4.0 GB, warmer writing but
     heavier) and the older **Qwen2.5 1.5B** to compare against. Any model can be swapped
     later from the same screen.
4. Wait for a WhatsApp message to arrive. It appears in the app's chat list.

Updates: install the newer `Alfrid.apk` over the old one. Your data and model are kept.

## How it works (and its limits)

WhatsApp has no public API, so Alfrid reads **WhatsApp notifications** (Android's
`NotificationListenerService`) and replies through WhatsApp's own **notification reply
button**. This means:

- It only sees messages that produced a notification, starting from when access was
  granted. Muted chats and old history aren't visible.
- Direct **Send** works while that chat's WhatsApp notification is still showing. If you
  already opened the chat in WhatsApp, Alfrid offers **Copy & open WhatsApp** instead.
- Messages are stored only on the phone, for 3 days.

The AI is [llama.cpp](https://github.com/ggml-org/llama.cpp) compiled into the app (CPU,
tuned for the Dimensity 7300's Cortex-A78 cores) running a quantized GGUF model.
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

## Building

CI builds every push and publishes the APK as a GitHub release. Locally (Android SDK +
NDK required):

```bash
scripts/fetch-llama.sh            # clones the pinned llama.cpp (llama-version.txt)
./gradlew :core:test              # logic tests
./gradlew :app:assembleRelease    # APK (debug-signed unless HY_KEYSTORE_PATH is set)
```

Core tests without the Android SDK: `HY_CORE_ONLY=1 ./gradlew :core:test`.

Signing: see [`signing/README.md`](signing/README.md). The sideload key is committed for
convenience and must be replaced before any public/Play Store release.
