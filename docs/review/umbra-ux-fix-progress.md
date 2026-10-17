# UX fix progress log (working notes)

Plan: `docs/review/umbra-ux-fix-plan.md` · Report: `docs/review/umbra-ux-report.html` · Screens after: `docs/review/after/*.jpg`
Taste skill installed globally (`~/.claude/skills/`, `npx skills add Leonxlnx/taste-skill -g`); used `redesign-existing-projects` (+ `minimalist-ui` rules) inside Porcelain.

## Done (code, compiles: `./gradlew -Pumbra.bundleLinux=false :app:compileDebugKotlin`)
- Foundation: Geist + Newsreader fonts (`res/font`, licenses `third_party/fonts`), Porcelain type scale, `Semantic` → Porcelain tones, launcher bg recoloured, new `porcelain/Parts.kt` (PConfirm, PEmpty, FadingRow, Disclosure, DisabledReason, PField, SectionHead, PLink, PToggle, PChoice), `shell.PorcelainPage` header (back + emergency shield + lockdown banner), `Segmented(fill=)`, icons folder/trash/chevronRight.
- strings.xml + values-zh-rTW (≈900 keys), `locales_config.xml` + manifest `localeConfig`, Settings › Language link.
- C-01 `AgentSupervisor.deleteWorkspace` + `WorkspaceManager.deleteIfIdle` (+test) + confirm dialog listing linked tasks. **Verified on emulator.**
- C-02 `ConfirmDeleteSession` (Sessions + Current). C-03 history "Hide from list" (`approvalHistoryHiddenBeforeMs`, ledger never deleted, `countUntil`). **Verified.**
- C-04/X-05 `ui/emergency/StopAll.kt` shared confirm; notification "Stop all…" opens app page `stop-all`; receiver uses `stopAll()`. **Verified.**
- C-05 Linux remove confirm; C-06 revoke helpers confirm; C-10 settings note + removed `Session.approvalMode`; license text → MIT.
- C-07/C-08/X-06 `core/safety/ApprovalSurfaces.kt` (+`ApprovalSurfacesTest`): notification ≤MEDIUM, capsule ≤HIGH, Inbox CRITICAL needs PConfirm; one narrowest remember scope. **Verified on emulator (HIGH + CRITICAL scenarios).**
- C-09 Take back phone screen (+ background-display note, pause button; note not exercised: no bg display task).
- S-01 restricted settings: detection via shell `appops get` / install source (`Restriction`), steps auto-open. **Verified.**
- S-02 Models needs-Linux reason + link; S-03 Commit proot card; S-04 countdown on all surfaces + last-minute re-notify. **Verified.**
- S-05 Diagnostics progress + Cancel (SelfTest marks cancelled); S-06 node pairing under "For developers"; S-07 Repository Hub disabled reasons; S-08 lift dialog lists paused + "Resume all". **S-08 verified.**
- X-01 Setup = 3 numbered required steps + "Optional and advanced"; X-02 task panel basic/More, composer Stop→Pause, Now/Later; X-03 Shared Services/Planning/Commit plain + Details; X-04 commit verdict sentence; X-07 terminal take over/ask for handover.
- D-01 vault readable last 3 + full record; D-02 node job one-line + Details + F-02 re-dispatch dialog; D-03/L-03 Current header; D-04 Security state + one action; D-05 short texts; D-06/L-01 Planning picker + Progress tab.
- L-02 snapshot toolbar base; L-04 all 8 Material screens → Porcelain; L-05 Home left-aligned; L-06 Inbox empty centered; L-07 "Independent"; Terminals empty state + no mid-word cut.
- F-01 Emergency "What each control guarantees" (halt controller NOT built — off-limits); F-04 "Not verified on a phone yet" (Shizuku, Laya); F-05 Reference page (spec asset `assets/reference/architecture-spec-v0.1.md` + live design system), Markdown tables (+`MarkdownParserTest`).
- Overlay-mode Capsule hidden while Umbra is in front (it covered the header).
- `scripts/ondevice-scenario.py` gained `critical` scenario (setenforce 1, harmless).
- Tests run: core safety 261, workspace 19, session 56, app MarkdownParserTest 2 — all pass.

## Final state (2026-10-14)
- `./gradlew check` green: 806 tests, 0 failed (36 live skipped); lint 0 errors. Strings: 1665 keys, identical in en and zh-TW.
- Screens: `docs/review/after/en-*.jpg` and `docs/review/after/zh-TW-*.jpg` (24 pages each), plus flow shots (approvals, dialogs).
- Round-2 taste fixes done (CJK tracking, CJK headline join, LineBreak.Heading, core labels via `ui/CoreLabels.kt`).
- Open: L-08 (needs the user's OK). Not built by design: F-01 halt controller. Still English: omp/core error and reason text, terminal keys.

## Remaining (in order, historical)
0. (done 2026-10-14 by subagent) F-03 sweep: 1595 keys in both locales. Still English by design: omp error prefixes, audit reasons, model-bound text, terminal keys; and core enum labels (incident state/level/boundary, CredentialScope, NodeTrust in Security, HealthLevel, service catalog, PressureLevel, Breaker) that would need a UI mapping layer.
1. (superseded) F-03 sweep: ~60 literals left, mostly post-action status messages (`message =`/`onMessage(`), in SecurityEvidence, ScreenContext, Reference (design-system demo labels can stay), SharedServices, Timeline defaults, TerminalPane keys (keep), Nodes, Current (SessionOps error prefixes stay English: they prefix English omp errors). Tools: `scratchpad/i18n.py FILE` and `scratchpad/addstr.py` (see below).
2. (done) `./gradlew check`: 806 tests, 0 failed (36 live skipped); lint 0 errors after moving composable lookups to `LocalResources.current` (104 `LocalContextGetResourceValueCall`) and fixing one format type; 8 unused strings removed. Remaining lint warnings are pre-existing kinds (ModifierParameter order matches old Porcelain API, PluralsCandidate, UseKtx).
2b. (old note) `./gradlew check` (lint + all unit tests) with emulator stopped; fix lint (unused imports, missing translations: every key must exist in both locales).
3a. (done) zh-TW capture of all 24 pages (`docs/review/after/zh-TW-*.jpg`). Round-1 fixes: CJK tracking for Eyebrow/Kicker/drawer groups (`String.hasCjk()`), Headline joins CJK parts without a space, `LineBreak.Heading` on Headline/SerifTitle (no orphan character), core enum labels mapped in `ui/CoreLabels.kt` (subagent). Next: rebuild, install, recapture zh-TW + en, check round 2.
3. (old) Emulator pass in zh-TW: `adb shell cmd locale set-app-locales app.umbra.agent --locales zh-TW`, capture every screen (`scratchpad/shot.sh NAME`), fix truncation; then `--locales en`.
4. taste-skill look-and-fix: top 3 issues from screenshots, max 3 rounds.
5. Mark statuses in `umbra-ux-fix-plan.md`; update memory; final report. L-08 (re-record marketing clips / redeploy site) needs the user's OK.

## Environment notes
- 8 GB Mac: never run Gradle and heavy emulator use together (`./gradlew --stop` before device tests). Emulator SystemUI ANRs under load → `adb shell su 0 pkill -f com.android.systemui`.
- After `scripts/dev-device.sh install`: run `grant`, `helper`, `roothelper`, `scripts/umbractl.py setup`. Grant `POST_NOTIFICATIONS` (`pm grant`). Never `am force-stop` during a scenario.
- Build without bundled Linux: `-Pumbra.bundleLinux=false` (`build/rootfs` is empty).
- Scratchpad helpers (session-local): `addstr.py` (key || en || zh, `@plural key || one || other || zh`), `i18n.py FILE [--ctx]`, `shot.sh`, `ui.py tapx TEXT`, `montage.py`.

## Release 0.2.0 + OTA (2026-10-15)
- OTA built: `app/.../update/{AppUpdater,UpdatePolicy,UpdateReceivers}.kt` (+`UpdatePolicyTest`, 5 pass). Site writes `dist/update.json` (website/build.mjs, guarded: only when src/apk.json describes `umbra-<version>.apk`); `tools/upload-release.mjs` records version/versionCode from Gradle's output-metadata.json. Settings → About: version, status, Check/Download/Install, auto + Wi-Fi-only toggles. Test override: `adb shell setprop debug.umbra.update_url https://…/update.json`. Version override for test builds: `-Pumbra.versionCode= -Pumbra.versionName=`.
- Verified on emulator (real R2 + Pages preview `ota-test`, since deleted with the test objects): 0.1.0→0.2.0 adb upgrade OK (same key); no helper → auto download, "ready" notification → unknown-sources prompt → system "Update this app?" → updated to t1 (vc3), "updated" notification; helper over adb → forced `cmd jobscheduler run -f app.umbra.agent 7301` with app in background → silent install of t2 (vc4) in ~15 s.
- Release APK: `build/umbra-0.2.0-release.apk` (669,116,901 B, vc2, release cert b38060de…). Emulator now has t2 (vc4) installed — uninstall before recordings.
- Next: re-record site clips (marketing/capture → marketing/clips.py → website/public/clips), upload APK (`node tools/upload-release.mjs ../build/umbra-0.2.0-release.apk umbra-0.2.0.apk` needs output-metadata.json next to it: copy `build/umbra-0.2.0-output-metadata.json`), deploy, commit, push.

## Recordings (2026-10-15)
- Old raw recordings and old clips backed up to `marketing/raw/old-2026-10-11/` and `marketing/raw/old-clips/` (git-ignored).
- Fresh debug APK built (`-Pumbra.bundleLinux=false`, includes OTA/Settings About), release vc4 uninstalled, `dev-device.sh setup`, POST_NOTIFICATIONS, locale en.
- Linux: `dev-device.sh rootfs` (dev rootfs, chroot, ~3 min); Android pack from the offline repo: pushed `build/rootfs/umbra-packs-dev-arm64.tar` into `<rootfs>/var/cache/umbra-repo`, `umbractl call guest.install_packs {"ids":["android"]}` (30 s), repo removed.
- Note: the device had no nodes / vault / commit data any more (release test build was a fresh install), so those screens start empty.
- `marketing/capture/lib.py`: `ui_nodes()/find()/tap_text()` (taps by label via uiautomator); `clips.py`: `fps=30` before `trim` (screenrecord is variable-rate: a cut inside a still used to skip to the next change).
- Scene A re-recorded (composer/Send by label; ends by scrolling the chat to the answer + Home key so the last still is kept). Clips say 12.9 s, operate 19.8 s.
- Scene B re-recorded unchanged (scripts/ondevice-capsule.py passes 0 problems); capsule clip now = selection + the approval peek (Previous/Current/Next, Deny), 15.8 s.
- Scene C re-recorded (Inbox/Deny by label; ends on the denied session's answer; todo list dropped: omp re-asks with open todos and the scripted model repeated its answer). approve 8.9 s.
- Scene D re-recorded: final step scrolls the answer into view; scripted agent now closes the app it launched (`android_stop`) before answering, because otherwise the released background display moves Crashy Login onto the phone's own screen (app bug, reported); Home key at the end. background 11.2 s (2nd round: launch → typed → "Welcome, demo"), linux 15.6 s (build+approval → crash notice, logs, fix → final answer). Keyboard (IME of the background display shows on the main screen) and approval heads-ups are cut out.
- Debug-only crash seen once: `DevControlReceiver` BRIDGE_START → `BindException EADDRINUSE` (ToolBridge.kt:71) killed the app process; rerun was fine.
- **Found: `build/rootfs/umbra-rootfs-dev-arm64.tar.xz` (Oct 15 09:42; 175,742,360 B = the `assets/linux/rootfs.tar.xz` inside `build/umbra-0.2.0-release.apk`) has no tmux** (nor libevent-core, libjemalloc2); `-main-` (Oct 10) has it. Session → Terminal → New terminal fails: "tmux refused … exec: tmux: not found". For the recordings tmux was installed into the emulator's guest with `apt-get install tmux` (3.5a). The release APK should be rebuilt with a rootfs that has tmux before upload.
- Scene E: `scene_e_session.py` now says "0.2.0 release". Routes: there is no `setup` page any more (`linux` = Set up).
- Rebuilt the debug APK **with** the bundled Linux (`./gradlew :app:assembleDebug`, 1.5 min, emulator idle; `--stop` after) and `adb install -r` (data kept), helper/roothelper/bridge again: the Set up page now says the add-ons "come with the app", as in the release (the non-bundled build says "this build of the app does not carry them"). Scenes A–D don't show that text, so they were not re-recorded.
- Deleted the leftover sessions of failed takes through the app (session ⋯ → Delete…), so Sessions/Planning lists are clean.
- Scene E rewritten (`scene_e_tour.py SESSION_ID NODE_LOG [1|2]`): fresh activity before the camera (`am start -f 0x10008000`: pages otherwise keep scroll/disclosure state), terminal warmed up; two recordings so no chapter straddles the 170 s screenrecord segment: `raw/scene_e_0.mp4` (plan: Phases DAG → Helpers → Waits → Progress + revision history; resources: Resources → Runtime → Services + health checks; resume: tmux terminal (`tmux ls`) → "Keep going after a restart" → Pause → Resume → Sessions) and `raw/scene_e2_0.mp4` (nodes: **real pairing of umbra-node on the Mac** with its one-time code → Preflight (macOS, 8 CPUs) → trust Owned → Run `uname -a` → job Exit 0; vault + commit; evidence: journals intact → anchor the digest on the Mac → Verify "All 2 anchor(s) … match" → Emergency; access: Set up 3 of 3 + "Runs with root (chroot)" → Settings → Stop and take back).
- clips.json re-tuned for all 13 (cuts around: "No session open" flash, "Connecting to the terminal…", dark activity transition, approval heads-ups, IME of the background display, idle menu while uiautomator dumps). `python3 marketing/clips.py`: say 12.9 s, operate 19.8, background 10.1, linux 15.2, capsule 15.8, approve 8.9, plan 12.4, resources 14.2, resume 16.2, nodes 14.9, vault 11.0, evidence 13.8, access 13.2. Reviewed: dense frame sheets per clip, heads-up/dark-frame scan, contact sheet `marketing/raw/contact-sheet.jpg`.
- `website/public/og.png` regenerated in the same layout (it had two app screenshots of the old UI: Steer/Queue composer): screens replaced by scene D 7.5 s (task page, build output, Pause composer) and scene B 11.0 s (Snapshot selection, Cancel/Clear/Attach). Old copy: `marketing/raw/old-clips/og.png`. Recipe in `marketing/README.md`.
- App problems seen while recording (not fixed: app source untouched): (1) a finished task's background display is released without DESTROY_CONTENT_ON_REMOVAL, so the app that ran there (Crashy Login) moves onto the phone's own screen with the keyboard; (2) typing in a background display pops the IME on the main screen; (3) after Deny in the Inbox the bottom bar highlights Current while the Inbox is still shown; (4) opening a session first flashes "No session open" and then "The conversation appears here once the agent has started / Agent not running" for 1–2 s; (5) Settings → About shows a raw parser error "The update did not work: Unexpected JSON token at offset 0 … <!doctype html>" (update.json not deployed yet, the site answers HTML); (6) session Terminal: with the keyboard open the terminal pane collapses to zero height; (7) Nodes: "Unpair" has no confirmation and does not tell the node (the Mac kept the phone until `umbra-node unpair`), and the old banner stays after changing trust; (8) deleting a session leaves its tmux terminal running; (9) debug-only DevControlReceiver crash on BRIDGE_START (EADDRINUSE).
- Done: processes started for the recordings (scene_e_session.py, umbra-node serve) stopped; emulator left running (debug build with bundled Linux, node "chengmatt@MacBook-Air-M1-3.local" paired, Owned). Not done by design: commit, upload, deploy.

## Release 0.2.0, final (2026-10-17)
- Recordings done by subagent (13 clips + posters + og.png; contact sheet `marketing/raw/contact-sheet.jpg`).
- tmux fix: release rebuilt with the `main` Linux image (`-Pumbra.linuxVersion=main`, `build/rootfs/manifest-main.json` → `manifest.json`; dev manifest kept as `manifest-dev.json`). rootfs sha 60b32a42…, has tmux 3.5a. APK `build/release-0.2.0/app-release.apk` sha da295d11…, 669,175,889 B, vc2, release cert.
- Update check failure now shows plain localized reasons (`upd_err_offline`, `upd_err_no_manifest`) instead of a parser message.
- Smoke test on emulator: fresh 0.1.0 + bundled Linux (proot) → adb -r new 0.2.0 → Set up offers newer Linux → Update (168 MB, ~60 s) → tmux present, workspace + home marker files kept, offer gone → Terminals → New terminal → `tmux -V` = 3.5a.
- Found: fast injected keystrokes in the terminal arrive reordered (`cat /workspace/marker.txt` → `c/at workspace/`). Follow-up.
- Next: upload (running), `node build.mjs`, deploy production, verify update.json + /download, About shows "Up to date", commit clips/marketing/website/apk.json, push. Then the Capsule redesign after notchi (GPL-3.0: ideas only, no code/sprites), ship as 0.2.1 via OTA, re-record the capsule clip.

## Bug fixes from the recordings (2026-10-17, this session)
Status: ☐ open · ☑ fixed + verified on emulator
- ☐ B1 terminal: fast keystrokes reordered (`cat /workspace/marker.txt` → `c/at workspace/`)
- ☐ B2 session terminal shrinks to zero height when the keyboard opens
- ☐ B3 background display released with the launched app still on it → app jumps to the main screen with keyboard
- ☐ B4 agent typing into an app on a background display shows the keyboard on the main screen
- ☐ B5 Inbox: after Deny, bottom bar highlights Current while Inbox shows
- ☐ B6 opening a session flashes "No session open" → "Agent not running" for 1–2 s
- ☐ B7 Nodes: Unpair without confirmation, node not told; stale message after trust change
- ☐ B8 deleting a session leaves its tmux terminal running
- ☐ B9 debug: BRIDGE_START BindException EADDRINUSE crashes the app
Coordination: Capsule subagent paused (code only, no Gradle/emulator) until I resume it.
