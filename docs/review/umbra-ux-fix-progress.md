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
