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
of the approved specification complete. Release verification and deployment are recorded below.

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

## Deployment — 2026-10-19

* Source commit: `1f30a25` on `app-fixes-capsule-recording`.
* Production: https://umbra-agent.pages.dev (Cloudflare Pages deployment https://dc5609c1.umbra-agent.pages.dev).
* APK: https://umbra-agent.pages.dev/download/umbra-0.4.0.apk — 851,813,993 bytes (812 MiB), versionCode 7.
* SHA-256: `1ec0306ad06c9311231df18b7c9830f3a9ef92f1ddc1d29fbdbbfdf9c1985e55`.
* Multipart R2 upload completed and its temporary Worker was deleted. Existing release objects were retained.
* `node tools/verify-release.mjs`: passed against production. The update feed matches the artifact, HEAD and Range
  responses have the expected sizes, `/download/latest` redirects to 0.4.0, and the checksum file matches. A complete
  download of the published APK matched the local SHA-256. Both website languages link to the new APK.
* Existing demo clips were kept unchanged. The development emulator is running with the signed 0.4.0 release.

The remaining specification work above is still pending; this deployment does not include the Go/VNC rewrite,
desktop installers or the unfinished connector methods. Account-level message delivery has not been verified.


## 0.4.1 session files and assistant sessions

* The updated Figma FAB uses two 56 dp icon-only circles, original exported icons, a 10 dp horizontal gap,
  and 12 dp above the close FAB; left-handed mode mirrors the actions. Checked on the Android emulator.
* The paperclip accepts files and folders through Android's document picker. Device files receive managed
  shortcuts to originals; device folders become the working directory. Cloud documents are explicitly imported
  copies. Android file access is requested for direct paths. Mount changes require an idle/paused session.
* Git protection starts enabled. Private repositories snapshot selected content, including ignored and uncommitted
  files, before work and after turns. Existing source HEAD/index are preserved. New source repositories use
  rename-based object creation for Android shared storage. Trust is scoped to selected folders, not global config.
  Recovery creates a new managed workspace. Disconnecting removes only managed links/binds; history stays usable
  after app restart and after source access is revoked. Delegated coding work inherits connected file protection.
* Changing folders restarts the process in the selected working directory. The app atomically updates only the
  app-owned transcript's cwd header while its writer is stopped, preserving the rest of the conversation.
* Agent sessions use their own assistant system prompt and assistant tool list, with memory, scheduling, messages,
  Android actions and delegation. Coding runs in separate sessions. Delegation and report events carry stable
  session IDs and open the corresponding session. Agent session links open agent chat, not the coding screen.
* Laya selects recipients among agents subscribed to an incoming source, including all relevant agents. Explicit
  rules override relevance filtering. Unavailable or low-confidence decisions preserve subscriptions. Notifications
  without explicit rules wait for the heartbeat; messages route immediately. Decisions are cached across agents.
* Broad activity is shown in agent chat/list, including delegated work while the assistant is idle. Assistants can
  update it with agent_status. Idle processes yield promptly at handoffs, and finished child processes are released
  before reporting. The native thinking orb ports the upstream working preset at commit de85557; its MIT license is
  included in APK assets. Background/reduced-motion states stop its clock.

Verification for this change:

* 58 core session tests, 23 workspace tests, 48 orchestrator tests and 54 app tests passed. The orchestrator drives
  the real pinned omp against a scripted model; no provider credentials or real account sends are used.
* Rooted-device mount test passed: selected file/dir binds, external atomic replacement, and disconnect preserving
  originals. Native root mounts reject changed/symlinked paths.
* Android document-picker tests passed for attachments and folder grants in the arm64 proot environment. Verified
  live shortcut edits, fresh source Git initialization/commit, checkpoints, separate recovery copies, attachment
  after folder mount, disconnect preserving originals, and recovery after restart with source access revoked.
* scripts/ondevice-agent-session.py passed: assistant prompt/tools, broad activity/native orb, separate coding
  session, tapping its event, returning report, and clearing finished status. Local provider settings were restored.
* The Downloads document picker also passed with an opaque MediaStore document ID: the app resolved the native
  original, requested Android file access, created its shortcut and saved the first Git checkpoint. Original file
  access settings were restored after the test.
* The orb geometry test compares the Kotlin renderer with upstream reference coordinates; link parsing and Laya
  individual/broadcast/failure/uncertainty/cancellation paths have regression tests.


0.4.1 artifact verification:

* Source: 0c195aa (following fd2da4c), versionName 0.4.1 / versionCode 8.
* :app:assembleRelease -Pumbra.linuxVersion=0.4.0 and :app:lintRelease passed. The 0.4.0 Linux image is intentionally
  reused; rootfs and offline add-on archive sizes and SHA-256 values match the APK's bundle metadata.
* APK package and original signing certificate verified with apksigner; thinking-orbs MIT attribution is bundled.
  APK size: 852,013,388 bytes. SHA-256: 969a2b3f0864fc064eb43d622dbd9fecd9dbd56fedc3019ab48f94d666c6c7dc.
* The signed release installed over the existing Android emulator app. Version 0.4.1 / code 8 is installed,
  the original first-install timestamp is unchanged, and Home launched successfully.


## 0.4.1 deployment — 2026-10-19

* Production: https://umbra-agent.pages.dev; Pages deployment https://da04ebf8.umbra-agent.pages.dev.
* APK: https://umbra-agent.pages.dev/download/umbra-0.4.1.apk — 852,013,388 bytes, versionCode 8.
* Source commits fd2da4c and 0c195aa; verified artifact/site metadata committed as eab0ead.
* Multipart R2 upload completed; its temporary Worker was deleted and older release objects were retained.
* node tools/verify-release.mjs passed against production: update feed fields, HEAD/Range responses, latest redirect,
  checksum file, a complete APK download matching SHA-256 969a2b3f0864fc064eb43d622dbd9fecd9dbd56fedc3019ab48f94d666c6c7dc,
  and both website languages' download links/version.
* Both right- and left-handed FAB layouts were checked on the signed release. Original handedness/file-access
  settings were restored, existing demo videos were unchanged, and the emulator remains on the signed 0.4.1 release.

## 0.4.2 notification behavior — 2026-10-20

* Agent chat hides wake digests, including existing history, without removing them from the agent's context or
  inserting empty date separators. User messages, replies and linked coding reports remain visible.
* Completing an agent turn no longer posts the generic “Task finished” notification. Ordinary coding/subagent
  completion alerts and non-Telegram failure alerts retain their existing behavior.
* Replies, explicit `notify_user` messages, coding results and approval alerts follow the last direct user request's
  channel. Telegram requests stay on Telegram across wake digests and delegated reports; a new in-app request
  switches back to phone notifications. Both reply observers derive the channel from the saved conversation,
  rather than consuming an in-memory Telegram marker after the first answer.
* App unit tests: 59 passed, zero failures/errors/skips. Regression coverage includes wake-only histories, date
  separators, retained replies and session links, result transitions for all session kinds, and channel selection
  across reports, wakes and subsequent in-app requests. No live Telegram account messages were sent.

### 0.4.2 release verification

* Source: `346e564`. `:app:testDebugUnitTest :app:assembleRelease :app:lintRelease
  --no-parallel --max-workers=1 -Pumbra.linuxVersion=0.4.0`: passed.
* APK: version 0.4.2 / code 9, 852,017,188 bytes. SHA-256:
  `e91beafe830c621986bfb31f5914676ab0643262524eddce350ac4b7077cf94c`.
* APK signature matches the existing release identity; both bundled Linux archives match their manifest hashes.
* The final APK installed over the existing Android 16 app with `adb install -r`. Its on-device SHA-256 matches
  the verified release, the first-install timestamp is unchanged, and Home launched successfully.
* Telegram failure replies now use the current failure text, rather than the previous successful response.

### 0.4.2 production deployment

* Release metadata commit: `407f7f2`. Production: https://umbra-agent.pages.dev, deployment
  https://50edd52c.umbra-agent.pages.dev.
* APK: https://umbra-agent.pages.dev/download/umbra-0.4.2.apk. The production download's complete SHA-256
  matches `e91beafe830c621986bfb31f5914676ab0643262524eddce350ac4b7077cf94c` (852,017,188 bytes).
* `node tools/verify-release.mjs`: passed update feed, download headers, byte-range resume, latest redirect,
  checksum file, full APK hash, and both website languages. The temporary uploader Worker was deleted.
