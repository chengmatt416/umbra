# 0012. The Linux environment without root: proot

Status: accepted. Complements [0003](0003-root-first-linux-runtime.md) and [0010](0010-what-the-agent-can-reach.md).

## Context

The chroot runtime needs root. The product must also work on a phone without root.

## Decision

1. **proot** runs the same Debian environment without root: it translates every path the environment's programs use
   into the rootfs folder with ptrace and fakes root for package installs. The APK carries Termux's arm64 builds of
   proot, talloc and libandroid-shmem (`third_party/proot`, SHA-256 pinned by `runtime-build/proot/fetch.sh`, renamed
   to `lib*.so` so Android extracts and lets the app execute them).
2. Without root there is no root `tar`: the in-app `TarReader` unpacks the archive as the app's uid.
3. **An environment stays with the mode it was unpacked for** (the owners of its files differ). One unpacked under proot
   keeps running under proot even when root appears. One unpacked for root runs under proot only if its files are the
   app's own (checked by owner, not by permission bits: SELinux labels on root's files refuse execution even when the
   bits look open); otherwise the user is told that root is not answering, rather than shown a proot execve error.
4. `ProotRuntime.secure()` does nothing: in this mode the agent's programs are the app's own processes and nothing
   separates them from the app. The set-up screen says so ("not kept apart from the app the way the root mode is").

## Consequences

* Works on any phone, at the cost of ptrace overhead and of the isolation ADR 0010 gives the chroot.
* Licences: proot GPL-2.0-or-later, talloc LGPL-3.0-or-later, libandroid-shmem BSD-3-Clause; sources are linked and
  offered in `third_party/proot/README.md`.
