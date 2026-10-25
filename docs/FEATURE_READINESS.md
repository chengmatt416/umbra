# Requested feature readiness

Reviewed 2026-10-24 against the working tree. The owner reconfirmed that local vision and both live
conversation modes remain deferred. Multilingual dictation is included in the current work.
This is an implementation map, not a claim that a model can prevent every prompt injection or that
fixture tests establish production model quality.

| Request | Current implementation | Scope and limits |
| --- | --- | --- |
| Local vision decision model (note §1) | Deferred by the owner. | Existing screen planning and configured backup vision do not implement a new resident local vision model. |
| Laya-only prompt-injection prevention | `LayaActionSecurityCheck` batches an explicit injection decision with action risk and category. Confident suspected injection becomes CRITICAL and requires fresh confirmation in every approval mode, even with a saved command prefix. | Only Laya performs this model check. One bounded local inference reviews the action, screen context and samples of the latest coding tool results. It does not scan whole files, images or transcripts. Missing, uncertain or unavailable Laya retains the deterministic action safeguards; it is not a clean-content verdict. |
| Real multimodal live mode | Deferred by the owner. | No live audio/video model connection is claimed. |
| Low-cost local transcription + TTS live mode | Deferred by the owner. | Existing dictation is not a live duplex conversation. Persistent steer/queue/Laya interruption preferences remain part of this deferred feature. |
| Multilingual dictation and Telegram transcription | A shared recognition request enables Android language detection and balanced language switching without a locale or language allowlist. The app display language does not constrain recognition. Telegram uses the explicit on-device recognizer; audio decoding is bounded and releases codecs, pipes and temporary files on failure/cancellation. | Languages depend on the installed recognizer and its downloaded language packs; Android may ignore detection flags. This does not establish support for every human language. Telegram has no network-recognizer fallback and accepts at most two minutes of decoded audio. Acoustic accuracy and mixed-language behavior still need real-device testing. |
| Install and monthly-active counts (updated note §11) | Opt-in Settings switch, default off. Main-app foreground use sends a random install UUID at most once per successful UTC month; durable retry backoff and a mutex prevent repeated opens from flooding the endpoint. The existing Cloudflare site stores install and month-specific SHA-256 membership keys in private R2. | MAU measures opted-in active installations. Background wakes and task content are excluded. Production endpoint and private storage were tested with a synthetic ID; both synthetic records were removed. Existing published site assets and APK feed were preserved. |
| Suggestion confirmation input (updated note §17) | Simple agent/coding suggestions edit the instruction as text using composition/selection-aware field state. JSON serialization preserves quotes, Unicode, newlines and the other reviewed fields. Blank/oversized instructions cannot be confirmed; the dialog remains usable above the keyboard. | Structured goal/skill/message actions retain their complete configuration review. JVM checks cover Unicode and payload preservation; the Android fixture verifies keyboard-open confirmation and the exact saved instruction without provider calls. |
| Suggestion and brief entries (updated note §18) | Direct buttons on every Home agent row and in agent chat open the correct proactive tab. Briefs expose morning/evening enablement and time selection; empty states explain where results will appear. Home reserves its measured composer height so the final rows remain reachable. | Android checks cover both Home and chat entry paths, including a long agent list. Real calendar/account delivery remains tracked in the proactive roadmap. |
| Active subagents on the parent page (updated note §19) | Parent chat lists active owned tasks and nested descendants, including coding tasks, subagents and specialist handoffs with different owner IDs. Each row opens the exact session and shows its real state. | Graph/cycle/ownership tests and Android direct/nested task navigation pass. Completed tasks remain in history rather than the active list. |
| Broadcast-driven Capsule (notes §§7–8) | App-owned changes emit a package-scoped, non-exported invalidation broadcast. The overlay starts and reads content after invalidation; burst coalescing has no idle polling timer. | Android Capsule fixtures cover suggestion visibility, routing and scheduled expiry. A third-party Material Capsule installation has not been exercised. |
| Screen control procedure (note §9) | Bounded ordered screen plans let Laya select permitted actions from the fresh accessibility tree, verify each subtask and escalate visual-only screens to the main/configured backup vision model. Existing action checks and takeover fences apply inside the loop. | Scripted checks establish action bounds, freshness and failure handling; they do not establish real Laya accuracy on arbitrary apps. |
| Proactive specification (note §10) | Goals, discovery, routines, briefs, scoped context, teams, skills, teaching, deliverables and approval paths have implementation and test coverage. | The full specification is still incomplete. See [the capability-by-capability remaining work](PROACTIVE_IMPLEMENTATION.md), including executable synthesized tools, OAuth lifecycle, broader document editing, remote/DOM teaching and complete browser/API verification. |
| Composer clearing and coding startup (notes §§12, 14) | Sending clears the accepted draft; task startup belongs to the supervisor and survives composer/navigation cancellation without manual pause/resume. | JVM and Android startup fixtures pass. This does not establish live-provider latency or reliability. |
| Remote-node pairing crash (note §13) | QR decoding and camera teardown are serialized; rotated luminance, padded strides and offset buffers are handled. Manual pairing and authenticated screen takeover remain available. | Decoder/lifecycle tests and a synthetic paired-node fixture pass. A positive physical-camera scan remains unverified. |
| Security audit (note §16) | The supplied audit is mapped to code fixes and residual limitations in [the finalization review](review/finalization-2026-10-24.md). | The owner skipped a new security scan. This is not a declaration that every audit item or security property is resolved. |
| Telegram images, files and reactions (note §20) | Private linked-user messages accept photos, documents, supported media and voice; albums coalesce, incoming reactions become bounded feedback, and the bot acknowledges delivery. Published files return through receipt/hash checks. | Focused tests cover routing, payload limits and receipt validation. Real Telegram-account delivery remains unverified. |
| Rejected agent creation and cold-start links | Conversation validation precedes profile publication; initialization failures roll back new records. Restore errors stop the affected profile without crashing the service. Deep links wait through onboarding and for a ready navigation graph; Stop All remains immediate. | Android verifies rejected creation leaves no profile/session, a blocked legacy profile survives restart safely, and an onboarding deep link does not crash. |
| Claude Code, Codex and omp access from Coding | **Computer coding sessions** in the new-coding sheet and session menu opens paired computers. Existing authenticated screen viewing and explicit user takeover can operate their running terminals. Umbra's own omp sessions already use native chat/RPC. | Computer screen access must be enabled. External transcripts, billing and approval prompts stay in their original client. Native external-session discovery/import is not implemented; investigation below identifies the integration work. |
| Command approval tightening | Complete command/cwd/stdin review, approve once, and eligible literal prefix forever with a second confirmation. Prefix grants survive restart and can be revoked. | New command approvals cannot grant an entire risk category. Compound syntax, interpreter input, root and critical actions are one-time only. Prefixes are restricted to their working-directory context. |
| Sign-out fails closed | `signOutVerified` requires successful, untruncated command completion and the exact verification marker before removing local account settings. The command revokes only the selected provider in a checked SQLite transaction. | Failures, timeout, missing database/schema and cancellation never produce local sign-out success. Live account revocation has not been exercised in this review. |
| Task result summaries and agent history | Completed coding turns save and display a bounded, redacted final-answer summary. `sessions_list`/`session_read` expose title and summary first; full current-branch saved messages require explicit paged history requests. | Summaries reuse the final answer without an extra model call. Images are referenced, not embedded in history pages. Older sessions without a cached transcript fall back to saved timeline events. |
| Per-task/per-agent usage limits | API-equivalent USD, five-hour quota %, weekly quota %; no reset, 10-minute, hourly or 24-hour schedules. Persisted meters cover task follow-ups, children and shared agent sessions. New-task/new-agent screens allow configuration before work starts. | Admission runs before each managed provider request. Unknown prices or fresh quota block limited requests. Already-running requests may exceed a cap, quota readings may include other account activity, and external clients/programs have independent billing. This is not a provider-enforced hard spending cap. |
| Keep Laya resident | Opt-in installation/preload followed by residency between tasks. Healthy processes are reused; 30-second maintenance checks recover exited processes, with failed-start backoff up to five minutes. Stop persists the disabled preference. | The app service and Linux environment must remain available. Android may reclaim processes. Actual energy and phone CPU latency require hardware measurement; no numeric efficiency gain is claimed. |

## Phone-session investigation

The existing node protocol has authenticated jobs and an authenticated screen stream, but no session
enumeration, structured chat transport or external-client approval adapter. Reusing screen access is
the currently implemented route from Coding: select a paired computer, open its screen, then choose
takeover to type into the desired terminal. Viewing alone sends no input. This route works independently
of which coding CLI is shown and does not duplicate or rewrite that CLI's conversation files.

For native Umbra chat integration:

- **Codex:** its documented app-server lifecycle supports initialization, thread start/resume, turns,
  steering and completion events. A node-side adapter can translate that protocol into Umbra chat;
  resuming persisted history is not evidence of safe concurrent attachment to an arbitrary running CLI.
  Session ownership, approval forwarding and reconnect behavior need implementation and tests.
  [Official Codex app-server lifecycle](https://learn.chatgpt.com/docs/app-server#lifecycle-overview).
- **Claude Code:** official Remote Control can expose an existing terminal session using
  `/remote-control` through Claude's web/mobile client. The reviewed documentation does not establish
  a public third-party attachment API for Umbra. Use its official client or Umbra's paired desktop
  route; do not reverse-engineer private account endpoints as the implementation plan.
  [Official Claude Code Remote Control](https://code.claude.com/docs/en/remote-control).
- **omp:** the pinned runtime already has `rpc-ui`, saved-session reopening and interactive `--resume`
  support in `runtime/omp`. Extending that to a computer needs a node-side process owner and an
  authenticated RPC stream. The existing one-writer-per-conversation rule must extend to that owner;
  opening the same conversation concurrently in a separate process is not an attachment protocol.

Before describing external sessions as native Coding chats, add per-computer session authorization,
bounded event replay with reconnect cursors, single-writer ownership, one-time/prefix approval mapping,
and explicit usage-meter capability reporting. Test revocation, disconnect during approval, reconnect,
duplicate event delivery, history paging and cancellation against pinned real client versions.

## Relevant checks

- Safety: `LayaActionSecurityCheckTest`, `SafetyGateTest`, `CommandPrefixesTest`, approval-surface tests.
- Lifecycle: `LayaSidecarTest` exercises warm reuse, opt-out, delayed Linux availability, process exit,
  backoff, concurrent maintenance and cancellation using a controlled local runtime.
- Real omp integration: `AgentSessionTest` tests usage admission, summary-first history and Laya's
  review of recent tool output before a subsequent coding write.
- Sign-out: `VerifiedSignOutTest` and `python3 scripts/test-signout-command.py` (real Bun/SQLite with
  disposable credentials).
- Android fixture checks: `scripts/ondevice-task-controls.py`, `scripts/ondevice-laya-security.py`.
  These test app wiring and UI behavior, not live Laya detection accuracy or real provider billing.
- Startup lifetime: `AgentSessionTest` cancels a waiting composer during both new-task submission and
  sending to a prepared session. `scripts/ondevice-startup-navigation.py` sends from Home, opens the
  new task before its first model request, and requires completion without pause/resume.
- QR camera teardown: `scripts/ondevice-node-qr-lifecycle.py` repeatedly removes and reopens the live
  scanner while frames are decoding, requiring the same app process and usable manual pairing afterward. `NodeQrDecoderTest` uses a deterministic dense invitation that fails at one orientation without real luminance rotation; it also checks padded pixel/row strides and an offset buffer. A positive physical-camera scan remains unverified.

Run the standard checks with `source scripts/env.sh`, the pinned `OMP_BIN`, and Gradle tasks
`:core:safety:test :core:orchestrator:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
Use `-Pumbra.bundleLinux=false` for a development APK that relies on an already installed guest.

The full proactive roadmap and its remaining release gaps are tracked in [PROACTIVE_IMPLEMENTATION.md](PROACTIVE_IMPLEMENTATION.md). Native credential handoff now has an Android fixture for unchanged/changed forms, encrypted persistence and deleted-source/restart recovery; browser and remote credential entry still use direct user takeover.

Current follow-up checks: app JVM tests **168 passed**, orchestrator tests **92 passed**, app lint and development APK assembly passed.
`scripts/ondevice-document-context.py` and `scripts/ondevice-note-ui.py` passed on the disposable QA
emulator with no provider calls. Telemetry's four Node tests, local Cloudflare R2 checks and production
endpoint/storage checks passed. These results do not clear every remaining item in the full roadmap.
