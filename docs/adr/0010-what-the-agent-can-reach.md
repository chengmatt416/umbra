# 0010. What the agent can reach

Status: accepted. Extends [0003](0003-root-first-linux-runtime.md) and [0004](0004-privileged-helper.md).

## Context

The agent's commands run in the Linux environment as the app's own uid ([0003](0003-root-first-linux-runtime.md)),
started by a root shell that enters the environment and drops privileges. That choice keeps the phone's network
access and file ownership simple, and it means the boundary between the agent and the app is weaker than between
two Android apps. Looking for the holes on the emulator (tests in `RootChrootRuntimeLiveTest`,
`scripts/ondevice-confinement.py`) found real ones:

* The folder the environment lives in took the umask of the root shell that created it. An adb root shell's is 0, so
  `/` of the environment was writable by everyone. Root enters the environment by running `setpriv`, `env` and `bash`
  from it, so the agent could have replaced them and been root the next time the app ran anything there.
* Everything root or the app created in the environment was writable by everyone (umask 0), and Bun installs a few of
  omp's own scripts that way.
* Root's commands started in `/workspace`, a folder the agent fills.
* The agent saw every process of the app's uid in `/proc`. For a process that may be inspected, `/proc/<pid>/root`
  is a door into that process's files, so the agent could list the app's private folder (helper tokens, model keys).
  The app's main process, and a `logcat` the app had started as a child, were both such doors.

## Decision

1. **The system part of the environment belongs to root and is closed to everyone else.** `RootChrootRuntime.secure()`
   makes the environment's top folder `root:root 755` and removes write permission from others everywhere except the
   agent's own places (its home, the workspace, `/tmp`, `/var/tmp`, devices). It runs after an install, at the first
   mount of every run of the app, and when the environment is prepared for a task, so it also repairs an environment
   installed earlier. The archive build (`check-tar.py`) fails on anything else.
2. **A sane umask everywhere**: every command in the environment starts with `umask 022` (root's and the agent's), and
   the scripts that create files say so themselves. Root starts in `/`.
3. **The app's own process is not dumpable** (`prctl(PR_SET_DUMPABLE, 0)` at start): the files behind
   `/proc/<app pid>` become root's, and following `root`, reading `environ`, `maps` or `mem` fails for the agent.
4. **The app starts no long-lived process of its own uid.** The log stream is started again, as the shell user or
   root, before the first agent starts, because a stream begun at launch (before the helper or root was known) runs as
   the app. Short-lived commands go to the helper or root first, and the app's own shell is the last resort.
5. **Guest scripts and packs are root's** (ADR 0009), so the agent can run but not change what root runs later.
6. omp's built-in guard patterns forbid only the spellings of wiping the root or the home; a pattern with a star would
   also forbid `rm -rf /workspace/build` (omp's star matches slashes). The host-side risk classification sees every
   other deletion.

## What this does not do

Be honest about the limits; they are why approvals exist and why a hostile agent is not a supported threat model:

* The agent and the app share a uid. The agent can signal the app's main process (and kill it), and a process of the
  same uid that is dumpable can still be looked into. Android's kernels have no PID or user namespaces (the emulator's
  `/proc/self/ns` has neither), so the environment cannot be given a private process table, and the SELinux domain
  of the agent is whatever the root manager gives (`su`). A separate uid would fix this but costs the phone's network
  rules (Doze, data saver) that are keyed to app uids; it was not taken.
* The network is open to the agent, including the phone's loopback. The helpers listen there and require a token the
  agent cannot read (see 3 and 4); the token is 128 bits.
* Anything the agent does through omp's tools is subject to approvals only as far as the mode asks (yolo asks nothing).

## Consequences

* The checks are tests, not review: `theAgentCannotChangeTheSystemPartOfTheGuest` (live), `ondevice-confinement.py`
  (on the device, as the agent), `check-tar.py` (at build).
* A new door would show up as a failing check, for instance a new app-uid child process.
