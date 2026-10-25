# Proactive roadmap implementation

The owner explicitly included the entire [proactive specification](design/proactive-agents-spec-2026-10.md)
on 2026-10-23. This is an implementation checklist, not a release clearance. The security scan is
skipped at the owner's request. Live voice and the future local vision decision model stay deferred.

## Verified foundations

- New coding startup survives composer cancellation/navigation; the Android fixture completes without
  pause/resume and leaves no sent draft behind.
- QR camera teardown is serialized with decoding. Ten live-camera lifecycle transitions keep the
  same app process and leave manual pairing usable.
- Capsule startup and reads follow internal main-program broadcasts. The Android fixture observes
  no content invalidations while idle.
- Command approval offers once and a second-confirmed permanent literal prefix; the Android fixture
  verifies restart persistence and refuses compound-command prefix grants.
- Backup Vision Model is configurable in onboarding and Settings. Native-runtime tests verify direct
  images for a vision-capable main model, tool-free fallback for a text model and shared task billing.
- Read-only discovery is a separate, immutable session mode. Native-runtime regression checks verify
  that mutations are neither advertised nor executed, including after a follow-up, and TUI access is
  refused. Background research does not feed its result back into an authorized conversation.
- Atomic proactive state, goal dependencies/completion evidence, opportunity claiming/expiry,
  routine authorization/idempotency, watcher no-match behavior, attention tiers, memory retrieval,
  and group membership/hop validation have focused JVM tests.

## Roadmap completion checklist

Updated 2026-10-24. “Implemented” below describes the stated path, not the entire row's release clearance.
All remaining work stays in scope unless explicitly marked deferred by the owner.

| Capability | Implemented path | Remaining work |
| --- | --- | --- |
| A1 Persistent goals | Goal UI/tools, durable milestones/dependencies, event-driven L4 dispatch, cron/default reconciliation, deadline review cards, deadline-aware dispatch order, verified-session evidence and native authorization; stale updates cannot replace newer user decisions | Extended live-model and simultaneous-goal/deadline fixtures |
| A2 Opportunity discovery | Read-only research, typed cards, edited adoption, dismissal, durable claims and restart reconciliation | Full live source match/adoption/revocation fixture |
| A3 Event triggers | Local notification/web/guest probes, bounded RE2 notification-body patterns and Laya semantic conditions; scoped authorized runs; existing literal watchers retain their matching mode | Android source-match and delivery fixtures across notification packages |
| A4 Time routines | Reviewed scopes, cron, idempotency, 50-routine limit, 20 run records, TTL and authorization-bound runs | Android restart/approval-expiry fixture |
| A5 Replanning | Preserved completed milestones, history, old-task cancellation, renewed grants, native deadline/cadence checks and ranked final acceptance review; Android restart/replan fixture | Extended specialist assignment and live-model replanning fixtures |
| A6 Attention | Five tiers; quiet hours, calls, playback, meeting context, recent typing timing and foreground game/video detection | Complete immersive-app and channel delivery fixtures |
| A7 Briefs | Local morning/evening generation, a per-agent **Briefing page** (Agents overview row and chat) separate from settings, briefs that unroll section by section on tap or pull-down (Home and Briefing), direct Home/chat entries, morning/evening time controls, conflicts and preparation research; automatic delivery and Telegram `/brief` include bounded decisions, conflicts, schedule, completed work and updates | Real-account calendar/preparation and channel delivery fixtures |
| A8 Prepared next steps | Explicit native or linked-private Telegram review/adoption; the native sheet shows prepared fields readably (arrays as chips) with an empty plain message field (appended to the adopted task, ≤4000 chars) and edits only a message draft's text, never configuration; keyboard-open confirmation, visible task and duplicate prevention | Complete action-specific external service fixtures |
| B1 Background work | Supervisor/service, scoped research, routine/goal/team reconciliation; **wake budget** (`WakeBudget`): ≤12 background runs per agent per hour, ≥1 min apart, none while the previous background run is unfinished, held-back work deferred (≤40) to the next allowed wake; repeating timers ≥5 min, ≤50 per agent | Extended process-death fixtures and device endurance measurements |
| B2 Cross-app context | Per-agent grants; messages, notifications, memory, contacts, calendar, Gmail and local tasks; email draft/send; calendar proposals/create and approved update/delete of single non-recurring events without other guests, with before/after review, turn idempotency and unchanged-event recheck; per-agent Android-picker document context with bounded excerpts, UTF-8 and DOCX/XLSX/PPTX/ODT/ODS/ODP text extraction, plus provider-offered plain-text/CSV/TSV exports of virtual cloud documents | Recurring/guest event changes (left to the user by design), OAuth refresh/registration, PDF/legacy binary Office parsing, providers without text exports and document workspace/edit workflows |
| B3 API/DOM/visual routing | Connectors, native browser and Laya screen plan; metered tool-free backup vision; **connector-first routing** (`ConnectorRouting`): omp browser/eval/computer approvals, `gui_*`/`remote_screen` and `android_launch`/`android_switch` calls that target an enabled structured connector's web app or Android app are refused once per session with the connector's tools named (steered into omp), a deliberate repeat proceeds through the ordinary gates and is logged as a visual fallback | Real-account checks of each connector's coverage versus its visual fallback |
| B4 Teams | Groups UI, member arbitration, four host-counted hops, **spawn guard** (`SpawnGuard`, enforced in `AgentSupervisor.create` for every spawned session): depth ≤3, ≤4 unfinished children per session, ≤6 unfinished per root, ≤24 starts per root per hour, coordinator summary, parent budgets, structured handoffs and descendant stop; parent chat lists and opens active coding tasks and nested subagents/specialist handoffs; incomplete coordinator turns wait for descendant reports before propagating final results, notifications or routine success | Handoff/restart/stop Android fixtures; real-account delivery checks |
| B5 Verification | Host-derived tool receipts; failed/stale/truncated checks cannot be model-declared verified | Complete pre-completion contract across all API and browser operations |
| B6 Deliverables | Validated owned artifacts, SHA-256 checked opening, Markdown receipts and bounded visual preview; screenshot events record host-derived SHA-256, size and MIME, and final visual receipts/preview require that metadata and the matching bytes; Telegram-originated turns send published artifacts back as documents after the same hash check (≤50 MB each, ≤10) | Real-account Telegram delivery and screenshot/OCR privacy fixtures |
| B7 Teach mode | Explicit Android recording up to ten minutes, masked structured trace, bounded form-free keyframes, parameterized draft and test/enable gates; capsule/notification stop; **teaching on a paired computer** (the remote viewer reports clicks, drags and special keys; typed text is a placeholder only; no frames kept) and **on the Linux desktop** (Screens page taps and sent text), drafted as `REMOTE_NODE`/`LINUX_CLI` skills with relative positions and `remote_screen`/`gui_*` instructions | DOM-level (selector) capture in the headless browser; broader keyframe and interruption fixtures |
| B8 Skills/tools | Shared/owned skills, verified-task extraction, patch review, exact-version tests, templates; isolated reviewed pure JSON JavaScript transforms; **synthesized Python/Bun/Bash tools** (`GuestScriptPolicy`, `script_run`): declared capabilities (no network, or HTTPS to 1–5 literal domains), deterministic static checks (no processes, dynamic code, file writes, privilege/device/Umbra paths, computed URLs), complete-source Laya review, JSON stdin/stdout tests, exact-version enablement, root-owned reinstall + hash read-back per run, 30 s / 64 KB bounds, network runs through DLP and the approval gate; never available to read-only discovery | Kernel-enforced network isolation for scripts (the guest has no per-process network namespace); real-model synthesis quality |
| C1 Low-friction entry | Capsule, Telegram commands and reviewed opportunity callbacks; Teach entry in Skills/Capsule; one-time credential-panel links and notifications, verified Android HTTPS association with local fallback; actionable Telegram suggestions for its selected linked agent or existing request channel | Real Telegram delivery fixtures |
| C2 Shared history | Persisted sessions, group messages, routine results and linked receipts | Complete cross-channel artifact navigation |
| C3 Low-noise delivery | Native attention ranking, durable queues and broadcast-driven capsule suggestion badges/review cards with scheduled expiry | Complete immersive-state handling and channel delivery fixtures |
| C4 View/takeover | Unified Android/Linux/node ownership, writer fences, pause/return and required fresh observation | Android end-to-end takeover fixture and background Android manual input surface |
| C5 Approval | Once/permanent literal-prefix confirmation, full outbound review, custom four-way rules, TTL and native credential panel | Complete background TTL fixture |
| C6 Logs | Session/approval history, routine runs, team messages and deliverable receipts | Cross-channel presentation coverage |
| D1 Read-only proactivity | Immutable discovery mode, native host and SDK tool restrictions, scoped inherited authority | Complete connector/browser integration fixtures |
| D2 Scoped rules | Four-way custom rules, per-agent source and connector grants, exact action boundaries | Extended integration fixtures for every connector |
| D3 Independent review/DLP | Laya-only independent action/injection review; deterministic outbound checks, native tool-result redaction and provider-boundary guard | Image/OCR protection and complete arbitrary SDK/network path coverage |
| D4 Credential handoff | One-time expiring local/HTTPS links, notification/chat/capsule/vault entry, screenshot-protected native input, at-most-once form-bound injection, durable encrypted redaction values and clear/authentication-before-return checks | Browser/WebView and remote targets require direct user takeover; real channel fixtures and image/OCR protection remain |
| D5 Checkout | Critical checkout review, complete payload, unknown terms shown explicitly, mandatory takeover for payment entry/SCA | Browser/API checkout and visual-proof fixtures |
| D6 Auditability | Persistent journals, receipts, validated artifact anchors and hash-bound screenshot evidence with checked private preview copies | Full cross-channel evidence anchors; real-device proof/privacy fixtures |
| D7 Budgets/rates | Task and ancestor-agent limits, reset windows, quota checks, group/routine ancestry; native auxiliary inference disabled for limited tasks | Pinned Android auxiliary-call tests and hard in-flight reservation limits |
| D8 Stop/revoke | Agent-owned task cancellation, descendant group stop, routine/goal/watcher authorization fences, race-safe watcher observation updates, source revoke/purge UI | Full process-death and revocation-during-approval fixtures |

## Current verification

- 2026-10-24 (0.6.0 pass): synthesized scripts ran for real in the emulator's Linux guest (python, bun, bash; ~200–330 ms; a failing script is reported with its exit code) through `test.script.run`; policy, store-gate, interpreter-flag, connector-routing, remote/Linux-desktop teaching, brief-headline, cron-text and message-draft tests pass. Proactive UI (designed brief and suggestion cards, Home deck, goal progress, readable watcher/routine/trigger text, pending chat bubbles, centred Capsule placement and reply peek) checked on the emulator and recorded for the website.

- Updated Obsidian note §§11, 17–19: optional install/MAU telemetry, plain suggestion input, direct
  suggestion/brief entries and linked active descendants are implemented. App tests: 168 passed;
  development APK and lint passed. Android UI fixture confirms Home/chat links, direct/nested task
  links and keyboard-open confirmation with the exact edited instruction, without provider calls.
- Android document fixture now also extracts a DOCX, retains the read-only picker grant across restart,
  enforces agent/routine scope and revocation, rejects an attempted sensitive-name agent without
  leaving records, safely stops a blocked legacy profile during restore, and defers onboarding links.
- Screenshot receipts now include host-derived hashes, sizes and MIME types. Preview copies must
  match those bytes; legacy path-only observations cannot establish visual verification. Recorder and
  receipt tests pass (orchestrator suite: 92 tests).
- A real-omp delegation regression holds a coordinator's partial completion, waits through its
  descendant becoming terminal, and forwards exactly one freshly aggregated report. The same host
  predicate gates routine success, native final notifications and Telegram answers. Independently
  scheduled routines/research do not block an unrelated interactive answer.
- Telemetry is deployed on the existing Cloudflare site. Four Node tests and local R2 checks pass;
  production rejects malformed payloads and accepted duplicate synthetic reports. Both temporary
  private count records were verified and removed. Deployment reused existing live static assets and
  the unchanged APK feed, with only the privacy documents updated. No new APK was published here.

- JVM results before the current goal/delegate follow-up: app 128, orchestrator 85, runtime/omp 77, control 45 and safety 287 tests, zero failures/errors/skips in the latest corresponding suite runs.
- Orchestrator and omp integration suites pass against the product-pinned omp 18.12.1 runtime. A regression also checks that a follow-up cannot republish the previous turn's artifacts, risks or Git summary.
- App lint and development APK assembly pass with `-Pumbra.bundleLinux=false` (existing guest required).
- Android goal fixture starts without pause/resume, advances a dependent milestone through a checked coding-session report, blocks revoked in-flight work, preserves revocation across app restart, replans while keeping completed work, requires a new grant and stages final acceptance for review (13 scripted localhost provider requests).
- Watcher regression tests cover revocation during probing/local review, removal during a failed probe and one-shot authorization continuity. Watcher-triggered tasks and their descendants check the captured grant before subsequent managed requests/actions.
- Android watcher fixture blocks a revoked in-flight response and its provider follow-up, then permits the new grant to save a private note (three scripted localhost requests). It also creates a native RE2 watcher on Android.
- Telegram proposal buttons bind the complete card and its evidence to the original private chat, require a separate review before adoption, expire after ten minutes and invalidate on restart, unlink or disabling messages. This path has focused JVM checks; real-account delivery has not been exercised.
- RE2 notification patterns reject unsupported syntax and oversized/complex programs, avoid backtracking on nested quantifiers and retain prior literal behavior, including case folding. Probes inspect at most 200 matching-package records and 8,000 body characters per record.
- Android startup fixture completes without pause/resume, including navigation before the first provider request; sent draft clears.
- Android group fixture completes four hops plus coordinator summary with six scripted provider requests and the shared root task.
- Android Teach fixture records synthetic public/password input without retaining either value, excludes keyboard events and form screenshots, and creates only an untested draft; no provider calls.
- Isolated transform Android fixture verifies unavailable network/file/native bridges, separate renderer UID, timeout and recovery.
- Native credential Android fixture passes unchanged-form injection, changed-form rejection, clearing and deleted-source/app-restart recovery, with encrypted-only persistence, clean timelines and no provider calls. Android accessibility must be re-enabled after an explicit force-stop; stale window identities require manual field clearing.
- HTTPS handoff parsing passes JVM checks. The production website serves the existing release certificate's Android association and a fragment-only, no-credential fallback page. Android reports `umbra-agent.pages.dev` verified. All three native cases also pass through default OS HTTPS routing, without an explicit app target; the app generates HTTPS only after verification. Website tests cover invalid fragments, removing the fragment from history, package-bound opening, content restrictions and response headers.
- Markdown summaries, usage settings, once/permanent prefix confirmation, restart persistence and compound-command refusal pass on Android.
- Android capsule suggestions fixture shows only eligible ambient items, opens the correct review page without executing, rejects malformed routes and clears expired suggestions through a scheduled broadcast, with no model calls.
- Android document-context fixture passes native picker authorization, read-only persistence, unsupported-binary exclusion, per-agent and routine scopes, restart recovery and revocation, with no provider calls. Retrieval reads bounded UTF-8, supported Office/OpenDocument text and provider-offered cloud text exports at query time, limited to 500 entries, eight nested levels, 512 KB per file, 4 MB total and bounded excerpts; no persistent document copies/index or edits.
- Bridge restart regression checks same-port reuse with closing clients; Android repeated setup preserves an authenticated client.
- Dense QR regression reproduces an orientation-specific detector miss and verifies actual luminance rotation, padded strides and nonzero buffer offsets. Successful physical-camera QR decoding is still pending; decoder tests and lifecycle stress are separate checks. The latest Android remote-node regression passes membership modes, pinned-TLS pairing, input-free watch and explicit pointer/keyboard takeover using a synthetic RFB fixture.

## Release constraints

Rootless PRoot remains supported. Root mode uses chroot, protects system files and drops agent
capabilities; rootless mode provides a PRoot environment without those kernel protections. Neither
currently provides a distinct Android UID for agent code. Do not describe either as a complete
isolation boundary. Pure JSON JavaScript transforms run only in the isolated renderer after local review, tests and explicit user enablement. General executable synthesized scripts remain unimplemented.

Distribution still requires the open items in [the release legal review](RELEASE_LEGAL_REVIEW.md),
final bundled release validation, and physical-device checks. Release assembly with bundled Linux 0.4.0 and release lint passed after the document-context, bridge and capsule-suggestion changes. Goal scheduling/concurrency also passed bundled release assembly and release lint. Telegram, notification-pattern, watcher-authority and HTTPS handoff changes pass bundled release assembly and release lint; the packaged APK contains the Linux assets and RE2/J license. A validation APK signed with the existing production key passes certificate verification and matches the published Android association. Tagged CI releases now require signing configuration and an APK version matching the tag; development fallback signing still works, and the missing-signing case rejects as expected. No APK has been uploaded or published by this pass. These builds do not clear the unfinished roadmap or distribution review. Emulator fixtures and scripted model
endpoints do not establish live-model quality, real-account interoperability or measured energy use.

## Integration references

- [Android Calendar provider](https://developer.android.com/identity/providers/calendar-provider)
- [Gmail message search](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list)
- [Gmail message retrieval](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get)
- [RE2/J releases and supported matching model](https://github.com/google/re2j)
- [jsoup parser and Android support](https://jsoup.org/download)
