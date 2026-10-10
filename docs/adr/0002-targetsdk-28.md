# ADR 0002 — targetSdk 28, Android-16-only gate, applicationId

Status: accepted · Spec: §2, §11

## Context

The product is distributed by sideload / F-Droid / GitHub only (never Google Play). The Linux
runtime must `exec()` binaries it unpacks into its own data directory. For apps targeting API 29+
SELinux forbids that (W^X); upstream Termux therefore targets API 28, which keeps the
`untrusted_app_27` domain with `execute_no_trans` on app data.

## Decision

* `compileSdk 36`, `targetSdk 28` (set explicitly; AGP 9 no longer defaults it).
* `minSdk 36` so the whole codebase may use Android 16 APIs without per-call guards. If AGP or
  the platform rejects `minSdk > targetSdk`, fall back to `minSdk 28` plus a hard runtime gate
  that shows "Android 16 required" before anything else runs. (Result of the experiment is
  recorded at the bottom of this file.)
* `applicationId = app.umbra.agent`. It is baked into the Termux `$PREFIX`
  (`/data/data/app.umbra.agent/files/usr`) and into every rebuilt Termux package, so it must not
  change after the first runtime build.
* Native executables and `LD_PRELOAD` shims ship as `jniLibs` with `useLegacyPackaging = true` so
  they exist as real files in `nativeLibraryDir`.
* Lint's `ExpiredTargetSdkVersion` / `OldTargetApi` are disabled on purpose.

## Consequences

* Behaviour gated on targetSdk 29+ does not apply (scoped storage enforcement, FGS type
  requirements, notification-permission targeting). The app still requests what Android 16
  requires at runtime where the platform enforces it regardless of target.
* A future Android release could raise the minimum installable targetSdk or drop the legacy
  SELinux domains; the conformance suite probes `exec` from app data and reports this.
* Cleartext is blocked by default for targetSdk 28; loopback traffic to sidecars needs an explicit
  network security config.

## Experiment result (P0)

* AGP 9.4.1 builds `minSdk 36` with `targetSdk 28`; it only warns, and the warning is silenced with
  `android.sync.suppressAgpWarnings`. `aapt2 dump xmltree` on the debug APK shows
  `minSdkVersion=36`, `targetSdkVersion=28`. So no runtime Android-16 gate is needed for the build
  to work.
* **Not yet proven:** that Android 16 honours target 28 when it is below minSdk (SELinux domain
  `untrusted_app_27`, `exec()` from app data). The conformance suite's `exec_from_app_data` probe
  reads `/proc/self/attr/current` and tries a real `exec`; it must be run on the emulator and on the
  rooted device. If it fails, fall back to `minSdk 28` plus the runtime gate.
* `compileSdk` is 37, not 36: current stable AndroidX (core 1.19, Compose 1.12) requires it. Runtime
  support stays Android 16 only; lint's `NewApi` check (against minSdk 36) rejects API-37-only calls.
