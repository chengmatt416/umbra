# Requested feature readiness

Reviewed 2026-10-23 against the working tree. Both voice modes are explicitly deferred by the owner.
This is an implementation map, not a claim that a model can prevent every prompt injection or that
fixture tests establish production model quality.

| Request | Current implementation | Scope and limits |
| --- | --- | --- |
| Laya-only prompt-injection prevention | `LayaActionSecurityCheck` batches an explicit injection decision with action risk and category. Confident suspected injection becomes CRITICAL and requires fresh confirmation in every approval mode, even with a saved command prefix. | Only Laya performs this model check. One bounded local inference reviews the action, screen context and samples of the latest coding tool results. It does not scan whole files, images or transcripts. Missing, uncertain or unavailable Laya retains the deterministic action safeguards; it is not a clean-content verdict. |
| Real multimodal live mode | Deferred by the owner. | No live audio/video model connection is claimed. |
| Low-cost local transcription + TTS live mode | Deferred by the owner. | Existing dictation is not a live duplex conversation. Persistent steer/queue/Laya interruption preferences remain part of this deferred feature. |
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

Run the standard checks with `source scripts/env.sh`, the pinned `OMP_BIN`, and Gradle tasks
`:core:safety:test :core:orchestrator:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
Use `-Pumbra.bundleLinux=false` for a development APK that relies on an already installed guest.
