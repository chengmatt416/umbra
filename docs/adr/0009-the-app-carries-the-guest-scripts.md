# 0009. The app carries the guest's scripts

Status: accepted. Extends [0003](0003-root-first-linux-runtime.md).

## Context

The Linux environment is a large archive (176 MB) that is built rarely. The scripts the app relies on inside it
(`umbra-gui`, the desktop helper; `umbra-pack`, the toolchain installer) and the package lists of the packs change
with the app. With the scripts only in the archive, a newer app ran against older scripts without anyone noticing:
a fixed `umbra-gui` sat in the repository while the installed environment still had the old one, and every script
fix would have needed a new 176 MB archive.

## Decision

`guest/bin` and `guest/packs` travel inside the APK as assets (the build copies the folder). Before an agent
starts in the environment, and whenever the app prepares it (installing a pack, diagnostics), the app compares a
version with the one recorded in the environment (`/opt/umbra/.files-version`). The version is the first 16 hex
digits of a SHA-256 over every file's path, mode, length and content. When the two differ, the app writes every file
as root (`0:0`; scripts and `.post` steps executable), each replaced in one step so a running script is not
disturbed, and then records the new version.

* The files belong to root, so the agent (the app user) can run them but not change them. That matters for
  `umbra-pack`, which root runs later.
* It happens at most once per run of the app, under a lock. A failure leaves the environment as it was and is
  tried again at the next start.
* A fresh install takes the same path: an archive without these files gets them on first start.
* Files the app stops shipping are not removed. A leftover is harmless.

## Consequences

* Script fixes and new packs reach environments installed months ago with the app alone. The Android build pack
  arrived this way.
* The archive keeps its own copy so it works stand-alone (tests, other front ends); the app's copy wins.
* A pack can carry a `.post` step for what the distribution does not package. It runs as root after apt, must be
  safe to repeat, and downloads only from the vendor's own servers with a pinned checksum
  (`guest/packs/android.post`: R8 and the SDK platform from Google).
