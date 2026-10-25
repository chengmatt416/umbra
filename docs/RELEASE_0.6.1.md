# Umbra 0.6.1

Version code **15** (0.6.0 was 14). Built from HEAD `2fec720` plus the uncommitted working tree. Published 2026-10-25
(Asia/Taipei) at the owner's request ("Publish this batch as 0.6.1 first").

- Production: https://umbra-agent.pages.dev/en/ (deployment https://58b9c91f.umbra-agent.pages.dev)
- APK: https://umbra-agent.pages.dev/download/umbra-0.6.1.apk · `/download/latest` redirects to it
- Update feed: https://umbra-agent.pages.dev/update.json (0.6.1, versionCode 15)
- APK bytes `855750063`, SHA-256 `6e964253bffe118ceeee4c7cd3860aa38ee1b1620ae7ab9815e6aec4df3ce939`
- Signing certificate SHA-256 `b38060dee29910fb73d15cb75fe4b557708d85d37cb2c3bdbe6a5ecc3a2231c3` (unchanged)
- Bundled Linux image 0.4.0 (unchanged, so installed apps are not offered a Linux update)
- `tools/verify-release.mjs`: update feed, download headers, range resume, latest redirect, checksum file, full APK
  SHA-256 and both languages all PASS. The site's FAQ "What is new" entry now describes 0.6.1 (both languages).

## What changes for the user

- **Briefing page per agent**, opened from the agent's row on the Agents overview (and from its chat): today's brief,
  the suggestions waiting for you, its goals and earlier briefs. Briefs and suggestions are no longer mixed into
  settings.
- **Proactive settings moved to settings.** The agent's settings hold its autonomy level, brief times, goal
  authorization, watchers, routines, skills, sources, rules, teams and templates. Settings → **Proactive agents** sets
  the defaults every new agent starts with and lists each agent; setup gains a step for the same choice.
- **Briefs unroll.** Tap a brief, or pull it down, and the day unrolls below its head: the timeline, conflicts,
  decisions, what got done, each section sliding down into place; pull up or tap to roll it back. On Home it unrolls in
  place, with a link to the full Briefing.
- **Answering a suggestion is a plain message.** The sheet shows what was prepared as readable fields (a task, order
  numbers as chips, a recipient) and an empty message box for anything you want to add; a message draft shows just the
  text to send. No configuration text is ever put in the box.
- **Usage counts are on by default for new installations**, shown with their switch in setup and in Settings; earlier
  installations keep the choice they had. What is sent is unchanged (a random installation ID, at most monthly).

## Fixed

- **Runaway sessions:** any chain of sessions starting sessions (subagents, coding sessions an agent starts, team
  handoffs, research, routines) is bounded: at most three levels below a task, four unfinished children per session,
  six unfinished sessions per task and 24 starts per task per hour.
- **Wake loops:** an agent's background runs are capped at 12 per hour, at least a minute apart, and a new background
  run never starts while the previous one still works; what a held-back wake would have handled runs at the next
  allowed wake. Repeating timers fire at most every five minutes; an agent keeps at most 50 timers.
- **Laya install without CUDA:** PyTorch now comes from its CPU-only index, so installing Laya no longer downloads
  ~3.3 GB of GPU libraries a phone cannot use; failed installs retry at most once a day, also across restarts.

## Verification

- JVM: orchestrator 100, app 185 tests pass; debug and release lint 0 errors.
- Release APK: signed with the release key, non-debuggable, versionCode 15; installed over the QA emulator's published
  0.6.0 (versionCode 14) as an in-place update with data kept and launched; setup shows the new five-step flow with
  the proactive step, and Share usage counts stays off for that upgraded install (prefs v2 → the earlier choice).
- Emulator (debug build): Home brief unroll (slow motion), Briefing page, proactive settings, Settings → Proactive
  agents, the setup step, the suggestion sheet for a message draft and for a task with order numbers.
- Not verified: a physical phone; the in-app OTA path from 0.6.0 (the APK was installed with adb); usage counts on for a
  fresh install on a device (covered by `UsageCountsDefaultTest`); a real agent hitting the spawn or wake limits; the CPU-only Laya install end to end on
  a clean guest (the CPU wheel's availability for the guest was checked).

## How it was published

1. `./gradlew -Pumbra.linuxVersion=0.4.0 -Pumbra.requireReleaseSigning=true :app:assembleRelease :app:lintRelease` with
   the release key and JDK 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/...`; the system default JDK 11 fails).
2. `node website/tools/upload-release.mjs ../app/build/outputs/apk/release/app-release.apk umbra-0.6.1.apk`, `APK` in
   `website/src/content.mjs` and `LATEST_APK` in `website/wrangler.toml` bumped, `node build.mjs`, Pages deploy to
   `main`, `node tools/verify-release.mjs`.

The open items in [the release legal review](RELEASE_LEGAL_REVIEW.md) still apply.
