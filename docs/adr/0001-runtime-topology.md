# ADR 0001 — Runtime topology: one OMP process per session, Android as host tools

Status: accepted · Spec: §3, §14, §16, §21, §24

## Context

The spec makes OMP (Oh My Pi) the autonomous runtime and asks for one unified tool surface over
Android, Linux CLI, Linux GUI and privileged access. OMP is a Bun/TypeScript program with a Rust
napi core. It exposes `omp --mode rpc` (newline-delimited JSON over stdio, protocol v2) with
**host tools**: the embedding program registers tools with `set_host_tools` and answers
`host_tool_call` frames. Approvals surface as `extension_ui_request` (`confirm`/`select`). A process
holds **one live session**, and the protocol has **no pause command**.

## Decision

* The Android app is the RPC *host*. Each top-level agent session owns one `omp` child process
  started inside the guest userland (Debian chroot, or the Termux prefix on the non-root path).
* Android, display, privileged and Linux-GUI capabilities are registered as host tools with a
  common `ActionRequest` / `ActionResult` envelope (spec §16). Linux CLI work uses OMP's own
  built-ins (`bash`, `read`, `edit`, `grep`, `lsp`, `debug`, `task`) running in the guest.
* OMP's desktop-only built-ins (`computer`, local `browser`, screenshot/enigo) are disabled; the
  host tools replace them.
* Subagents are OMP `task` fan-out inside one process (events: `subagent_*`). Concurrent top-level
  sessions are separate processes, governed by `PerfGovernor`.
* Approvals from OMP and from the host `RiskClassifier` feed one queue (ADR 0004 covers who may
  execute what).
* Pause is cooperative: hold host-tool results, `abort` after a timeout, resume with `follow_up`.
  A session that dies is re-attached with `open_session` from OMP's own session file.

## Consequences

* No fork of OMP is needed for integration; the Android patch set stays small and lives in
  `omp-android/PATCHES.md`, pinned to the 18.5.1 release.
* Per-session memory cost is a Bun + JSC process (not yet measured on device); the number of
  parallel sessions is therefore a runtime policy, not a constant.
* The RPC client is pure Kotlin/JVM and is tested against the real `omp` binary on the dev Mac.
