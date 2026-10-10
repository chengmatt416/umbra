# ADR 0006 — File-based session store instead of Room

Status: accepted (deviation from the implementation plan, which said "Room") · Spec: §24

## Context

Sessions must persist across restarts and support pause/resume/stop/restart/duplicate/delete and
history review. The data is append-heavy (timeline events, tool calls, approvals) and read mostly
by session or by tail; it is small per session. OMP additionally keeps its own session file, which
the host re-opens on recovery.

## Decision

One directory per session: `session.json` (metadata, atomically replaced), `events.jsonl`
(append-only timeline), `approvals.jsonl`, plus an `index.json` for listing. Writes use
temp-file + rename for metadata and `O_APPEND` for logs. The `SessionStore` interface lives in a
pure Kotlin/JVM module with an in-memory implementation for tests.

## Consequences

* Fully testable on the host JVM; no KSP/Room toolchain, no Android dependency in the domain.
* Human-inspectable and trivially exportable ("clear session data" is deleting a directory).
* No ad-hoc SQL queries; cross-session search scans metadata, which is fine at this scale. If that
  stops being true the interface allows a SQLite implementation later without touching callers.
