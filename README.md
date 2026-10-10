# Umbra

An autonomous agent that lives on your Android phone. Tell it what to do; it works in the background,
operates apps for you, writes and builds code in a real Linux environment on the same device, checks
its own work, and asks before anything risky.

* **Operates the phone.** It reads the screen (accessibility tree first, screenshots when needed),
  taps, types, scrolls, opens apps, reads notifications and logs, and verifies that each action did
  what it should. Work can run in an invisible background display, so you keep using the phone.
* **A Linux machine in your pocket.** A Debian environment (bash, git, Python, Bun, and add-on toolchains
  such as C/C++, Java, Node, Android app builds and a virtual desktop) where the agent builds and tests
  software. Files live in workspaces both worlds can see.
* **Built on [omp](https://github.com/can1357/oh-my-pi)**, a coding agent with a mature tool and planning
  loop, extended here with Android, privileged and desktop tools. Models are yours: Anthropic, OpenAI,
  Google, OpenRouter, or any OpenAI-compatible server.
* **You stay in control.** Risky actions (payments, messages, deleting data, security settings,
  installing apps, passwords) always need your approval, from the app, a notification or a floating
  control. The agent can never operate its own app or answer its own approvals. Everything it asked and
  you answered is kept in a history. Settings → *Access and data* takes access back (stop everything,
  revoke the helpers, turn screen control off, delete the history). The Linux side runs without
  privileges and cannot change its own system files or read the app's private data; the limits of that
  are in [ADR 0010](docs/adr/0010-what-the-agent-can-reach.md).
* **Resumable.** Tasks are persistent sessions: pause, resume, stop, follow up, restart after the app or
  the agent crashes.

Free software, GPL-3.0-or-later. Sideload or F-Droid; there is no Play Store build.

## Requirements

| | |
| --- | --- |
| Android | **16** (API 36) |
| Root | needed for the Linux environment and the privileged helper. Magisk/KernelSU with the mount namespace mode set to *Global* or *Requester*. Without root, the Android tools still work (the helper can be started once per boot over `adb`). |
| Storage | about 2 GB for the Linux environment |
| A model | an API key for a provider above |

## Get started

1. Install the APK (`./gradlew :app:assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`) and open Umbra.
2. **Set up** walks through what it needs: the accessibility service, the Linux environment, a model.
3. Tap **New task** and say what you want. Watch it work, answer questions, approve what it asks.

The Linux environment is a Debian archive built by `runtime-build/rootfs/build.sh` (or downloaded from a
release); the app unpacks it as root and mounts it. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## What is verified

Every part is tested against the real thing: the real omp binary, a real Android 16 emulator, a real
Debian environment on it. The one stand-in is the language model in automated runs (a scripted
endpoint), since a real model needs your key. [docs/ONDEVICE_TEST_PLAN.md](docs/ONDEVICE_TEST_PLAN.md) lists what was
verified where, what only a real phone can show, and how to run the end-to-end acceptance task on yours.
[docs/traceability.md](docs/traceability.md) maps each requirement to code and tests.

## Building

```bash
source scripts/env.sh
scripts/verify.sh                          # build, unit tests, lint
runtime-build/rootfs/build.sh dev          # the Linux environment archive (needs Docker)
```

More in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); design decisions in [docs/adr](docs/adr).
