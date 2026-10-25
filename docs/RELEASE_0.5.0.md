# Umbra 0.5.0

Published 2026-10-24 (Asia/Taipei) at the owner's request ("deploy this version first"). Version code **13**.
Built from HEAD `2fec720` plus the uncommitted working tree (the proactive-roadmap work handed over in
[HANDOFF-2026-10-24.md](HANDOFF-2026-10-24.md) and this pass). Nothing was committed or pushed.

- Production: https://umbra-agent.pages.dev/en/
- Immutable Pages deployment: https://3b55146d.umbra-agent.pages.dev
- APK: https://umbra-agent.pages.dev/download/umbra-0.5.0.apk
- Update feed: https://umbra-agent.pages.dev/update.json (version 0.5.0, versionCode 13)
- APK bytes: `855293835`
- APK SHA-256: `ad628e503f991663c05e67aecdea22f31c2c515d66f1c8a226280ce3470a8e28`
- Signing certificate SHA-256: `b38060dee29910fb73d15cb75fe4b557708d85d37cb2c3bdbe6a5ecc3a2231c3` (unchanged)
- Bundled Linux image: 0.4.0 (unchanged, so installed apps are not offered a Linux update)

The open items in [the release legal review](RELEASE_LEGAL_REVIEW.md) still apply; this receipt does not
declare binary-distribution clearance. Physical-device verification is still pending.

## What changes for the user

- **Ink, the new look.** White and black: a near-white page with white cards and black ink and buttons, or true
  black with charcoal cards and white ink in the dark (follows the phone). Colour appears only where it means
  something — failure, attention, done. Porcelain, Liquid and Apple remain under Appearance; a Porcelain look is
  moved to Ink once on update.
- **Motion.** Controls give under the finger and spring back; the Agents/Coding switch and every segmented control
  slide an ink pill; pages push along a shared horizontal axis; list rows animate into place when tasks or agents
  appear, finish or reorder; scrolling pages fade at their edges instead of cutting under the header.
- **Clearer Home.** The Coding tab gets a real empty state with starter prompts (they only fill the composer); the
  menu's two "new" buttons are labelled ("Coding session", "Agent"); settings rows keep their explanations readable;
  a finished task no longer shows its answer twice.
- **Capsule.** Opens by growing out of the pill at the camera and folds back into it; the pill pops in, pulses once
  when something needs you, and its words slide when the step changes. It makes room for other capsule apps such
  as Material Capsule: Settings → Capsule position (Auto, Around the camera, Top corner). Auto moves to the top
  corner when another capsule app is active and fades out of the way while that one is expanded.
- **Screen control.** In `android_run_plan`, the main model can now let Laya choose among the elements actually on
  screen (tap, long press, type, scroll, back, enter) instead of listing every action; Laya types only the plan's
  literal texts and never into password fields. Every action still passes the normal approvals.
- **Telegram.** Your reactions to the bot's messages reach the agent as feedback (it answers only if needed; switch
  in the bot page); albums arrive as one message; videos are saved like files; stickers pass as emoji; files the
  agent publishes for a Telegram request come back as documents after an integrity check.
- **Calendar.** With explicit event-write permission, an agent can change or delete a single event after your
  approval, seeing before/after. Recurring events and events with other guests stay yours to change.

## Security

- Credentials for Linux commands and omp no longer pass through a temporary file in the guest; they travel on the
  command's standard input (review item E).
- Shell commands that inject taps, keys or text (`input`, `cmd input`, `sendevent`, `monkey`, `uiautomator`) now
  need approval like the screen tools and can never be remembered as a permanent prefix (review item A).
  Behaviour change: an agent using `input tap` from a shell is asked first.
- The privileged helpers read their token from the environment instead of the command line (review item G17).
- Laya runs with a private API key and is only treated as running when it answers as Laya (G21, part of G03).
- A session's tool-authority check can no longer be omitted by accident (no silent default).

## Verification performed

- JVM: safety 287, omp runtime 77, control 45, session 58, orchestrator 89, decision 45, app 139 (+ new
  Telegram, Capsule-neighbour, screen-plan and secret-stdin tests) — no failures. `tools` live device tests need a
  debug-signed install and were not run against the release-signed QA emulator.
- Android (QA emulator, PRoot Linux, scripted local model): secret hand-off over stdin (exact values, provider key
  reached the model server, no env files left); startup without pause/resume and cleared draft; result summary,
  approve-once and permanent-prefix confirmation with restart persistence; broadcast-driven Capsule; QR camera
  teardown (10 cycles); see `reports/` for the remaining fixtures run in this pass.
- Ink light/dark, Capsule open/close morph (slow-motion frames), Capsule top-corner placement and the new Settings
  rows were checked by screenshot on the emulator.

## Release verification

- Signed `:app:assembleRelease` and `:app:lintRelease` (0 errors) with one Gradle worker; versionCode 13, 0.5.0,
  same signing identity as 0.4.5.
- The release APK installed over the QA emulator's existing app with data kept and opened non-debuggable in Ink.
- R2 multipart upload (temporary Worker deleted afterwards); site rebuilt; credential-link tests passed; Pages
  deployed; `tools/verify-release.mjs` passed: update feed, download headers, range resume, latest redirect,
  checksum file, full published APK SHA-256 and both website languages.

## Known issue (fixed in source after this build)

- The Agents list previewed an agent's last answer with raw Markdown marks (`## Done…`). Fixed with
  `plainPreview` + `PlainPreviewTest`; ships in the next release.

## Not verified

Physical phone; root/chroot Linux mode for the new secret hand-off; a real Material Capsule installation; real
Telegram reactions/albums/file delivery; real calendar changes; live model quality; Laya with its new key on a
device.
