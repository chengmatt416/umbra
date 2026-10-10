# 0017. The commit gate

Status: accepted. Architecture spec §11.

## Context

Agents commit in their workspaces. Publishing those commits (a push to a shared branch) is an external side effect:
it can race other writers, it can be cut off before its answer arrives, and a force push can destroy other people's
work. The spec asks for an authoritative gate on the real write path with a durable lifecycle
(VALIDATE → PREPARE → FENCE → COMMIT → VERIFY → FINALIZE), conditional writes, an explicit "outcome unknown" state
settled by checking before any retry, no unauthorized force push, and scoped single-use capabilities.

## Decision

1. **`CommitGate` (core/control)** promotes one repository's commit to one remote branch. Validation reads the
   candidate commit, the remote's current ref (`ls-remote`), whether it is a fast-forward, and the evidence (commits,
   diffstat, the tree it publishes). Nothing to publish, an unreadable remote, a detached HEAD or a remote commit the
   repository does not have refuse the proposal.
2. **Only the user authorizes**, on the Commit & Integration screen, for one promotion. A non-fast-forward needs a
   separate permission for exactly that promotion; without it the promotion is refused and nothing is sent.
3. **Prepare and fence**: the intent is persisted with a capability (resource, action, expected old value, new value,
   fence epoch, ten-minute expiry, single use) before anything is sent; each authorization takes a new fence epoch of
   the target, so an older attempt cannot write.
4. **The write is conditional**: the remote is read again just before writing (a moved ref is not overwritten), and
   the push uses `--force-with-lease=<ref>:<expected old>`, so the remote itself refuses if it moved in between.
5. **Verify before anything else**: after the write the remote is read back. The candidate there → committed (receipt
   in the journal). The old value there → not written. Anything else → someone else's write; nothing is overwritten.
   A write whose answer was lost is OUTCOME_UNKNOWN and settled the same way. At start-up, a write the app died in is
   settled by reading the remote (checkpointed forward recovery); a prepared intent never sent is abandoned.
6. Every transition is in a hash-chained promotion journal; the screen checks the chain.
7. Validation reads the candidate commit itself; no staging worktree is created. The push uses the repository's own
   git credentials in Linux, with no terminal prompt; Umbra brokers no git credential yet (ADR 0016 says so).

## Consequences

* External effects are not exactly-once and are not claimed to be; the record and the read-back make every outcome
  explicit.
* Verified against real git on the host (`CommitGateTest`: fast-forward, a remote that moved, a rewrite needing its own
  permission, a lost answer both ways, recovery after the app died) and on the emulator in the phone's Linux
  environment: a promotion published and read back, and one refused because another writer moved the branch.
