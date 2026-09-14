# VoiceCommander — status

Snapshot: **2026-09-13, `main` @ `8d544d9`** (+ working tree edits as recorded
below). Android app, Kotlin, one module; 28 unit tests green. Built and driven
via adb from this Mac against:
- **Phone:** Motorola Edge 50s Pro (`ZY22JSFS7R`, Android 16/API 36) — the
  real device, everything app-side is installed there and verified on it.
- **Emulator:** `emulator-5554` (API 37 AVD `Medium_Phone`) — headless
  playback/verification target.

What this app is for and how to use it: `README.md`. Design decisions and
deviations: `DESIGN.md`. Structure, workflow, rules: `AGENTS.md`.

## What is done, and how we know

| Capability | Evidence |
| --- | --- |
| Hold-to-talk loop: button → RAW → INTENT → review popup (Send/Edit/Cancel) → delivery | driven end-to-end on the phone and emulator via `make inject`; real speech confirmed by the author ("voice is recognized, interpretation works") |
| Interpretation (OpenAI Responses, gpt-5.6-luna, `reasoning.effort=none`) with debounce + monotonic revisions | `intent.ready` logs; the demo's single-utterance correction RAW「1枚…いや…3枚」→ INTENT「3枚追加して」 |
| Termux delivery via **tmux** `send-keys -l` + Enter on Send (agent starts immediately) | marker-file proof (`CLEAN-E2E-OK`); the pi-agent demo edited the live README from a spoken intent; refusal notes for `termux-input` (does not exist), pty/TIOCSTI/master/a11y injection (all proven dead) — channnel matrix in DESIGN.md/STATUS history |
| Mode framework PoC: **AI interaction** (instructive, NOP on non-actions) + **Document edit** (tone/length preserving, Markdown-light, NOP on commands) | live contrast: prose → NOP under AI mode; same prose → preserved dictation under Document edit |
| Mode switcher: short-tap on the button opens the HUD mode menu (Option A), selection persists | `mode.menu.open` / `mode.selected` logs; device-verified |
| NOP contract | `NOP: <reason>` surfaced in HUD, nothing sent (device logs) |
| Config UI: hamburger + drawer (General / Mode / Status); mode enable/disable; per-mode **title** and **prompt** editors (Save / Restore defaults); overrides honored by interpretation | device-verified incl. OCR: custom prompt → "HELLO WORLD"; custom title → "• Command" in the menu |
| Notification gains a **Setup** action (besides Stop) | `actions=2`; opens the config activity |
| Fixes discovered while verifying: a11y enabled-but-not-bound detection (AccessibilityManager), systemui/IME window events no longer clobber the frontmost target, force-stop disables a11y binding (re-enable via settings), DarkActionBar hid/blocked the top UI under edge-to-edge (→ NoActionBar), status-bar padding for the top bar | each root-caused (OCR/pixel forensics for the theme one) and regression-checked |
| Demo/hackathon assets | `doc/asset/`: `demo.mp4`/`demo-short.mp4` (English take: pi launch → voice correction → pi edits README), `demo.gif`, `demo-short.gif`, `demo-short-zoom.gif` (embedded at README top), shot-*.png. README embeds only the zoom GIF |
| 28 unit tests | scheduler races, directives, router (live-target fallback), transcript, modes (NOP sentinel, prompt/label overrides, enable filter) |

## Discussed / designed, not built

Tracked as GitHub issues (tai/voice-commander):

- **#1 framework — purpose-specific modes** (mode = prompt + NOP contract).
  Implemented for two modes; the framework ticket stays open for the rest.
- **Modes not yet implemented:** #2 SQL analysis (dialect/schema/destructive-
  confirmation open questions), #3 Jupyter-based analysis (stateful namespace,
  ipython-first target), #4 AI interaction (shipped as the PoC default;
  ticket open for instructive-only contract strictness + agent tool-space
  questions), #5 Document edit (shipped; ticket open for structure
  aggression/language-strictness questions).
- **#8 shareable modes + `voice-commander-modes` store** — mode → `mode.json`,
  one-tap export/import, PR-based exchange repo. Requires modes to leave the
  enum (dynamic installed modes).
- **#9 settings backup/restore** — encrypt-and-upload (passphrase → PBKDF2 →
  AES-GCM `backup.vcrypt`), download-and-decrypt; API key in scope makes it
  load-bearing; file-first transport, gist/Drive later. Reminder-level detail.
- **#10 Mode config: add / remove a mode** — user-created modes; built-ins
  disable-only (proposal); cascades into clamp/menu.
- **Deferred, agreed:** per-mode validators (syntax check before Send —
  mentioned in #2/#3), voice-switched modes ("switch to Linux mode" as a
  directive), volume-key trigger remains opt-in.

## Known limitations / not verified

- **Nearly all pipeline verification used injected transcripts** (`make
  inject`), not a live microphone; real-speech acceptance was confirmed once
  by the author but RAW-quality (SpeechRecognizer, ja-JP default) is
  stress-untested. Keywords setting for the recognizer does not exist yet.
- **Termux delivery requires the tmux setup**: agent running inside a tmux
  session named in Settings (`agent`), `allow-external-apps=true`, and the
  `com.termux.permission.RUN_COMMAND` runtime grant. Non-tmux targets fall
  back to clipboard + paste.
- **UI verification was OCR/pixel-driven** (no visual review during dev):
  the mode menu anchors near the button but the anchor math had one
  cosmetically-off run; the review popup geometry used for adb taps.
- **An open keyboard eats overlay touches** (IME fullscreen window sits above
  overlays) — pre-existing; dismiss the keyboard before using the button.
- Force-stop disables the a11y service (Android security); re-enable after
  any `am force-stop` (Makefile/status section documents this).
- `adb` is not on the shell PATH on this Mac — the Makefile and SDK-path
  invocations are the working route.
- Long-utterance, battery, and multi-day daily-use are unmeasured.

## Open research notes worth keeping

- Android app-uid **cannot inject input into another app's terminal** (pty
  slave-write = display only; TIOCSTI silent no-op; master-fd not reachable;
  a11y has no key injection). The tmux `send-keys` route is the working
  injection channel for REPL agents; `adb shell input` works only from shell.
- Raw text is never auto-sent; raw-fallback in the popup is user-confirmed
  only; no synthesized Return except the explicit Send on the Termux route.

## Next steps (suggested order)

1. Real-speech acceptance pass on the phone (hold → speak → Send into the
   pi-in-tmux setup).
2. #10 add/remove mode UI (small, builds directly on the current Mode tab).
3. #8 mode portability (biggest architectural step: dynamic modes + schema +
   the store repo).
4. #2/#3/#4/#5 as concrete modes once dynamic modes exist; then #9 backup.