# Capsule companion

The Capsule's plain orb becomes a small living companion drawn from Umbra's own mark, one per running session, and
the expanded Capsule gains a live activity feed. Porcelain stays the identity: dark glaze, sage, warm amber and clay
only where they mean something, quiet motion.

Interaction ideas come from notchi (a macOS notch companion for coding agents, GPL-3.0): a mascot that reacts to the
agent's events in real time, one per concurrent session, a click that opens a feed with session time and usage, and
feedback that stays quiet while you are already looking. Only the ideas are used; the drawing, motion, code and
feedback here are Umbra's own (Umbra is MIT).

## The companion

Umbra's mark is an eclipse: a sage disc with a second disc over it (`website/public/favicon.svg`: body r 14 at
(16, 16), cover r 10.5 at (21.5, 12)). On the light page the cover is the page itself, so the mark reads as a
crescent. The companion keeps exactly that: the cover is cut out of the body, so the Capsule's glaze shows through,
and the covered part keeps a faint earthshine (the body at 16 %) so the whole disc is always legible at 18 dp.

Every state moves only the cover (offset and size, in units of the body radius R) and the body's glaze. Each state has
its own shape, so colour is never the only signal.

| State | When | Shape and glaze | Motion (system animations on) |
|---|---|---|---|
| At rest | no session running | the mark: cover at (+0.39, −0.29) R, size 0.75 R; sage | breathes: cover size ±0.03 R and body ±3 %, 5.2 s period; stops after 60 s at rest and settles on the mark (battery) |
| Thinking | running, the model is answering | cover slides along the mark's axis (0.30 → 0.58 R): the crescent waxes and wanes | 2.6 s ease-in-out, continuous |
| Working | running, a tool is executing | cover orbits the body at 0.42 R: the crescent turns | one turn per 1.8 s, linear |
| Compacting | omp is compacting the context | cover almost centred, 0.80 ↔ 0.92 R: a ring that tightens | 1.6 s, continuous |
| Needs you | an approval or a question waits for this session | annulus: cover centred at 0.66 R, warm amber ring | a soft amber halo leaves the ring every 2 s (1.15 → 1.65 R, fading) |
| Paused | paused by the user or the system | the mark, at half strength | none |
| Error | the provider is retrying, or the session failed (10 min, until dismissed) | thick clay crescent: cover small (0.62 R) and pushed up-right | one short damped shake (520 ms, 1.5 dp), then still |
| Done | a session just completed | cover leaves the disc: the full, lighter sage disc | one glow ring (900 ms); the moment lasts 2.6 s, then the session's companion leaves |

Changes between states glide in 420 ms with Porcelain's ease (cubic-bezier .22, 1, .36, 1).

Reduced motion: with the system animator scale at 0, battery saver, or a hot phone (`motionAllowed`), and with the
Liquid theme's motion switched off, nothing moves: every state is drawn in its still pose (the halo as a static faint
ring), and state changes snap. The frame loop only runs while something animates, is capped at about 30 frames a
second, and draws in the draw phase only (no recomposition per frame).

## Several sessions

One companion per session that is running, paused, waiting for you, just finished, or failed recently: at most three,
then "+N". When more than three qualify, the ones that need you come first, then errors, then the done moment, then
working, then paused; the three chosen keep their order of creation so they do not trade places as they update.
With no session at all there is one companion, at rest.

## Collapsed

```
 ┌──────────────────────────────────────┐
 │ ◐ ◑ ◒ +2   Remove the SIM toolkit  ⌄ │  ( ● camera )
 └──────────────────────────────────────┘
```

The companions replace the orb; the label keeps its rule (lockdown, decision, question, error, the current step or
title, at rest) and adds "Done · <title>" during the done moment. The label gives way to the companions when the room
beside the camera is short; the screen reader always hears the label and every companion's state.

## Expanded

```
 [ camera ]
 ◐  Tidy up the phone · working             4:12  ×
 ─────────────────────────────────────────────────
 (lockdown) (approval peek: unchanged, first) (questions) (failure)
 SESSION · PREVIOUS / CURRENT / NEXT
 Current   ◐ Tidy up the phone          4:12 · 34 % context
 Next      ◑ Build the login app        0:48
           ◒ Hold, then decide          0:12
 Previous  Check storage · done
 STEP · PREVIOUS / CURRENT / NEXT
 Previous  ✓ todo           Inspect the phone             0.3 s
           ✓ bash           echo holding; sleep 60       1:00
 Current   ● android_shell  pm uninstall --user 0 com…   0:07
 Next      Remove the SIM toolkit
 [ Ask the agent…                         ] [◎]
 [Open] [Pause] [Stop task] [Take back phone screen]
```

- The header carries the current session's companion and its elapsed time.
- Sessions: the architecture spec's Previous / Current / Next, now with a companion, elapsed time and context use per
  row; every live session has a row (the companions above); tapping another session makes it the current one (it is
  Umbra's focused session, as in the app).
- Steps: the activity feed of the current session. Previous are its last finished steps, Current what runs now (a
  live dot), Next the next plan step. Long content scrolls inside the Capsule instead of growing past the screen.
- Approvals, the composer, Snapshot selection and the controls stay as they were, in the same places (the approval
  peek keeps its position, which the on-device script taps).

## What feeds it (nothing invented)

- Sessions and their states: `SessionStore` (`sessionsFlow`).
- Needs you: `ApprovalBroker.pending` and `QuestionBroker.pending`, by session id. The Capsule may still answer only
  up to HIGH; CRITICAL goes to the Inbox with a confirmation (`ApprovalSurfaces`, unchanged).
- Activity feed and elapsed time: the session's stored timeline (`SessionStore.eventFlow`, last 40 records: tool calls
  and results with arguments already redacted, the agent's and the user's messages, approvals, problems). Elapsed time
  counts from the timeline's last start or restart, else from the session's creation.
- Thinking / working / compacting / retrying and context use: the live conversation omp reports
  (`AgentSupervisor.liveSession(id).conversation`: running tools, `compacting`, `retry`, `contextUsage`). Usage in
  tokens or cost is not shown: omp reports it per message only on request, and a scripted model has none.

## Haptics

Light and consistent, no sound: a single crisp tick (`GESTURE_THRESHOLD_ACTIVATE`) when a new approval or question
arrives, a soft confirmation (`CONFIRM`) when a session completes. Played through the Capsule's own window
(`View.performHapticFeedback`), so the system's touch-feedback setting decides; nothing is played while Umbra itself is
in front (the app already shows it, as notchi mutes itself while the terminal is focused) or when the Capsule is off.

## Strings

Every new word is in `values/strings.xml` and `values-zh-rTW/strings.xml` (prefix `capc_`).
