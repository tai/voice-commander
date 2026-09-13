# VoiceCommander — design

Everything about how this app is designed: where it comes from, the design as
specified, the decisions awaiting review, the ideas that are not built yet,
and — once code exists — where the two part company.

- **`README.md`** — what the app is for, installation, usage.
- **`DESIGN.md`** (this file) — design, decisions, deviations, plans.
- **`AGENTS.md`** — project structure, workflow, policy, acceptance criteria.
- **`STATUS.md`** — what is done, what is measured, what is outstanding.

## What this project is, and what it is not

VoiceCommander is the **voice-ime pipeline re-targeted twice**: the consumer of
the interpretation changed (clean text for typing → a task for an agent), and
the platform changed (macOS → Android). The macOS prototype
(`../voice-ime/`) proved the product's core: live RAW + race-safe debounced
LLM interpretation, a hold-to-talk HUD, and insertion that never executes. Its
Swift code is a **reference spec**, not portable code — this is a fresh Kotlin
implementation with a different RAW source and a confirmation workflow the
platform requires.

What changed because it is Android:

1. **RAW is on-device.** `SpeechRecognizer` (system, free) replaces paid
   OpenAI realtime transcription for the default path. OpenAI realtime remains
   as the fallback provider for GMS-less devices. Cost drops to the INTENT
   calls only.
2. **Commit is user-confirmed.** Android has no clean Esc-while-holding, so
   release does not commit: a review popup (Send / Edit / Cancel) is the
   platform's answer to the prototype's Esc key and its "see it before it
   lands" promise.
3. **Delivery is routed.** Termux via tmux `send-keys` (submitted on Send),
   generic apps via `ACTION_SET_TEXT`, clipboard as the universal fallback —
   instead of a single paste path.

What survives unchanged from the macOS design: the interpretation contract
(intent over wording, constraints sacred, no invention), the race handling,
the safety invariant (never auto-execute; deliver only what the popup
displays), and the directive set.

## Decisions awaiting review

Defaults stand unless the author says otherwise. The android-only ones are new;
the others carried over from the macOS design.

| # | Decision | Recommended default | Alternatives / note |
| --- | --- | --- | --- |
| A1 | RAW provider | system `SpeechRecognizer` (free, on-device); OpenAI realtime as switchable fallback | OpenAI realtime costs per spoken hour but works without Play Services |
| A2 | Primary trigger | floating hold-button (touch-down listen, touch-up review, slide-off cancel) | volume-key trigger opt-in later; quick-settings tile not in MVP |
| A3 | Review popup payload | popup opens instantly on release showing the current buffer: INTENT if ready, else RAW; Send enabled immediately | fast release: the user never waits for interpretation; in-flight INTENT may update the preview before Send |
| A4 | Edit surface | small editor Activity (system keyboard reliability) | in-overlay editor is OEM-flaky; rejected |
| A5 | Delivery routing | frontmost-app → Termux (tmux, submitted on Send) \| focused text field `ACTION_SET_TEXT` (text only) \| clipboard+notice | Send is the explicit user hand-off |
| A6 | Popup lifecycle | auto-cancel ~2 min or on new session; preceding popup cancelled first | no stale INTENT can ever be sent |
| A7 | Session start rule | popup pending blocks a new session (resolves as A6) | — |
| A8 | Min OS | Android 10 / API 29 | newer minSdk needs a reason |
| A9 | Storage | EncryptedSharedPreferences / Keystore for the key; SharedPreferences otherwise | Keychain analogue from the prototype |
| A10 | UI toolkit | plain Views; Compose only if it earns its weight on a screen | — |
| A11 | Sliding topics | directive set stays 3 (shorter / English / verbatim); cancel handled by popup | more directives when use shows need |

## Design as specified

### High-level architecture

```text
[floating button]  touch-down ─► AudioCapture (SpeechRecognizer | OpenAI realtime)
        │                                   │ partial results
        ▼                                   ▼
   listening ─────────────────────────► TranscriptStore ──► RAW panel
        │                                    │  debounced snapshot (~0.7 s)
        │ touch-up                           ▼
        ▼                              IntentScheduler ──► OpenAI Responses API
   reviewing (popup: preview + Send/Edit/Cancel) ◄──────── INTENT text
        │ Send
        ▼
   Router: TermuxCommitter → SetTextCommitter → ClipboardCommitter
        ▼
   agent session in Termux (Send = tmux send-keys + Enter) ──► agent runs
```

Modules:

```text
core/        pure Kotlin: SessionState (state machine), TranscriptStore,
             IntentScheduler, IntentPrompt, DirectiveParser, Router rules, Log
overlay/     floating button, RAW/INTENT panel, review popup (all overlay windows)
input/       AccessibilityService (target detection, ACTION_SET_TEXT,
             optional volume-key filter)
deliver/     TermuxCommitter, SetTextCommitter, ClipboardCommitter
editor/      edit activity with its own Send
settings/    key entry, keywords, trigger options
audio/       Transcriber interface + SpeechRecognizerProvider + OpenAITranscriber
```

`core/` has no Android imports; everything worth unit-testing lives there.

### The state machine

```text
idle
  │ touch-down on button
  ▼
listening            RAW streams; slide-off → idle (silent cancel)
  │ touch-up on button
  ▼
reviewing            popup opens instantly with the buffer snapshot: INTENT if
  │                  ready, else RAW, source-tagged. Send enabled immediately;
  │                  in-flight INTENT may update the preview before Send.
  ├─ Send ─► sending ─► idle
  ├─ Edit ─► editing ─► (editor; Send from editor → sending → idle,
  │                     back/discard → reviewing)
  └─ Cancel / timeout(2 min) / new session → idle (discard)
```

Errors: interpretation failure → the preview simply keeps its text (INTENT if
one existed, else RAW) and a small ↻ retry in the footer re-runs it; Edit is
always available to take over the text. Recognizer error mid-listen → panel
error + cancel/retry.

### Session identity and races

Ported semantics from the prototype, unchanged:

- Every interpretation request carries a monotonic revision; a response applies
  only if its revision exceeds the last applied one. A late response can never
  move the shown INTENT backward; `reset()` marks outstanding work stale
  rather than zeroing the counter.
- One request in flight, never cancelled mid-flight (the prototype measured
  that cancelling on every delta meant no candidate appeared mid-speech).
- Debounce ~0.7 s on RAW change, shorter settle on stable segments.
- Session ids reject anything from a previous session.
- The routing snapshot (target app, INTENT text, revision) is captured the
  moment Send is tapped; nothing arriving later changes what is delivered.

### The interpretation contract

`IntentPrompt` is the product. The model is a **communicator between the
speaker and the agent**; output is the instruction text shown in the popup.

- **Intent over wording**: fillers, false starts, corrections resolved; INTENT
  says what the user wants done, directly ("List every use of `unsafe` in Rust
  files under this directory", not "the user would like us to perhaps…").
- **Constraints are sacred**: every noun, number, name, path, tool, flag,
  destination survives. Loss of a constraint is the failure mode this product
  exists to catch; `TextDiff`-style scoring against expected INTENTs is the
  test.
- **No invention**: no facts, names, steps, flags or sub-tasks the user did
  not say. 「あいつに伝えておいて」 must not produce a name; the honest reading
  surfaces and the user re-speaks.
- **Do not execute in prose**: the INTENT describes the task; it narrates
  neither running it nor a plan.
- **Technical speech preserved**: terms, identifiers, flags, code, paths
  survive (settings keywords tune the recognizer, as measured in the
  prototype).
- **Directives told apart from content**: 「もっと短く」/「英語で」/「この通り入力して」
  are recognized (`DirectiveParser`) and applied to the INTENT, never leaked
  into it. The set is deliberately three; the popup's Edit/Cancel cover the
  rest. Unmatched directive-like speech falls back to content.
- **Output only the instruction**: no preamble, quotes, or fences unless the
  instruction itself needs them; empty input → empty output.
- **Stability under revision**: small RAW changes shouldn't rewrite the whole
  INTENT; debounce + revisions cover ordering, the prompt covers wording drift
  — and the popup's Edit covers the residue.

### RAW providers

```text
interface Transcriber {
    start(onPartial, onFinal, onError)
    cancel()
}
```

- **SpeechRecognizerProvider** (default): system recognizer, language from
  the session's transcript (Japanese-first default, switchable), keywords fed
  via the recognizer's extras where supported. Partial results stream RAW.
- **OpenAITranscriber** (fallback): the prototype's realtime WebSocket path,
  ported to OkHttp — the reference for wire behaviour (model in
  `session.update`, no server turn detection → client `TurnCommitPolicy`,
  `session.updated` readiness, commit ≥100 ms).

Choosing between them is a setting, not a code change.

### The review popup

An overlay window (not a dialog attached to an activity — it must survive app
switches and the editor round-trip). Layout: preview of the **buffer snapshot
at release** — INTENT if ready, else RAW — with a source tag ("interpreted" /
"raw"), the target line ("→ Termux" / "→ <app>" / "clipboard"), and the three
buttons. Send is enabled immediately: releasing the button never waits for
interpretation. If an interpretation is in flight, it continues and, if it
completes before the user taps Send, replaces the preview — releasing on good
RAW and grabbing the cleaner INTENT a moment later are both honoured, and Send
captures exactly the preview at tap time. On interpretation failure the
preview keeps its text and a small ↻ retry sits in the footer; Edit is always
available. Popup auto-cancels ~2 min or when a new session starts. Draggable;
never covers the whole screen.

Why Send doesn't wait: the user asked for release-as-soon-as-it-reads-well,
and the raw text can be exactly right. The popup remains the guard — nothing
is delivered except by an explicit Send on displayed text — so the fallback
is safe: nothing is delivered by voice alone — Send is the explicit, visible
hand-off — and the raw option is user-confirmed, not automatic. For Termux,
Send also submits (Enter), so the agent starts immediately; that is the
author's explicit choice. This deliberately relaxes the
prototype's commit rule (release inserts the visible text and discards the
in-flight rewrite): the popup's review window is long enough that a completed
interpretation is worth showing rather than throwing away.

### Editing

`Edit` opens a one-screen activity: the current text (INTENT, or RAW when in
the error path) in a normal multi-line field with the system keyboard and the
editor's own **Send / Cancel**. Send delivers the edited text (user-owned);
Cancel/back returns to the popup, which still shows the pre-edit INTENT in the
waiting/ready state — the loop the author specified. The overlay windows
persist under the activity, which is what makes "back to the popup" free.

In-overlay editing was rejected: a focusable `TYPE_APPLICATION_OVERLAY`
hosting the system keyboard is unreliable across OEMs (keyboard doesn't appear,
focus fights), while an activity gets a real keyboard everywhere at the cost of
one task switch during a deliberate action.

### Delivery

Routing, evaluated at Send time:

1. **Termux frontmost** → `TermuxCommitter`: a `RUN_COMMAND` script runs
   `tmux send-keys -l <INTENT>` then `tmux send-keys Enter` — the INTENT enters
   the agent session's input queue **submitted**, so the agent starts working
   immediately. (Auto-submit is explicit and popup-confirmed; off is a one-line
   flag.)
2. **Other app, focused editable node** → `SetTextCommitter`:
   `AccessibilityService.performAction(ACTION_SET_TEXT)` on the focused
   editable node. No focus stealing, no paste. Fails safely on secure fields
   and custom views → clipboard fallback.
3. **Otherwise** → `ClipboardCommitter`: put INTENT on the clipboard; popup
   says "copied — paste where you want".

The user-initiated Enter is the execution boundary in every route. The
prototype's rule — synthesize no Return — is invariant, not a default.

### The floating button and panel

`TYPE_APPLICATION_OVERLAY`, draggable, collapsible to the button alone.
Touch-down on the button starts the session; touch tracking decides slide-off
(silent cancel) vs touch-up (review popup). The panel shows RAW (live) and
INTENT as they arrive; collapsed, only the button remains and the notification
still holds Start/Stop. The overlay is created from the app's own foreground
activity to comply with overlay-creation rules at the chosen targetSdk.

### AccessibilityService

Responsibilities: frontmost-app detection (window-state events) for routing
and the popup footer; `ACTION_SET_TEXT` on the focused editable node; the
optional volume-key `onKeyEvent` filter (hold Volume Up = listen, release =
review). The service reads no text fields other than the focused node being
written to, and logs no content.

### Configuration

Settings screen: API key (encrypted storage), intent model name, language
default, transcription keywords, trigger options (button / volume keys), debug
log toggle. No config file on disk beyond preferences; no env file story on
Android (the macOS `.env` pattern has no analogue here).

### Concurrency

UI and state on the main thread; recognizer/network callbacks marshalled to
the same, with session ids + revisions rejecting stale delivery. No background
work without the foreground service. The mic runs only while the button is
held (or volume key held), satisfying "no always-on microphone".

### Testing strategy

- **JVM unit tests** (the bulk): state machine incl. the popup contract
  (waiting→ready→error, timeout, new-session invalidates old popup),
  TranscriptStore (revisions never duplicate), IntentScheduler (debounce,
  one-in-flight, late-response rejection), IntentPrompt contract (constraints
  survive — the numbered test the prototype's fidelity test inspired; no
  invention; directives classified), Router rules (frontmost → route, disabled
  Send until ready), transcript-vs-expected INTENT scoring on samples.
- **Instrumented**: overlay visibility across apps, permission flow walkthrough.
- **Device checks**: the delivery matrix by hand; the Termux route against a
  pty `fake-agent` REPL (asserted bytes at the prompt); `fake-openai.py`
  (ported) for offline OpenAI plumbing.
- Failure modes surface as *data* (error rates, latency, routes chosen, edit
  frequency) via the log, not judgment by eye.

## Inheritance from the prototype

| Piece | Carry-over | Change |
| --- | --- | --- |
| TranscriptStore (stable items + mutable tail, revision tolerance) | semantics | Kotlin port; recognizer deltas are usually final-per-segment, so the tail logic is simpler |
| IntentScheduler (debounce, one in-flight, monotonic revisions, stale-forever reset) | semantics, tested | Kotlin port, drives INTENT |
| Session state + session ids | semantics | + reviewing/editing states (popup contract) |
| IntentPrompt family | product text | the macOS `RewritePrompt` lessons: a cheap model summarises unless told not to — the contract is test-asserted |
| TextCommitter mechanics | none (paste+⌘V) | replaced by routed delivery; the *invariant* (no Return) carries |
| HUD | none (AppKit) | overlay windows; the review popup is new, an Android necessity |
| Replay harness / reference set | concept | samples + expected INTENTs scored in JVM tests; no audio replay (recognizer is device-owned) |
| `fake-openai.py` | script | ported |
| Logging event set | list | + route, popup, provider events |
| HotKey/PushToTalk/FocusTracker | concepts | button + optional volume-key; AccessibilityService replaces FocusTracker; slide-off replaces Esc |
| 46 unit tests | as reference suite | re-derived for Kotlin, not copied |

## Ideas and plans

Not built. What each would buy and what would justify it.

### Volume-key trigger (polish)
Hold Volume Up to speak, release to review. Real press-and-hold hardware, but
fights volume control and is OEM-fragile; opt-in, after the button path is
daily-proven.

### Quick settings tile / notification quick-start
Tap-to-start, tap-to-cancel (no hold, no popup on start). Cheap; only once
"hold" is confirmed as the interaction users actually want.

### Spoken directive expansion
「最後の一文を消して」「箇条書きにして」… applied to INTENT before the popup.
Current three + Edit cover the observed needs; grow only when use shows it.

### Spoken correction of the delivered text
Fix the INTENT *after* Send (「いや、2番じゃなくて3番」) by editing the terminal
input line — needs cursor/position knowledge in the target; defer.

### Per-agent profiles
A prelude the interpreter prepends (terse agent vs structured agent). Defer:
first criterion is that one agent works well.

### Voice output from the agent
Read the agent's reply aloud — drags in TTS and terminal-capture; explicitly
out of MVP.

### Push-to-talk on a watch
A Wear OS button is real hardware press-hold; a companion app is real scope.
Defer until the phone interaction itself is settled.

## Deviations from this specification

Empty while the project is documents-only. Once code exists, every place the
built system parts company with the design above is recorded here with its
reason, in the prototype's format — AGENTS.md rule 10 requires it.