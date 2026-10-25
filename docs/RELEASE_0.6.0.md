# Umbra 0.6.0

Version code **14** (0.5.0 was 13). Built from HEAD `2fec720` plus the uncommitted working tree. Published 2026-10-25
(Asia/Taipei) at the owner's request: the website first ("Push the website to prod first"), then the APK and update feed.

- Production: https://umbra-agent.pages.dev/en/ (deployment https://14bb0032.umbra-agent.pages.dev)
- APK: https://umbra-agent.pages.dev/download/umbra-0.6.0.apk · `/download/latest` redirects to it
- Update feed: https://umbra-agent.pages.dev/update.json (0.6.0, versionCode 14)
- APK bytes `855612071`, SHA-256 `a5d0baf8da9b9277181f2e5987d55be9bc9652136bd5feaa4d818d39d9f65219`
- Signing certificate SHA-256 `b38060dee29910fb73d15cb75fe4b557708d85d37cb2c3bdbe6a5ecc3a2231c3` (unchanged)
- Bundled Linux image 0.4.0 (unchanged, so installed apps are not offered a Linux update)
- `tools/verify-release.mjs`: update feed, download headers, range resume, latest redirect, checksum file, full APK
  SHA-256 and both languages all PASS. The site's preview alias `site-0-6-preview` still exists.

## What changes for the user

- **New app icon:** Umbra's crescent moon (the same mark as the website and the Capsule), white on Ink black, with a
  themed-icon layer for Android's tinted icons; the notification glyph is the same moon.
- **Briefs and suggestions as designed cards.** A brief has a dark head (morning or evening, the date, one sentence
  about the day, counts for events, conflicts, decisions and work done) and a body: the day as a timeline, conflicts
  in an amber callout, what needs a decision, what got done while you were away. A suggestion card shows its kind,
  when it was found, what was found, the prepared next step in its own shape (a message draft as the bubble it would
  be, an instruction, a goal), the evidence and the expiry, with one primary action.
- **Home's Agents tab** opens on today's brief and the two suggestions that matter most, each adoptable or dismissable
  in place; every agent row shows the goal it is working on with its progress.
- **Proactivity page:** the autonomy level is explained in words as you change it (L0 Only when asked … L4 Works
  toward goals); sections are pills with live counts that keep the selection in view and slide between sections;
  goals show a progress bar and a milestone checklist; watchers read "Every 60 min · when < 25,000", routines
  "Weekdays at 08:30" instead of internal data.
- **Brief headlines** are sentences ("A clear morning: nothing needs you yet.", "1 conflict to settle, 3 events
  today.") in English or Traditional Chinese, never a row of zero counts.
- **Message drafts** in a suggestion are reviewed as the recipient line plus the message text, editable; payloads with
  other fields still show the complete JSON.
- **Chat:** your message appears the moment you send it, with a clock until the agent's conversation records it.
- **Capsule:** new placement **Top centre** (a plain pill in the middle of the status bar, for phones whose camera is
  off-centre; it moves to the corner beside another capsule app). When you ask your agent from the Capsule, its answer
  opens the card for a moment, like a finished task.
- **Tools agents write themselves:** besides pure JSON transforms, an agent can draft a small **Python, Bun or Bash**
  program (JSON in on stdin, JSON out on stdout). It declares whether it needs the network and which domains; static
  checks refuse processes, dynamic code, file writes, privilege, device and Umbra paths and computed URLs; local Laya
  reviews the complete source; tests run in the Linux environment; you enable that exact version in Skills. A
  network tool passes DLP and the approval gate on every run. Read-only background research cannot run scripts.
- **Connector first:** when an agent has a structured connector for a service (WhatsApp, Telegram, Gmail, Google
  Calendar, …), its browser and screen tools are pointed back to the connector the first time they try that service's
  web or Android app; repeating the call is the agent's deliberate fallback and is logged.
- **Teach a task** also works on a paired computer (operate it in the embedded viewer: clicks, drags and special keys
  are recorded; typed text only as a placeholder; no screenshots kept) and on the Linux desktop.

## Fixed

- **Laya install loop:** when installing Laya failed (for example on a full disk), the maintenance loop retried the
  whole install — about 1 GB of torch/CUDA wheels — every five minutes at most, and each interrupted attempt left its
  `pip-unpack-*` folders in the Linux environment's `/tmp` (found on the QA emulator: ~1.4 GB, then ~0.8 GB again
  overnight). pip now unpacks into a private folder that is always removed, leftovers of earlier attempts are cleared,
  and a failed install retries by itself at most once a day (Download in Settings retries at once).

## Verification

- JVM: see `docs/review/finalization-2026-10-24.md` § 0.6.0 for the final counts of this pass.
- Android (umbra36 emulator, PRoot Linux, scripted local model): synthesized python/bun/bash scripts executed in the
  guest; brief/suggestion/goal/watcher/routine UI, the Home deck, the message-draft review, pending bubbles, the
  centred Capsule and its agent conversation were exercised while recording the website clips.
- Release APK: signed, release lint 0 errors, non-debuggable; installed over the QA emulator's 0.5.0 (versionCode 13)
  as an in-place update with data kept, launched, and the new icon checked in the launcher.
- Not verified: a physical phone, the in-app OTA path from 0.5.0 (the APK was installed with adb), a real model writing and testing a tool, real connector accounts for routing,
  a real paired-computer teaching session end to end (the viewer hooks are unit-tested via the recorder's draft).

## How it was published

1. Signed release: `./gradlew -Pumbra.linuxVersion=0.4.0 -Pumbra.requireReleaseSigning=true :app:assembleRelease`
   with the release key (`website/README.md`).
2. `node website/tools/upload-release.mjs app/build/outputs/apk/release/app-release.apk umbra-0.6.0.apk`, bump
   `APK` in `website/src/content.mjs` and `LATEST_APK` in `website/wrangler.toml`, `node build.mjs`, deploy Pages,
   `node tools/verify-release.mjs`.

The open items in [the release legal review](RELEASE_LEGAL_REVIEW.md) still apply.
