# Developing Umbra

Umbra is an Android 16 app that runs an autonomous agent (omp) on the phone. It sees and operates
apps through an accessibility service, a privileged helper and root, and gives the agent a Debian
environment to work in. This page is how to build it, test it and run it.

## What you need

| Thing | Why | Notes |
| --- | --- | --- |
| JDK 21 | Gradle, Kotlin | `scripts/env.sh` finds it (the system `java` may be older). |
| Android SDK, platform 37 + build-tools | the app | `ANDROID_HOME` or `~/Library/Android/sdk`. |
| An Android 16 arm64 emulator **or** a rooted phone | live tests, running the app | `scripts/emulator.sh start` boots the `umbra36` AVD (google_apis, arm64, rootable with `adb shell su 0`). |
| Docker | building the Linux environment image | Only for `runtime-build/rootfs/build.sh`. |
| `omp` + Bun on the host | tests that run the real agent | `bun install -g @oh-my-pi/pi-coding-agent@18.6.1`. Tests skip themselves without it. |

`source scripts/env.sh` once per shell: it exports `JAVA_HOME` and `ANDROID_HOME`.

## Everyday commands

```bash
source scripts/env.sh
scripts/verify.sh                 # build + all unit tests + lint, then checks that every test really ran
./gradlew :app:assembleDebug      # the APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:orchestrator:test # one module
scripts/emulator.sh start|stop|status
scripts/dev-device.sh install     # install the debug APK, grant what the app needs, start the helper
```

On an 8 GB Mac, stop the emulator (`scripts/emulator.sh stop`) while Gradle builds the app, and run
`scripts/emulator.sh trim` once on a new AVD: the preinstalled Google apps eat the 2 GB guest alive
(Settings takes ten seconds to start instead of one, and the live tests time out).

## How the code is organised

```
app/                    Compose UI, foreground service, notifications, settings, the debug control channel
core/model              the shared vocabulary: ActionRequest/Result, observations, capabilities
core/safety             risk classification, approval policy, the safety gate (spec §28)
core/session            persistent sessions and timelines (file based, ADR 0006)
core/workspace          project and session directories, visible from Android and from Linux
core/perf, recovery     resource governor and health supervisor
core/decision           fast typed decisions (heuristics now, Laya sidecar when present, ADR 0005)
core/orchestrator       runs agent sessions: omp lifecycle, approvals, timeline, recovery
runtime/omp             the omp RPC client, config writer, process spawning
runtime/linux           the Debian guest: install, mount, run as the app user, the desktop backend
platform/api            the backend interfaces everything above talks to
platform/shell|android|privileged|server|logs|vision|adb   the real implementations of those interfaces
tools/                  the host tools omp calls (android_*, priv_*, gui_*, verify, decide) and the action pipeline
guest/, runtime-build/  what goes into the Linux image, and how it is built
```

The tool path: omp calls a host tool → `UmbraToolHost` decodes it → `ActionPipeline` resolves the
target, asks the safety gate, takes a display lease, runs strategies with fallbacks, verifies the
effect → a structured result goes back to omp. Debug builds also serve the same path over a loopback
`ToolBridgeServer`, which is how the development scripts call tools on a device.

## Testing

Tests are real, not simulated. Where a test needs a device, it uses one and **skips itself** when none
is attached, so `./gradlew check` is green anywhere.

| Test kind | Needs | Examples |
| --- | --- | --- |
| Unit | nothing | safety rules, parsers (with golden files recorded from a real device), state machines |
| omp protocol and orchestrator | `omp` on the host | `OmpIntegrationTest`, `AgentSessionTest`: the real binary against a scripted OpenAI-compatible endpoint, which stands in for the model only |
| Live device | an emulator or phone with `adb` | `DeviceBackendsLiveTest`, `PrivilegedHelperLiveTest`, `RootChrootRuntimeLiveTest`, the on-device pipeline tests |

Golden files under `src/test/resources/golden` are recordings of real device output. To re-record one,
run the command on a device and replace the file (`scripts/record-golden.sh` automates the common ones).

`scripts/test-summary.py` fails the build if a module's tests did not run, or if JUnit silently skipped
a test method that returns a value.

## The Linux environment

```bash
runtime-build/rootfs/build.sh dev     # build/rootfs/umbra-rootfs-dev-arm64.tar.xz (+ .sha256, manifest.json)
```

The archive is a Debian trixie arm64 root filesystem with Bun, the pinned omp (18.6.1), git, Python
and ripgrep; larger toolchains are *packs* (`guest/packs/*.apt`, plus an optional executable `<pack>.post`
step for what Debian does not package) installed on demand from the app: `dev`, `java`, `node`, `android`
(Java 21, aapt2, apksigner, d8 and the SDK platform) and `gui` (Xvfb, openbox, AT-SPI).

The scripts in `guest/bin` and the pack lists in `guest/packs` are also assets of the APK. The app writes them
into the environment, as root, whenever the recorded version differs from its own (see
[ADR 0009](adr/0009-the-app-carries-the-guest-scripts.md)), so a script fix needs a new app, not a new archive.
In debug builds `scripts/umbractl.py call guest.status` prepares the environment the way a task start does,
including that step.

Things that were learned the hard way and are encoded in the build:

* `docker` hangs for minutes on this setup because Docker Desktop's credential helper blocks; the
  script uses a private, credential-free `DOCKER_CONFIG`.
* Android ships no `xz`, so the **app** decompresses the archive and streams plain tar into `tar`
  running as root. Android's toybox `tar` cannot restore hard links whose target path is over 100
  characters, so the image has none (`check-tar.py` fails the build if one appears).
* A layer diff drops files whose size, mode and mtime did not change, so the hard-link rewrite copies
  without preserving timestamps.

To use it on the emulator or a phone, push the archive and use **Set up → Choose file…** in the app, or
host it over https and paste the URL (a `.sha256` next to it is verified).

## Running on a phone

1. Install the APK. For a phone that is not a test device use the release build, which has no development
   hooks and is not debuggable: `./gradlew :app:assembleRelease` gives `app/build/outputs/apk/release/app-release.apk`
   (signed with your keystore when `UMBRA_KEYSTORE` and the related variables are set, with the debug key
   otherwise), then `adb install -r` it or copy it over and open it. `scripts/dev-device.sh install` installs the
   debug build for development.
2. Open Umbra → **Set up** and work down the list: accessibility, the Linux environment, a model.
3. Root is needed for the Linux environment and the privileged helper. With Magisk or KernelSU, set the
   **mount namespace mode to Global or Requester** (not Isolated): the guest's mounts must outlive one
   `su` call.
4. Without root the Android tools still work; start the privileged helper once per boot with the adb
   command shown under **Settings → Privileged helper**.

## Driving the app from a computer (debug builds)

The debug build can serve its session API over a loopback socket, so a script or test can do what the
UI does:

```bash
adb shell am broadcast -a app.umbra.agent.dev.BRIDGE_START --es token <16+ chars> --ei port 47000 app.umbra.agent
adb forward tcp:47000 tcp:47000
```

`BridgeClient.control("session.create", …)` (see `tools/…/ToolBridge.kt` and `app/src/debug/…/DebugControl.kt`)
creates sessions, answers approvals and reads timelines; `scripts/umbractl.py` is the command-line face
of the same channel and `scripts/ondevice-verify.sh` builds on it.

The scripts that drive a whole run (they need a debug build set up with `scripts/dev-device.sh setup`):

| Script | What it does |
| --- | --- |
| `ondevice-verify.sh` | install, set up, self-test, confinement check; `--agent` and `--acceptance` add runs with a real model |
| `ondevice-scripted-agent.py` | a full agent task with a scripted model: every kind of tool once |
| `ondevice-scenario.py approvals` | pause/resume from the notification, approvals from the app, the notification and the floating card, questions |
| `ondevice-scripted-gui.py` | the agent operates a program on the Linux desktop (needs `ondevice-install-gui.sh` once) |
| `ondevice-scripted-acceptance.py` | the §33 loop on `fixtures/crashy-login` (needs `ondevice-install-android-toolchain.sh` once) |
| `ondevice-confinement.py` | what the agent in the Linux environment can and cannot reach |

### On the emulator

The emulator image lets only the adb shell call `su`, not apps. The app then gets root through a
second helper that runs as uid 0, which the scripts start for it:

```bash
scripts/dev-device.sh install && scripts/dev-device.sh grant
scripts/dev-device.sh helper        # the shell-user helper: background displays, input
scripts/dev-device.sh roothelper    # the root helper: mounts, the Linux environment, the agent's process
scripts/umbractl.py setup           # start the control channel
scripts/dev-device.sh rootfs        # install the Linux environment through the app, as the Set up screen does
```

On a rooted phone none of that is needed: the app calls `su` itself. The scripts that need root on the device
(`dev-device.sh`, `ondevice-verify.sh`, the acceptance scripts) call it through `scripts/lib-root.sh` /
`umbractl.root()`, which try `su 0 sh -c`, then `su -c`, then `su root -c` and keep the first that answers uid 0
(the first form is the emulator's, the other two are Magisk's and KernelSU's; only the first was exercised).

### A whole agent run without an API key

`scripts/ondevice-scripted-agent.py` runs a real task through the app on the device: the real session,
the real omp in the Linux environment, the real tools and approvals. Only the language model is
replaced, by a scripted endpoint on your computer (`scripts/scripted_model.py`) that answers with a fixed
sequence of tool calls; the script then checks what each tool really did on the device. With a real key
use `scripts/ondevice-verify.sh --agent` or `--acceptance` instead (see the on-device test plan).

## Decisions

Architecture decisions are in `docs/adr/`: runtime topology, targetSdk 28, the root-first Linux
runtime, the privileged helper, the Laya sidecar, the file-based session store, the X11 companion.
