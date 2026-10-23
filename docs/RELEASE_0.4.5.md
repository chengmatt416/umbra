# Umbra 0.4.5 release verification

Published 2026-10-22 (Asia/Taipei). Version code **12**. Built from workspace HEAD
`c18a54fcc3be527cea0422f19fe1dc7ecf279b56` plus the uncommitted implementation. The existing HEAD and
index are retained, preserving the concurrent legal, remote-node and orb changes.

- Production: https://umbra-agent.pages.dev/en/
- Immutable Pages deployment: https://19b34bb5.umbra-agent.pages.dev
- APK: https://umbra-agent.pages.dev/download/umbra-0.4.5.apk
- Update feed: https://umbra-agent.pages.dev/update.json
- APK bytes: `852986046`
- APK SHA-256: `510a6ec2a462e248ef5ac0a6d51cace3ae498cf6325b4f3f33c848ed32f24d0b`

## Changes

- Command reviews show the complete command, working directory, stdin and environment variable names.
  Users can approve once or confirm a persistent literal prefix. Persistent grants bind the working
  directory, survive restarts and can be revoked in Settings. Compound shell syntax, interpreter input,
  root actions and critical risks do not receive a persistent grant. Capsule and notification commands
  open the full review; Telegram uses a second confirmation before saving a prefix.
- Sign-out updates only the selected provider's credentials in a verified SQLite transaction. The app
  removes the account only after successful command completion and the verification marker. Failure,
  timeout, truncated output or cancellation retains the local account state.
- Finished coding sessions display a saved result summary. Agents discover session titles and summaries
  first, then explicitly request full saved current-branch transcripts in bounded pages. Large tool
  results remain available across pages, secrets are redacted and image payloads remain in the app.
  Reading history does not start an idle agent or make a model request.
- Task and agent usage limits support API-equivalent USD, five-hour quota percentage and weekly quota
  percentage. Reset schedules are none, ten minutes, hourly or every 24 hours from configuration. Agent
  limits cover its coding sessions, and child tasks also charge their parent task's limit. Accounting
  survives restarts and does not double-charge replayed reports. Missing pricing or fresh quota pauses
  a limited task. Limits are checked before each managed provider request, including tool follow-ups.

## Verification performed

- The full regression run passed: 273 safety, 58 session, 65 orchestrator, 77 omp runtime and 67 app
  tests, with no failures or skips. The pinned native omp binary ran against local scripted model
  servers, including cost/quota admission and summary-first history queries.
- The final history-page tests passed all three cases: complete large tool-result and emoji paging,
  image references without binary payloads, and structured credential/secret-typing redaction.
- `scripts/test-signout-command.py` passed three real Bun/SQLite scenarios: selected-provider revocation
  and retry, missing database, and missing schema. No real account was used.
- `scripts/ondevice-task-controls.py` passed on the disposable Android emulator: summary display,
  usage-limit saving, approve once without a saved rule, two-step permanent prefix approval, persistence
  across process restart, exact-prefix reuse and compound-command refusal. Screenshots are in
  `reports/task-controls/`. Initial attempts were interrupted by an emulator System UI ANR and test
  selector/timing errors; the corrected harness passed.
- The real Android agent-session test passed against a local scripted provider: dedicated agent tools,
  live broad activity and orb, a distinct coding session opened from its delegation event, returning
  result and activity cleanup. Temporary providers, sessions and reverse ports were restored/removed.
- The release APK reports version 0.4.5/code 12 and matches the existing Android signing identity
  `b38060dee29910fb73d15cb75fe4b557708d85d37cb2c3bdbe6a5ecc3a2231c3`. Both embedded Linux archives were
  hashed in full and matched the bundled 0.4.0 manifest/checksums.
- The full production-signed APK installed over the existing app and opened its non-debuggable Android
  UI with version 0.4.5/code 12. The dedicated test emulator was stopped after verification.
- Signed `:app:assembleRelease`, `:app:lintDebug` and `:app:lintRelease` passed with one Gradle worker
  and parallel tasks disabled to fit the development Mac's memory. Lint retains warnings/hints and
  reports no errors. Website generation, local feed/page metadata checks and download-route tests passed.
- Production public verification passed: complete APK SHA-256/byte count, HEAD/Range, checksum file,
  latest redirect, update feed and both website languages. The feed and APK Range also accepted the
  current app's HTTP user-agent string. All eight unchanged remote-node artifacts and the checksum
  index passed public SHA-256/minisign verification; installer and both remote-node pages matched.
  The temporary APK upload Worker was successfully deleted.

## Limits

Usage accounting stops subsequent managed requests; requests already in flight can exceed a cap.
Provider quota changes can include other activity on the same account. External programs' independent
model billing is outside the managed provider-request check. Live provider-account behavior and live
Telegram delivery require separate account-based verification; development tests use local fixtures.

The Cloudflare security-audit-skill scan is complete in
`/Users/chengmatt/security-audit-skill/umbra/run-1/REPORT.md`. It covers immutable source snapshot
`e9a64757b9f5c1333d410c247022c8c6ad61ab2a`, which predates this release's changes. Its 26 leads remain
`needs_validation`; none are confirmed. The audit was source-only and does not clear this release.

Remote-node companion artifacts remain at 0.4.4. macOS packages remain ad-hoc signed and not notarized.
The existing [legal review requirements](RELEASE_LEGAL_REVIEW.md) remain applicable; this receipt does
not declare binary-distribution legal clearance.
