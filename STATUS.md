# VoiceCommander — status

Snapshot: **first prototype built and installed on the device** (Motorola Edge
50s Pro, Android 16, API 36; adb serial ZY22JSFS7R). Kotlin app, one module,
17 JVM unit tests green, `make build/test/install/run` work from this Mac
(JBR from Android Studio; Gradle project cache forced to local disk because
the repo lives on a network share — the prototype's mount lesson, again).

What this app is for and how to use it: `README.md`. The design, decisions,
and open questions: `DESIGN.md`. Structure, workflow, policy: `AGENTS.md`.

## Built and verified (headless + emulator)

| Piece | Evidence |
| --- | --- |
| Project builds | `assembleDebug` green under AGP 8.12 / Kotlin 2.2.20 / SDK 36 |
| Pure core logic | 17 JVM tests: scheduler (coalesce, debounce, late-response rejection, reset), directives, router, transcript buffer (partial-replacement, CJK/Latin join). Two bugs found and fixed by these tests |
| Install + launch | APK installed on the phone (Motorola Edge 50s Pro) and on the Medium_Phone AVD; MainActivity launches |
| Foreground service | running with `type=microphone`, persistent notification, Start/Stop via adb (`make start/stop`) on both targets |
| Overlay button | `TYPE_APPLICATION_OVERLAY` window, bottom-end, `SYSTEM_ALERT_WINDOW` granted |
| Accessibility service | bound, window-state + focused-node events flowing (device also has `com.termux` + `com.termux.api` installed) |
| **Full loop on the emulator** (driven via adb + `make TEXT="…" inject`): | |
| … hold → session, RAW in buffer → release → popup preview | `session.start` … `popup.show text=…` logs |
| … Send → FOCUSED_FIELD route → `ACTION_SET_TEXT` | text appeared in Chrome's omnibox; Chrome live-searched the phrase |
| … Edit → EditActivity, edit, Send → delivered; back → popup persists | focused activity + UI dumps |
| … Cancel → popup closes, nothing delivered | window count + deliver-log unchanged |

Fixes found by the emulator runs: focused-node cache went stale (Chrome
churns content events) → delivery now re-queries `findFocus(FOCUS_INPUT)`
live at Send time; frontmost was null because Chrome's omnibox fires no
window-state event → live `rootInActiveWindow` fallback; **missing
`INTERNET` permission** (no debug-manifest overlay in this repo) broke the
OpenAI call on both targets until added. One cosmetic quirk: when an IME is
up its window-state event overwrites frontmost; the route logic tolerates it
but the popup footer can show the IME package.

## INTENT verified live (test key from ./dot.env)

Real OpenAI calls through the app on **both** targets via `make inject`:

| RAW (injected) | INTENT (returned) |
| --- | --- |
| このディレクトリのRustファイルからunsafeを探してリストにして | このディレクトリのRustファイルからunsafeの使用箇所をすべて探してリスト化して |
| 過去三日間のgitログからテストを書いたコミットだけリストして | 過去三日間のgitログから、テストを書いたコミットだけリストして |

Latency ≈2 s after release (700 ms debounce + call). Revision guard proven:
`intent.ready rev=2` overwrote rev=1 of the previous session and delivered
text == displayed preview. The model sometimes echoes the input verbatim
(revision 2 did) — model behavior, not a bug.

## Direct delivery fixed (your clipboard report)

Two real bugs behind "result only goes to the clipboard", both found and fixed
with on-device evidence:

1. **Stale frontmost cache**: the delivery target came from cached
   window-state events, which get overwritten by systemui/IME windows and can
   be wrong (phone log showed `frontmost=com.android.systemui` while Termux
   was actually frontmost). Delivery now trusts the **live active-window
   root** first, with an ignore list for system chrome (`systemui`, IMEs, our
   own package). Proven on the phone: `deliver route=TERMUX
   frontmost=com.termux`. Pure logic in `Router.effectiveFrontmost` + 4 new
   unit tests (21 total).
2. **Background-thread UI crash**: the scheduler's interpretation callback
   ran on `Dispatchers.Default` and touched TextViews —
   `CalledFromWrongThreadException` on the phone (the emulator silently
   tolerated it). Every schedule callback is now marshalled to the main
   thread. Proven: zero crashes across repeat runs on both devices;
   emulator regression still delivers INTENT into Chrome's omnibox.

Tooling note: force-stopping the app **disables its accessibility service**
(Android security behavior) — re-enable after any `am force-stop`.

## Termux input injection: exhaustive channel matrix (all headless-verified)

Conclusion: **an unprivileged app cannot inject input into a Termux session on
this device (Android 16, Termux 0.118.1).** Every channel was tested with
`\n`-terminated marker-writing commands and polled via /sdcard:

| Channel | Result | Evidence |
| --- | --- | --- |
| `RUN_COMMAND` via `sendBroadcast` | no receiver exists | 0.118 has the action only on the exported `RunCommandService` `<service>` intent-filter; broadcast resolves to zero receivers |
| `RUN_COMMAND` via `startService` | **transport works** | scripts execute inside Termux (uid 10606); broadcast echo-backs verified repeatedly |
| write to pty **slave** (`/dev/pts/0`) | display-only, not input | text visibly appeared in the session (user-confirmed); `\n`-command never executed |
| **TIOCSTI** on the slave | silently no-op | ioctl returns 0, nothing reaches bash (no marker, no echo) |
| write to pty **master** via `/proc/<termux>/fd/*` | no delivery to the user session | marker absent; the one findable ptmx fd maps via TIOCGPTN to a non-session pts |
| a11y `ACTION_SET_TEXT` into `com.termux:id/terminal_toolbar_text_input` | no forwarding | `focus=true settext=true`, nothing reached the shell |
| `adb shell input text` (shell uid) | **works** | executed commands in the session (marker file) — but needs `INJECT_EVENTS`, shell-only |
| user's own keys | **works** | of course |

Why: pty device nodes are per-session 600 (app), TIOCSTI is domain-blocked,
and the session's master fd is not reachable. The `termux-input` binary does
not exist (checked the termux-api v0.59.1 package: no such script).

## v2 Termux delivery: tmux (the rethink, verified end-to-end)

The user's case made the limit concrete: *typed `echo foo ` + injected `bar` appears
as `echo foo bar` on screen, but Enter submits only the typed part* — the injected
bytes never enter the pty input queue (display-side only).

**Solved by tmux.** If the agent runs inside a tmux session in Termux (a
standard pattern), VoiceCommander drives it through tmux's own command
interface — same-UID socket access, no pty games:

```
tmux send-keys -t <session> -l "$INTENT"     # literal text into the real input queue
tmux send-keys -t <session> Enter             # submit
```

Verified headlessly end-to-end on the device: the app (via RUN_COMMAND →
tmux) sent `echo CLEAN-E2E-OK > /sdcard/vc_clean.txt` into a named tmux
session; the session's shell executed it; the marker file was written. Also
verified: Japanese text round-trips, `capture-pane -p` reads the pane back
(for HUD readout later), and user text must go through `-l` with Enter as a
separate send (otherwise tmux parses words like `Enter`/`Space` as keys).

**Flow now**: agent runs in tmux (name in Settings, default `agent`) → Send =
`send-keys -l INTENT` + `Enter` (auto-submit; the review popup is the user's
confirmation) + toast. Fallback when no tmux session is configured:
clipboard + in-session visual echo + paste.

One-time setup for the user: `pkg install tmux`, run the agent inside
tmux, set the session name in VoiceCommander settings.

## What still needs a real key or speech

1. **SpeechRecognizer RAW**: verified only by injection; real spoken input
   needs the phone (or a mic on the host + on-device recognizer).
3. **Termux route**: the `com.termux.RUN_COMMAND` broadcast reaches Termux but
   is silently dropped — Termux's `allow-external-apps=true` is not set on the
   phone. The one-liner, inside Termux:
   `echo "allow-external-apps=true" >> ~/.termux/termux.properties`
   then restart Termux (or `termux-reload-settings`). After that, Send with
   Termux frontmost delivers via tmux `send-keys` and submits on Send.

## What needs the unlocked phone (your part)

The device is pattern-locked; overlays are policy-hidden behind the keyguard,
so every interactive step still needs you:

1. **Unlock the phone.**
2. **Open VoiceCommander and add your OpenAI API key** (settings screen) —
   RAW works without it; INTENT does not.
3. **Speak a task**: hold the floating 🎤 button, speak (e.g. 「このディレクトリの
   Rust ファイルから unsafe を探して」), release → popup with Send / Edit /
   Cancel. RAW fallback shows if INTENT is not ready; Send without a key goes
   to the clipboard.
4. **Termux delivery**: device already has `com.termux` and `com.termux.api`.
   In Termux run `pkg install termux-api` and add `allow-external-apps=true`
   to `~/.termux/termux.properties`, then restart Termux. Send routes to
   tmux `send-keys` + Enter on Send (auto-submit).

## Known limitations of the first cut

- Interaction flow (hold → panel → release → popup → Edit loop → delivery)
  is verified only headlessly as far as window/component state; the feel is
  unmeasured.
- SpeechRecognizer language is ja-JP by default (device locale); the
  keywords setting does not exist yet — prototype limitation.
- tmux `send-keys` delivery verified end to end on the device (marker-file
  proof); output readback via `capture-pane` verified.
- No OpenAI key in the app, so interpretation is untested on the device.
- API key stored in SharedPreferences (plain), not encrypted (A8 pending).
- Overlay not draggable; the button is fixed bottom-end (A2 refinement
  pending). Volume-key trigger not built (deferred by design).

## Outstanding

1. On-device run of the full loop with speech (RAW → popup → Send).
2. INTENT quality with a real API key — constraint fidelity on the sample
   utterances; the M3 gate from AGENTS.md.
3. Termux round-trip with a real agent prompt (done in the demo: pi agent
   edited the repo from a voice-typed INTENT).
4. Edit/bounce loop and per-app `ACTION_SET_TEXT` on the device.
5. Re-measure: recognizer latency/quality, popup cadence, battery.

The design documents (DESIGN.md A1–A10, AGENTS.md milestones) still describe
the intended end state; this file now tracks the actual first cut against it.