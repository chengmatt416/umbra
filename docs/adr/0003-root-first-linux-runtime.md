# ADR 0003 — Root-first Linux runtime (Debian chroot); non-root via forked Termux

Status: accepted · Spec: §9, §11, §12

## Context

* Android's app-process seccomp filter kills newer syscalls (`faccessat2`, `openat2`,
  `fchmodat2`) with SIGSYS. glibc ≥ 2.33 and runtimes that issue raw syscalls (Bun/Zig, Go) hit
  them when started from an app process.
* A process started through `su` is a child of the root daemon, not of the app zygote, so it is
  not subject to that filter.
* Forking Termux under a new applicationId requires rebuilding every package (including
  glibc-packages) for the new `$PREFIX`; upstream package repositories cannot be mixed in.
* PRoot is reported broken or hanging on some Android 15/16 devices.

## Decision

* Rooted devices: the primary runtime is a **Debian chroot** (`RootChrootProvider`) with a private
  mount namespace, the app workspace bind-mounted, guest processes running as the app uid with
  Android's `inet` group, and an unmodified official Bun build.
* Non-root devices: `TermuxPrefixProvider` (forked Termux bootstrap + `glibc-runner` + a SIGSYS →
  ENOSYS `LD_PRELOAD` shim for dynamic binaries), with `PRootProvider` as a probed fallback. These
  are implemented but need a CI-built bootstrap and a device run before they can be called
  verified.
* AVF (Android 16 Linux Terminal VM) is probed and reported but not controlled in v1.
* Providers are selected by **executed probes**, not version checks, and the choice is shown in
  the conformance report.

## Consequences

* The test device (rooted Android 16) exercises the highest-confidence path first.
* The shim is validated on the dev Mac by emulating the filter (`SECCOMP_RET_TRAP` for the three
  syscalls) inside a linux/arm64 container; this proves the mechanism, not Android's exact policy.
* Static binaries ignore `LD_PRELOAD`; they need binary patching or a root run.
