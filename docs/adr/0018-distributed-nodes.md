# 0018. Distributed nodes

Status: accepted. Architecture spec §10.

## Context

Some work is better done on a computer the user owns (a long build, a model that needs more memory). The spec asks
for enrolled nodes with mutual pairing and trust tiers, policy-bound placement, a data-transfer manifest (allowlist,
resolved files, no path escape, a ceiling, no secrets, retention), lease-bounded offline execution, conditional
failover and rejoin reconciliation, and honesty about what pairing does not prove.

## Decision

1. **`umbra-node`** (`node-runner`, a JVM program for macOS and Linux) keeps an identity in a private folder
   (`~/.umbra-node`, owner-only): an EC P-256 key and self-signed certificate made with the JDK's keytool.
2. **Pairing** opens for ten minutes with a one-time 12-character code (60 bits) and five attempts. The phone proves
   it knows the code with an HMAC (PBKDF2-derived key) over both certificates' fingerprints, and the node answers
   with its own proof, so a machine in the middle, which would present a different certificate, cannot complete it.
   Each side then pins the other's certificate; afterwards only mutual TLS 1.3 with pinned certificates gets past the
   handshake. The phone's key lives in the Android Keystore and never leaves it.
3. **Placement** follows a versioned policy: a minimum trust (a new node is "limited" and gets nothing until the user
   raises it), a ceiling on the data that may leave the phone per job, the offline lease, and no secrets. A job
   records the revision it ran under. A preflight (`hello`) reports the node's OS, CPUs, memory, tools and epoch.
4. **Data leaves only by manifest**: built on the phone from an allowlist, every file resolved, hashed and sized,
   `..`, absolute paths and links that resolve outside refused, the total under the ceiling. The node accepts only
   listed files, at their declared sizes, and checks each hash; it deletes the job's folder when the phone forgets
   the job (`delete-after-job`).
5. **Jobs run in their own folder** with a small environment (PATH, HOME, LANG, USER, SHELL, a private TMPDIR): the
   node's own secrets are not inherited. **Each job has a lease** the node enforces itself: the phone renews it while
   connected; if the phone goes away the job runs until the lease ends and the node ends it then. The phone cannot
   confirm when that happened until it rejoins.
6. **Rejoin**: the node's account of its jobs (state, exit, output tail, epoch) becomes the phone's record; a job
   the node no longer knows is marked lost, never assumed finished. A runner that restarted (a new epoch) reports the
   jobs it no longer watches as lost.
7. **Not in this version**: automatic failover to another node and agent-driven placement. A lost node's job is left
   to its lease; a new attempt elsewhere is started by hand; no two nodes are given the same write. The screen says so.

## Consequences

* A paired node runs commands as its user: what a command may touch on that computer is that user's. The pairing
  output and the screen say this; pairing is identity, not attestation.
* Verified: `NodeEndToEndTest` (real TLS and processes on the host: pairing with right and wrong codes, strangers and
  wrong pins refused, manifest-only uploads, confinement and no inherited secrets, lease expiry while the phone was
  away, rejoin, a restarted runner), `NodeRegistryTest`; on the emulator against `umbra-node` on this Mac: pairing
  through the screen with the code, preflight, a job sending two files by manifest and returning its output, and the
  node deleting the job's folder when the phone forgot it.
