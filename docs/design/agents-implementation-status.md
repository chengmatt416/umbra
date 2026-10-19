# Agents implementation handoff

Resumed on 2026-10-19 from Claude Desktop session `453b2aad-abf0-450a-bd13-72bec6ffd6d1`
(“Multi-session agent architecture design”), interrupted at 2026-10-19 00:56 UTC.
The approved specification is [agents-2026-10.md](agents-2026-10.md); its Figma references and decisions still apply.

## Starting point

Branch `app-fixes-capsule-recording`, commit `b2dac93`, had a clean working tree. Phases 1–3 were committed:
session kinds and effort, inter-session messages and child sessions, agent profiles/runtime/UI and default-agent
Capsule routing. The Telegram bot implementation was also present. The connectors package held configuration and
method labels, but no message transports. The new-session browser selector was persisted but omp still disabled
its browser tool.

## Added in this continuation

* Notification listener events feed a retained SQLite log. Content is encrypted per record with AES-GCM under the
  existing Android Keystore key; expiry timestamps and record digests are metadata, not encrypted. This does **not**
  implement the specification's whole-database SQLCipher storage. Default retention is seven days (1/7/30-day UI).
  App policies are Use / Context / Ignore. Ignoring an app also deletes its retained records. OTP-bearing
  notifications are ignored by default; explicitly enabled ones redact codes across title, text, shortcut and
  actions before persistence. Financial/authentication packages are ignored by default.
* `notifications_query`, `messages_list`, `messages_read`, `messages_send` host tools. Notification replies use the
  actual RemoteInput action; the chat identity is checked again after approval. SMS reads Android's provider and
  sends through SmsManager; a provider observer delivers incoming SMS to the agent. OTP SMS are excluded.
* Message sends use the existing ApprovalBroker, including its ledger, Capsule and linked-bot approval interfaces.
  Only explicitly permitted, known one-to-one chats can bypass approval. Groups and unknown chat types ask.
  A send is described as submitted/accepted, not delivered. No fallback or automatic retry follows a send.
* Official text transports for BlueBubbles/OpenBubbles (HTTPS server/password) and X (OAuth user token). Credentials
  are checked before saving in SecretStore. Requests have bounded timeouts and response size, no redirect following
  or automatic retries; BlueBubbles URLs containing passwords are not included in network error messages.
  API data is read on demand; realtime API incoming-event subscriptions are still outstanding. X sends always
  require approval because event participant counts do not establish current conversation membership. The X inbox
  request uses the documented fields and sender/participant expansions; parsing has a regression test.
* Settings and agent channel pages reach Message apps and Notifications screens. English and 繁中 copy, policies,
  retained log, retention, forget confirmation, SMS runtime permissions, API setup, per-chat reply controls.
* Background message-app mutations yield to the user's foreground app and for five seconds after leaving it.
  An in-memory package owner prevents active sessions from operating the same app on different displays.
  ActionPipeline rechecks the input fence after observation and after any approval/admission wait. This is a guard
  on existing Android tools; it is **not** yet the resumable visual connector worker specified in §6.3.
* Chromium `browser` pack, localized Linux setup entry, capability detection and omp browser enabled/headless
  settings driven by the session's DOM/Visual/Auto choice. The guest uses X display `:99` for headed browsing;
  the agent is told to start the desktop first. The 0.4.0 release rebuilds the bundled offline package archive.

## Remaining specification work after the 0.4.0 preview

1. Complete phase 4: encrypted message history and API subscriptions/deduplication; TDLib user login with advanced
   api_id/api_hash; professional Instagram and Messenger Graph APIs; opt-in unofficial guest clients and their login
   flows; resumable visual workers, safe draft cleanup, call/quiet-hour checks and automatic resume.
2. Validate the existing Telegram bot against a user-provided bot/account, including voice/media and remote approval
   behavior. Do not use the developer's unrelated local provider credentials.
3. Complete full-session browser and Android desktop/visual testing. Chromium installation, JavaScript execution
   and DOM extraction in the arm64 proot guest have passed. The pinned omp launcher also passes headless and
   headed click, DOM and screenshot checks in an isolated arm64 Linux container.
4. Phases 7–8: Go node rewrite with Kotlin protocol parity, VNC over pinned mTLS, Android RFB viewer and agent tools,
   pairing QR, GUI/TUI wizard, signed installer/checksums and macOS/Linux download artifacts.

0.4.0 (versionCode 7) is an incremental preview of the implemented features above. It does not mark all phases
of the approved specification complete. Release verification and deployment are recorded below when finished.

Keep these constraints: no website re-recording is needed; no global omp/provider configuration; do not disturb
Supabase containers; preserve the installed app's data and signing identity; no account sends during verification
without explicit authorization for those messages.

## Verification record

* `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-parallel --max-workers=1
  -Pumbra.bundleLinux=false`: passed; 42 app unit tests, no failures/skips, lint has no errors. Lint fixes include
  declaring telephony optional for SMS and repairing four locale/resource-observation errors in the existing agent UI.
* `:core:orchestrator:test --tests '*AdmissionTest' --tests '*SessionBusTest'`, with the project-pinned omp:
  five tests passed with no failures or skips.
* `scripts/ondevice-notifications.py`: passed against the actual app on Android 16. Synthetic local notifications
  reach the retained log, verification-code notifications are excluded by default, and the SQLite file contains no
  plaintext fixture title or body. No real account messages were sent.
* The debug APK was signed with the existing release identity and installed over the emulator's existing app with
  `adb install -r -t`; its data was preserved. English and 繁中 Message apps screens and the Notifications screen
  were visually inspected. The per-app locale was restored to `[]` (follow system).
* The browser pack installed into the emulator's existing arm64 proot guest. A headless Chromium run executed
  JavaScript in a local synthetic page and returned the expected DOM. The pinned omp browser launcher passed
  click/DOM/screenshot checks in both headless and headed modes under Xvfb in a network-isolated arm64 container.
  Full-session model-driven browser calls and Android desktop/visual mode remain to be tested.

The initial broad orchestrator run had 43/44 passing, with
`AdmissionTest.requestsBeyondTheSlotsWaitAndAreAdmittedFairly` failing its queue-count assertion. A targeted
rerun passed, but a full run with the project-pinned omp reproduced the failure under build load. The test's
eight-second model delay could expire before all agent processes started. It now holds each model reply until
that session's running state has been observed; queue size, concurrency and fairness assertions remain intact.

* `runtime-build/rootfs/build.sh 0.4.0`: passed archive validation and installation of all six add-on packs
  (Android, browser, dev, GUI, Java, Node) in a container with networking disabled.

## 0.4.0 release verification

* Final `:app:testDebugUnitTest`: 43 tests passed, no failures or skips, including the X parsing regression.
* Full `:core:orchestrator:test` with the project-pinned omp: 44 tests passed, no failures or skips.
* `:tools:test`: five tests passed; 20 live-device tests skipped while the emulator was stopped for the build.
* Debug lint and `:app:assembleRelease :app:lintRelease -Pumbra.linuxVersion=0.4.0`: passed.
* APK manifest is `app.umbra.agent`, version 0.4.0 / code 7. `apksigner verify` passed; its certificate matches
  the local 0.3.2 APK whose SHA-256 matches the previously published update feed. Bundled archive sizes and the
  embedded 0.4.0 Linux manifest match the rebuilt artifacts.
* The signed release installed over the emulator's existing app with `adb install -r` and launched successfully.
  Android reports version 0.4.0 / code 7, with the original first-install timestamp unchanged.
