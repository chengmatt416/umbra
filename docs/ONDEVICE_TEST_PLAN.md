# On-device test plan

This is what was checked, where, and what is left for your phone. Everything listed as verified ran
against real components: the real omp binary, a real Android 16 arm64 emulator (API 36.1, rootable),
a real Debian environment on it, and the real app installed on it. The one stand-in in automated runs is
the language model, a scripted OpenAI-compatible endpoint (`scripts/scripted_model.py`), because a real
model needs your API key and costs money. What the agent does with the model's answers is real.

## Verified on the emulator

### Parts, one at a time (Gradle live tests; they skip themselves without a device)

| Area | Test | What it shows |
| --- | --- | --- |
| Device backends | `DeviceBackendsLiveTest` | shell, packages, apps, processes, files, logs, screenshots and displays work on real Android 16 |
| System grants | `SystemGrantsLiveTest` | accessibility, notification access, battery exemption and the phantom-process guard are applied and read back |
| Privileged helper | `PrivilegedHelperLiveTest` | the helper runs as the shell user; creates a real virtual display hosting Settings and photographs it; a helper started by root drops to the shell user; a process with a live stdin dies with its connection; a 6 MB input streams to a command intact; a cancelled stream does not take the helper down |
| Tool pipeline (development machine) | `HostSideLiveTest` | observe, tap, type, scroll, launch, wait, verify, shell, packages, logs and displays through the real pipeline |
| Tool pipeline (in the app) | `OnDeviceLiveTest` | the same scenarios, with the app's own accessibility service and helper |
| Linux environment | `RootChrootRuntimeLiveTest` | the archive is decompressed and unpacked as root, mounted, runs as the app uid with no capabilities, reaches the internet, writes files the app can see, unmounts cleanly |
| What the agent cannot change | same (`theAgentCannotChangeTheSystemPartOfTheGuest`) | `/`, `/usr`, `/etc`, `/opt`, `setpriv`, `umbra-pack` and omp itself belong to root and cannot be written, added to or replaced by the agent; nothing outside its own places is writable by everyone; other users' processes are invisible to it (ADR 0010) |
| Files carried by the app | same (`theAppsOwnFilesAreSyncedAsRootOnlyWhenTheyChanged`) | the scripts and package lists in the APK are written into the environment as root, executable where they should be, only when their version differs (ADR 0009) |
| omp in the guest | `GuestOmpLiveTest` | Bun and omp start in the guest as the app user, speak RPC, read the config the app wrote, register host tools, and leave nothing behind |
| Desktop | `GuestGuiLiveTest` | the `gui` pack installs; a GTK program on the virtual desktop is read through AT-SPI, typed into, clicked, moved, photographed and closed |

### The whole app, installed on the emulator

| What | How | Result |
| --- | --- | --- |
| Install, grants, helpers | `scripts/dev-device.sh setup` | the app starts, the accessibility service connects, root (through the root helper, as on an image whose `su` the app cannot call) and the shell helper answer |
| Linux environment through the app | `scripts/dev-device.sh rootfs` | the 176 MB archive is decompressed by the app and streamed through the helper into `tar` as root; Debian 13, Bun, omp 18.6.1 and git ready in under three minutes |
| Linux environment from the file picker | Set up → *Choose file…* in the real system picker, by hand | the archive pushed to Downloads is chosen in Android's own picker; the Set up screen shows *Installing… N of 167 MB* and the environment is ready in under two minutes; *Remove (keeps your workspaces)* takes it away again |
| Release build | `./gradlew :app:assembleRelease`, installed with `adb install` | the release APK (not debuggable, targetSdk 28, minSdk 36) installs, starts on the home screen with the set-up banner and has no development hooks; no crash in the log |
| Self-test | Diagnostics screen / `selftest.run` | shell, screen read, screenshot, launching Settings, Linux, internet, log monitor pass; the model step is skipped without a key |
| A background display | `android_display` / `android_launch display=N` / `android_observe` / `android_screenshot` | a display is created, Settings runs on it, its accessibility tree and a picture are read, it is destroyed |
| A whole agent run | `scripts/ondevice-scripted-agent.py` | a task runs end to end: omp starts in the guest as the app user, calls the Android and Linux tools, the timeline records every call, the task completes and no process is left behind |
| Notification | `scripts/ondevice-scenario.py approvals` | the persistent notification shows the task with Pause and Stop; Pause stops the running command and the notification turns into Paused with Resume and Stop; Resume continues |
| Approvals | same | a risky command (uninstalling a system package) is classified high risk and waits; the approval appears in the app, as a notification with Deny/Approve, and in the floating card; denying from the notification or the card leaves the package installed and tells the agent not to retry |
| Questions | same | the agent's question appears as a card in the app; the answer reaches the agent |
| Desktop apps | `scripts/ondevice-scripted-gui.py` | the agent starts a text editor on the Linux desktop, reads its accessibility tree, types into it, photographs it and closes it, all through `gui_*` tools |
| Android app builds, the §33 loop | `scripts/ondevice-install-android-toolchain.sh`, `scripts/ondevice-scripted-acceptance.py` | the *Android app builds* pack installs (Debian's `aapt2`, `zipalign`, `apksigner`, Java 21; `d8` and the API 34 platform from Google, checksums pinned); the crashy-login project is built in Linux in 5 seconds, installed from the workspace, operated in a background display until the log monitor reports the crash, the stack trace is read with `android_logs`, the source fixed, rebuilt, reinstalled, and signing in shows the welcome message |
| Capsule and Snapshot selection | `scripts/ondevice-capsule.py` | the capsule wraps the punch-hole camera (anchored on the hole, not the screen's centre); at rest a tap opens the composer without the keyboard; over Settings (which hides ordinary overlays) a long press lassoes two entries, Done returns to Settings with the chip, the field opens the keyboard, and the scripted model receives the crop as a JPEG and the four entries' text; an approval opens the capsule by itself with Previous / Current / Next from omp's todo plan and is denied from it; no crash |
| Shared Services | the screen, by hand with the bridge | L1–L4 of the Linux environment, both helpers, the tmux server, the X desktop and Laya; the helper recovered by the supervisor through the gate (epoch 1); draining Linux refuses a new session and a new terminal without leaving a failed session; admission reopened |
| Credential Vault | the screen and `session.create` | the keystore's real security level (software on the emulator); every key hand-over recorded per session in an intact chain; "not for agents" refuses a session with a clear reason and records nothing; "its provider only" hands the key to that provider's session |
| Commit & Integration | the screen, with a bare repository in the guest | a promotion validated with its evidence, authorized, written with a lease and read back; another refused because a second writer moved the branch, whose commit stays |
| Distributed Nodes | the screen against `umbra-node` on the development Mac (10.0.2.2) | paired with the one-time code (both certificates pinned, the phone's key in the Android Keystore); preflight reports macOS, CPUs, memory and tools; a job sends two files by manifest and returns its output; forgetting it deletes its folder on the node |
| Repository Hub | the screen | a workspace checkout registered (fast-forward only by default) and preflighted (branch, clean tree, remote answering, size); a repository cloned from a bare remote into `/workspace/repos` and registered with its origin |
| Security & Evidence | the screen, `umbra-node` on the Mac | 9 journals intact and no stored secret value in any; the digest anchored on the node and verified against its copy; an L2 git incident recorded and triaged (version 2) with its manual steps; the evidence exported through Android's file picker and decrypted on the Mac with `umbra-node evidence-decrypt` (a wrong passphrase refused) |
| Taking access back | Settings → *Access and data*, by hand with screenshots | *Stop all tasks*, *Delete all history* (with its confirmation; Cancel keeps everything, Delete empties the list, workspaces stay), *Turn off screen control* (the accessibility setting is removed) and *Revoke helpers* (both helper processes exit and the token is replaced) all do what they say |
| What the agent cannot reach | `scripts/ondevice-confinement.py` | as the agent, inside the environment: the system part cannot be changed, the app's process is not listed and its `root`, `environ`, `maps`, `mem` and `fd` cannot be read, no process of the agent's uid leads to the phone's files, other users' processes (and the helpers' command lines) are invisible |
| Recovery | `scripts/ondevice-recovery.py all` | each broken for real while a task runs: the privileged helper is killed (the app notices, starts it again, and the next step that needs it works); the agent process is killed with -9 (restarted, told, the task completes); the whole app is force-stopped (on the next start the task is paused with the reason, nothing is left running in Linux, and resuming completes it; the accessibility service comes back by itself) |
| Memory pressure | `AgentSessionTest` (host) and the scripted runs | a finished task's idle agent process is released when the phone is short of memory, so a new task can start; the task stays and a follow-up starts the agent again from the saved conversation |
| Floating controls | same | the bubble shows while a task runs and expands into a card with the task, the pending decision and Message, Open, Pause, Stop buttons |

## Verified on the host with the real omp binary

`OmpIntegrationTest` (protocol: handshake, host tools, approvals, abort, deny patterns) and
`AgentSessionTest` (the orchestrator): a task completes and is recorded; follow-ups continue the same
conversation; harmless commands run without asking and risky ones wait for the user; denial stops a
command; the agent can never stop its own app; secrets typed by the agent never reach the timeline; the
agent can ask the user questions; pause, resume and stop work and leave no process behind; a killed agent
process is restarted and the task continues; a model error fails the session with the reason; sessions
left running by a previous run are paused, not lost.

## What only a real phone shows

Run these on your phone after installing; each is a few minutes.

1. **Root and namespace.** Setup shows root green. If the Linux environment's mounts disappear between
   steps, set Magisk's *Mount Namespace Mode* to Global or Requester.
2. **Linux environment.** Setup → install (file or URL), then Diagnostics → *Start the Linux environment*
   and *Reach the internet from Linux* pass.
3. **Screen control.** Diagnostics → *Read the screen*, *Take a screenshot*, *Open an app*.
4. **Background displays.** Start the helper (Setup), Diagnostics → *Work in a background display*; then
   run a task and watch it in Settings → Displays while you use the phone. The helper keeps the groups
   your root manager gives it (the emulator hands over the adb shell's); if logs or `ps` come back empty,
   that is the first thing to look at (the hello reply of the helper lists its groups).
5. **A real task.** Add your model key (Settings → Models), then *New task*:
   "Open Settings, find the Battery page and tell me the battery percentage."
6. **Approvals.** Ask for something risky ("uninstall the Calculator app"): the approval appears in
   the app, as a notification and in the floating control; Deny it and check nothing happened.
7. **Overlay and notification.** Settings → Floating controls (grant draw-over-apps) and watch a running
   task: pause, resume and stop from the notification and the bubble.
8. **Shizuku** (only if you have no root): start Shizuku, Setup → Shizuku → Allow, then start the helper.
9. **Thermals and battery.** Run a long task and check the phone stays within limits; Umbra pauses new
   work under pressure.
10. **Taking access back.** Settings → *Access and data*: *Stop all tasks*, *Revoke helpers* (the shell
    and root helpers exit and are forgotten), *Turn off screen control*, *Delete all history*. Then check
    in your root manager that Umbra can still be revoked there.
11. **Android builds on the phone.** Set up → *Android app builds* → Install (about 400 MB), then give the agent
    a real project. The pack builds plain `javac` + `aapt2` + `d8` projects (`fixtures/crashy-login` is one). A
    Gradle project needs `-Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2` and an SDK layout around
    `/opt/android-sdk`; that was not tried.
12. **Acceptance (§33).** With the same key set in your environment:

```bash
UMBRA_VERIFY_PROVIDER=anthropic UMBRA_VERIFY_KEY=… UMBRA_VERIFY_MODEL=claude-sonnet-5-5 \
  scripts/ondevice-verify.sh --acceptance
```

It installs the app and the Linux environment, runs the diagnostics, then gives the agent the
`crashy-login` project: find the crash from the log, fix it, rebuild, reinstall, confirm. The result is
then checked independently of what the agent says (rebuild from the final source, sign in through the
real UI, search the log for crashes).

## Known limits

* The model in automated runs is scripted; real-model behaviour on the §33 task is for you to see.
* The agent shares the app's uid, so it is not a hard sandbox against a hostile agent (ADR 0010 lists what is
  closed and what is not; Android's kernels have no PID or user namespaces to close the rest with).
* The Android build pack links against the API 34 platform because Debian's `aapt2` is Android 14's and cannot
  read a newer `android.jar`.
* Shizuku, Laya's own server and a multi-hour thermal run were not exercised without a phone with them.
* Magisk's Isolated mount namespace mode is not supported.
* The helper scripts try three ways of calling `su` from adb (emulator image, Magisk, KernelSU); only the emulator's
  was run. If `scripts/ondevice-verify.sh` says the device gives no root to adb, enable *Superuser access: Apps and
  ADB* in Magisk, or skip the script and use the app's own screens (everything above can be done by hand).
* Linux GUI is a virtual desktop in the guest; the optional Termux:X11 companion view is not built.
* On the emulator, install the debug APK through `scripts/dev-device.sh install`: it pre-verifies the
  code, which an emulator does not do at install time (without it the first launch takes minutes and the
  system reports the app as not responding). A phone does this itself.
* The 2 GB emulator is at its limit during the 600 MB unpack; a phone is not.
