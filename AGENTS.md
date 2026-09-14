# AGENTS.md

## Project: Voice Commander for terminal AI agents (Android)

Build a lightweight Android voice utility: the user *holds a floating button,
speaks a task, releases*, and a review popup offers **Send / Edit / Cancel**.
Send delivers the user's **intent** — not a literal transcript — into the app
in front: Termux first (an agent REPL reading stdin), other apps generically
(an accessible text field), clipboard as universal fallback. The instruction is
in front: for Termux, Send types the intent into the agent session and
submits it, so the agent starts working immediately. Nothing is executed by
voice alone: only the user's explicit Send in the review popup reaches the
agent.

The lineage: a macOS prototype (`../voice-ime/`) proved the product's core —
live RAW + LLM-interpreted CANDIDATE, race-safe debounced interpretation, a
hold-to-talk HUD, and paste-based commit. That Swift code is a *reference
spec*: this project is a fresh Kotlin implementation of the same pipeline for
Android, with two changes forced by the platform: RAW comes from the on-device
`SpeechRecognizer` (free, no OpenAI transcription spend) and commit goes
through a user-confirmed review popup instead of instant release-commit.

Keep the client lightweight; no server of our own. Interpretation runs on the
OpenAI Responses API; everything else runs on the phone.

---

## Product goal

The user holds a floating button and speaks. A small panel shows RAW (live)
and INTENT (interpreted), then on release a review popup appears:

```text
RAW                        INTENT (when ready)
えーと、このディレクトリの  List every use of `unsafe`
Rust ファイルから、        in Rust files under this
unsafe を探して…           directory.

┌────────────────────────────────────────────────┐
│ List every use of `unsafe` in Rust files under │
│ this directory.                                │
│                                                │
│   [Send]   [Edit]   [Cancel]      → Termux     │
└────────────────────────────────────────────────┘
```

- **RAW** follows streaming recognition while the button is held.
- **INTENT** is the interpretation: the shortest clear instruction carrying the
  user's meaning and every constraint (names, numbers, paths, terms). The
  review popup appears **instantly on release showing the current buffer**:
  INTENT when it is ready, RAW otherwise — the user never waits for
  interpretation and can release as soon as RAW reads well. If interpretation
  is still in flight, it continues and updates the popup preview if it lands
  before Send is tapped. Only the popup's Send delivers anything.
- **Send** delivers via the routing rules (below). **Edit** opens a small
  editor; closing it returns to the review popup (the editor also has its own
  Send). **Cancel** discards.
- Returning to the popup is safe because the overlay persists under any
  activity; a stale popup auto-cancels after ~2 minutes or when a new session
  starts.

Japanese is first-class; English and mixed Japanese/English/code work too.

## Non-goals for the MVP

- local or server-side transcription beyond the built-in recognizer / OpenAI
  realtime fallback
- wake-word or always-on microphone (mic runs only while holding)
- voice *output* from the agent (reading replies aloud)
- running the agent, spawning terminals, or managing agent sessions
- auto-executing anything the LLM produced without the user's explicit Send
  confirmation
- system-wide text replacement / rewriting of ordinary dictation (that is the
  macOS prototype's job)
- iOS, Wear OS, watch companions
- cloud backend infrastructure of our own

First success criterion: **hold, speak a task, release, review popup shows the
intent, Send — the instruction is typed into the agent's session in Termux and
submitted, and the agent acts on it**.

## Platform constraints

### Minimum OS

```text
Android 10 (API 29), targetSdk current (34 or newer at implementation time)
```

No API above `minSdk` without a guard and a working fallback path. Behavior
changes with `targetSdk` (foreground-service rules, clipboard access, overlay
creation) must be checked against the *chosen* target at implementation time,
not assumed from memory.

### Preferred implementation stack

- Kotlin, a single application module, minimal dependencies.
- Overlay: `WindowManager` + `TYPE_APPLICATION_OVERLAY` (`SYSTEM_ALERT_WINDOW`).
- Accessibility: `AccessibilityService` (target detection, `ACTION_SET_TEXT`,
  optional volume-key key filtering).
- Speech: `SpeechRecognizer` (default) / OpenAI realtime WebSocket (fallback
  provider behind the same `Transcriber` interface).
- Networking: OkHttp (WebSocket) + direct HTTP to the OpenAI Responses API.
  No OpenAI SDK.
- Persistence: `SharedPreferences` for settings only. No database.
- No Jetpack Compose unless it genuinely simplifies a screen it must render;
  plain Views are acceptable and smaller. Core logic lives in pure Kotlin
  (testable without Android).
- Avoid adding dependencies that pull in large runtimes.

## External API

Interpretation: **OpenAI Responses API** only.

```text
OPENAI_INTENT_MODEL=gpt-5.6-luna      # cheap, low-latency; reasoning.effort=none
OPENAI_API_KEY=...                    # from the app's settings screen, never in code
```

- The API key is user-entered in settings, stored where Android protects it
  (EncryptedSharedPreferences or Keystore-backed storage at implementation
  time). Never hard-code, never log, never screenshot.
- RAW: system `SpeechRecognizer` by default — on-device, free, no key. The
  OpenAI realtime WebSocket path (`gpt-live-transcribe`) is the documented
  fallback provider for devices without Play Services and the reference for
  the `Transcriber` interface; the macOS prototype's verified wire behaviour
  (model in `session.update`, no server turn detection, client turn commits,
  `session.updated` readiness) is the spec for it.
- Both providers sit behind one `Transcriber` protocol; OpenAI wire types stay
  at the provider boundary.

## Design

The rules a change must not break (full design in `DESIGN.md`):

- **Never run on voice alone.** Speaking, releasing and dismissing never send
  anything; only an explicit **Send** on the review popup delivers the INTENT.
  For Termux that Send includes submission (Enter), so the agent starts
  immediately — there is no second confirmation step.
- **Deliver what the popup shows, and only that.** The preview is the buffer
  snapshot at release — INTENT if ready, RAW otherwise — and Send delivers
  exactly the preview at tap time (an in-flight interpretation may update the
  preview if it lands first). Nothing is delivered except by an explicit Send
  on displayed text; text edited after Edit is user-owned.
- **Commit only what is displayed** — the popup preview is the contract; the
  target names itself; a stale popup (timeout, new session) can never send.
- **Interpreting means no invention.** The INTENT model adds no facts, names,
  files, flags or steps the user did not say; ambiguity surfaces as the closest
  honest reading.
- **No credentials in source or logs; no audio written to disk.**
- **Keep the provider boundary.** Transcriber and interpreter are separate
  interfaces; swapping RAW provider or INTENT model must not change app code.
- **Session identity and monotonic revisions** reject stale events; no task
  from a previous session may update a new one.
- **Core logic is pure Kotlin** — state machine, transcript store, scheduler,
  routing rules run and are tested without Android.

## Permissions

One-time grants, each explained in-app, not silently assumed:

- `RECORD_AUDIO` — microphone; used only while a button is held.
- `SYSTEM_ALERT_WINDOW` — the floating panel/button and review popup.
- `BIND_ACCESSIBILITY_SERVICE` — target detection (`ACTION_SET_TEXT` into other
  apps, Termux identification, optional volume-key filter). Must not read text
  fields except the focused editable node being written to.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` — mic use behind the
  overlay; a persistent notification is mandatory and doubles as Start/Stop
  controls.
- Battery optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) —
  otherwise the service gets dozed mid-session.
- Termux side: **tmux** installed, `allow-external-apps=true`, and the
  `com.termux.permission.RUN_COMMAND` runtime grant; documented in README.

Screen-on is the supported scenario; follow the chosen targetSdk's
foreground-service microphone rules exactly.

## Logging

Structured events (a small `Log` object; logcat + an optional in-app dump):

```text
session start/end
recognizer connected / failed (and which provider)
first RAW text latency
intent request/response revision numbers
intent latency, stale intent discarded
popup shown (waiting/reviewing), send/cancel/edit chosen
deliver.route=termux|settext|clipboard, deliver.target=<pkg>
deliver.failed, no target, accessibility missing
API/network errors
```

No raw audio. RAW/INTENT text logged only with a debug flag on.

## Failure behavior

- Recognizer error mid-session: surface in the panel, allow cancel/retry.
- Interpretation failure: the preview simply keeps whichever text it had
  (INTENT if one existed, else RAW); a small ↻ retry in the popup footer
  re-runs interpretation; nothing is delivered without an explicit Send.
- No target at Send time: fall back to clipboard and *say so* in the popup.
- Accessibility not granted: delivery falls back to clipboard with a clear
  notice; the panel still works for everything else.
- Network failures never crash; reconnects are automatic for the realtime
  fallback provider.
- A second session cannot be started while a popup is pending; the pending one
  auto-cancels first.

## Manual test scenarios

On a real phone, at minimum:

### Japanese task with filler and correction

Say:

```text
このディレクトリの Rust ファイルから、えーと、unsafe を探して、リストにして
```

INTENT ≈ "list every use of `unsafe` in Rust files under this directory".
Send into Termux — typed in and submitted — the agent acts. All constraints
survive.

### Number and name fidelity

「3つ目と5つ目のファイルを消して」 — both numbers survive in the INTENT.

### Mixed technical language

Japanese containing `Git`, `Rust`, `Swift`, `WebSocket`, `OpenAI` and flags
(`--no-default-features`). Transcription keywords in settings matter here.

### Command-like speech

「このディレクトリ以下のRustファイルからunsafeを探すコマンドを実行して」 — the
instruction lands in the session and is submitted on Send; the user *chooses*
whether to let the agent run it. Check no step not spoken appears.

### Ambiguity

「あいつに伝えておいて」 — INTENT invents no name; you re-speak with the name.

### Directives

「短くして」 — INTENT is shorter. 「英語で」 — English INTENT. 「この通り入力して」 —
INTENT is the literal cleaned text.

### The review popup loop

- Release before INTENT is ready → popup shows the raw text immediately, Send
  available; if INTENT lands before the tap it replaces the preview.
- Edit → editor with text → back → popup with the edited text → Send.
- Edit → Send from the editor directly.
- Cancel mid-popup → nothing delivered.
- Slide off the button while speaking → silent cancel, no popup.
- Leave the popup open ~2 min → auto-cancel; start a new session → old popup
  gone.

### Delivery matrix

Termux frontmost → tmux `send-keys` (submitted on Send); another app with a focused text field →
`ACTION_SET_TEXT`; Termux closed / no field → clipboard with notice. Try all
three routes, and a two-app (Termux + messenger) focus switch.

### Long hold and rapid restart

Hold and speak continuously for at least 2 minutes; start → speak → Cancel,
immediately start again: no text from the first session may leak into the
second.

## Performance targets

```text
panel visible on session start:        < 200 ms after touch-down
RAW first useful text:                 as quickly as the recognizer permits
RAW UI after event:                    < 50 ms
INTENT refresh cadence:                about 0.5–1.0 s during speech
INTENT one-shot latency:               ~1–2 s after release (interpret, not execute)
popup on release:                      immediate; wait state for INTENT
main-thread stalls:                    none perceptible
idle CPU/battery:                      effectively negligible (no mic when not held)
```

## Repository shape

```text
.
├── AGENTS.md
├── DESIGN.md
├── STATUS.md
├── README.md
├── settings.gradle.kts / build.gradle.kts
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/dev/voicecommander/
│   │   │   ├── core/         pure Kotlin: state machine, TranscriptStore,
│   │   │   │                 IntentScheduler, routing rules, Log, providers
│   │   │   ├── overlay/      floating button + panel + review popup
│   │   │   ├── input/        AccessibilityService, volume-key filter
│   │   │   ├── deliver/      TermuxCommitter, SetTextCommitter, ClipboardCommitter
│   │   │   ├── editor/       edit activity
│   │   │   ├── settings/     key entry, keywords, trigger options
│   │   └── res/
│   └── src/test/             JVM tests: state machine, store, scheduler, routing
│   └── src/androidTest/      instrumented: overlay visibility, permission flow
├── scripts/                  fake-openai.py (port), adb helpers, fake-agent
│                             (a pty REPL side-channel for device checks)
└── samples/                  reference utterances (wav) + expected INTENTs
```

Core logic must not import Android classes (`core/` is JVM-testable). The exact
layout can change; preserve core/overlay/deliver separation.

## Development workflow

```bash
./gradlew test                 # JVM unit tests, no device
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedAndroidTest # instrumented
```

Port `scripts/fake-openai.py` from the prototype for offline plumbing checks of
the OpenAI path. Delivery to Termux is verified on a real device; for
automated checks use the pty fake-agent running in Termux (or an emulator
adb shell session) and assert the exact bytes the tmux channel would deliver. The
macOS replay harness has no direct Android equivalent — its job is covered by
samples + expected-INTENT scoring in JVM tests.

Debugging: `adb logcat | grep VoiceCommander`, plus the in-app debug dump
behind a settings toggle. Text logging stays behind a debug flag.

## Project operations (how this repo is actually managed)

- **Origin:** https://github.com/tai/voice-commander — pushed via SSH
  (`git@github.com:tai/voice-commander.git`), default branch `main`.
- **GitHub access:** `gh` CLI is preconfigured with auth on this machine —
  issues and PRs are created/closed through `gh issue …` / `gh pr …`. The
  open backlog is the design ledger; see STATUS.md "Discussed / designed".
- **Issue conventions:** each feature is a ticket first (concise spec + open
  questions), then implementation on a `feat/*` branch, verified on a real
  device, merged to `main`, pushed, and the ticket closed with a
  verified-summary comment. Closed issues keep the deferral notes.
- **Devices:** phone `ZY22JSFS7R` (Motorola Edge 50s Pro, API 36) and
  emulator `emulator-5554` (AVD `Medium_Phone`, API 37). `adb` is NOT on the
  shell PATH — use `$HOME/Library/Android/sdk/platform-tools/adb` or the
  Makefile (`make install DEVICE=…`, `make run`, `make inject TEXT="…"`).
- **Headless driving:** transcripts are injected over the INJECT broadcast
  (`make inject`) to run RAW→INTENT→delivery without a microphone; UI is
  verified via `uiautomator dump`/screencap+OCR; config is written with
  `adb shell run-as dev.voicecommander` into `shared_prefs/vc.xml` (API key
  from `./dot.env`, which is gitignored).
- **Gotchas that reset state:** `am force-stop` disables the accessibility
  service (re-enable via `settings put secure …`); the API key lives in
  dot.env and is restored to the phone via the prefs write above.

## Implementation order

Work vertically.

### Milestone 1 — Shell, permissions, trigger

- App skeleton, permission walkthrough (mic/overlay/accessibility/battery),
  foreground service + notification.
- Floating hold button: touch-down starts a fake RAW stream, touch-up ends;
  slide-off cancels. Verify on a real phone.

### Milestone 2 — RAW

- `Transcriber` interface; `SpeechRecognizerProvider` wired to the button.
- RAW streams in the panel; begin/end/cancel reliable; recognizer errors shown.

### Milestone 3 — INTENT

- `IntentPrompt` + `IntentScheduler` (debounce, monotonic revisions, one
  in-flight — ported semantics) on the OpenAI Responses API.
- Review popup: buffer-snapshot preview (INTENT if ready, else RAW), Send
  enabled immediately, source tag, ↻ retry on interpretation failure.
- Unit tests: prompt contract (constraints survive, no invention), scheduler
  races, popup state machine.

At this point verify interpretation quality on the samples + real spoken
tasks — this is the product; if constraints are lost here, everything
downstream is lost.

### Milestone 4 — Delivery

- Routing rules → `TermuxCommitter` (tmux `send-keys -l` + Enter on Send),
  then `SetTextCommitter` (`ACTION_SET_TEXT`), then `ClipboardCommitter` fallback.
- Target naming in the popup footer; no-target fallback path.
- Verify the delivery matrix manually and the Termux path against
  `fake-agent` on device.

### Milestone 5 — Edit, hardening

- Edit activity (Send from editor, back returns to popup), directives
  (shorter/English/verbatim), popup timeout, session identity.
- Error/reconnect behaviour, logging events, encrypted key storage, battery
  behaviour over a day, the optional volume-key trigger.

### Milestone 6 — Measure and decide

- Daily-drive the loop; measure intent latency/cost, constraint fidelity vs
  expected INTENTs, how often Edit is used, where users stall.

Do not jump to system-wide transcription or agent integrations before the
loop is daily-used and its real bottleneck is named.

## Development rules for agents

1. Keep minSdk/API-29 compat unless told otherwise; check targetSdk-era
   behavior changes at implementation time.
2. Prefer the smallest working native implementation; no new dependencies
   without a demonstrated need.
3. No backend of our own, no local ML, no raw-audio persistence.
4. Never hard-code secrets; never log keys or transcript text by default.
5. No arbitrary sleeps to hide races — use ids/revisions/session state.
6. Keep OpenAI wire types at the provider boundary; Transcriber and
   interpreter stay separate interfaces.
7. Test pure logic with JVM tests before touching the device.
8. **Never run on voice alone** and **never auto-send RAW**: the review popup
   is the guard, the user's Send is the trigger (for Termux it submits too).
9. Do not over-engineer extensibility before hold → speak → INTENT → Send →
   agent response works.
10. Record discoveries and deviations in `DESIGN.md` rather than silently
    diverging.
11. If current OpenAI API documentation differs from the prototype's verified
    wire behaviour, verify the official docs and adapt, preserving the
    architecture.

## Definition of MVP complete

On a real Android phone (API 29+, Play-Services recognizer):

1. The app starts, grants its four permissions through the walkthrough, and
   keeps a foreground service + notification running.
2. Holding the floating button shows RAW live; releasing opens the review
   popup.
3. INTENT appears in the panel during the utterance; the review popup opens
   instantly on release showing the current buffer (INTENT if ready, else
   RAW) with Send available immediately.
4. Send into Termux puts exactly the displayed INTENT at the agent's prompt
   and submits it; the agent starts working immediately.
5. Only an explicit Send on the displayed preview delivers anything; the
   preview falls back to RAW when INTENT is not ready, so the user never has
   to wait for interpretation.
6. Edit and Cancel behave per the popup contract, including the return-to-
   popup loop and slide-off silent cancel.
7. Constraints survive interpretation (numbers, names, paths, terms) and
   nothing is invented — demonstrated with the manual scenarios and scored on
   samples.
8. Spoken directives (shorter / English / verbatim) work.
9. The delivery matrix works (Termux / text field / clipboard with notice).
10. Rapid stop/start leaks nothing between sessions.
11. The app stays responsive and light; idle battery is effectively nil
    (no mic when not held).
12. Interpretation latency and cost are measured, not assumed.

After this, evaluate on evidence: volume-key trigger refinements, per-agent
profiles, spoken correction of a committed INTENT, voice output, or device-
specific polish. Do not choose the next step before the MVP's actual
bottleneck is observed.