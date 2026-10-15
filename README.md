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
  you answered is kept in a history. Settings → *Stop and take back* takes access back (stop everything,
  revoke the helpers, turn screen control off, delete the history); each of these says what it ends before it
  does it. The Linux side runs without
  privileges and cannot change its own system files or read the app's private data; the limits of that
  are in [ADR 0010](docs/adr/0010-what-the-agent-can-reach.md).
* **Resumable.** Tasks are persistent sessions: pause, resume, stop, follow up, restart after the app or
  the agent crashes; terminals live in tmux and survive the app; a session may resume by itself after a
  restart only under a resume contract you gave it.
* **Porcelain in Motion.** A camera-anchored Capsule over any app (the task, approvals, a composer, and
  Snapshot selection: circle part of the screen and send it with its text), and the Power Studio:
  planning and agents, resources (requests beyond what the phone can run wait in a fair, visible queue),
  runners, shared services, terminals, emergency lockdown, a credential vault (git tokens used only by root
  for one repository, never given to an agent; a backup sealed under a recovery code), a commit gate that
  publishes only what you authorize and reads it back, security evidence with independent anchors, and your
  own computers as execution nodes ([`umbra-node`](#your-computers-as-nodes)), which you can revoke.

* **English and 繁體中文.** The whole interface is in both; it follows the phone's language, or pick one in
  Android's per-app language settings.

* **Updates itself.** Twice a day it reads `update.json` on [umbra-agent.pages.dev](https://umbra-agent.pages.dev),
  downloads a newer APK from the site's Cloudflare R2 bucket (resumable; Wi-Fi only unless you check by hand),
  checks its size, SHA-256, package, version and that it is signed with the same key, and installs it when no task
  is running: silently through the helper or root, otherwise through Android's installer, which asks you once.
  Settings → *About* shows the state and has the switches ([`AppUpdater`](app/src/main/kotlin/app/umbra/agent/update/AppUpdater.kt)).

Free software under the MIT License. Sideload or F-Droid; there is no Play Store build.

## Requirements

| | |
| --- | --- |
| Android | **16** (API 36) |
| Root | optional. With root (Magisk/KernelSU, mount namespace *Global* or *Requester*) the Linux environment runs in a chroot kept apart from the app ([ADR 0010](docs/adr/0010-what-the-agent-can-reach.md)); without root it runs under proot ([ADR 0012](docs/adr/0012-proot-without-root.md)). The privileged helper can be started with root, Shizuku, or once per boot over `adb`. |
| Storage | about 2 GB for the Linux environment (carried inside the release APK: [ADR 0011](docs/adr/0011-linux-inside-the-apk.md)) |
| A model | an API key for a provider above |

## Get started

1. Install the APK — download it from [umbra-agent.pages.dev](https://umbra-agent.pages.dev), or build it (`./gradlew :app:assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`) — and open Umbra.
2. **Set up** walks through what it needs: the accessibility service, the Linux environment, a model.
3. Tap **New task** and say what you want. Watch it work, answer questions, approve what it asks.

The Linux environment is a Debian archive built by `runtime-build/rootfs/build.sh` and carried inside the
release APK, so setting it up needs no network; a file or a URL also work. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Your computers as nodes

`umbra-node` runs jobs for the phone on a computer you own (macOS or Linux, Java 21):

```bash
./gradlew :node-runner:installDist
node-runner/build/install/umbra-node/bin/umbra-node serve --pair
```

It prints a one-time code; on the phone, **Distributed Nodes → Pair a node** with the computer's address and the code.
Both sides pin each other's certificate; a new node gets no work until you raise its trust. A paired phone can run
commands as that computer's user in the node's jobs folder, only with the files a job's manifest lists, each job
for no longer than its lease ([ADR 0018](docs/adr/0018-distributed-nodes.md)). `umbra-node phones` and
`umbra-node unpair <prefix>` manage the phones it trusts.

## What is verified

Every part is tested against the real thing: the real omp binary, a real Android 16 emulator, a real
Debian environment on it. The one stand-in is the language model in automated runs (a scripted
endpoint), since a real model needs your key. [docs/ONDEVICE_TEST_PLAN.md](docs/ONDEVICE_TEST_PLAN.md) lists what was
verified where, what only a real phone can show, and how to run the end-to-end acceptance task on yours.
[docs/traceability.md](docs/traceability.md) maps each requirement to code and tests, including the architecture
spec's 22 acceptance criteria with their status (19 met; AC-007 partly and AC-008/009 not built: they need an
Execution Halt controller this build does not have).

## Building

```bash
source scripts/env.sh
scripts/verify.sh                          # build, unit tests, lint
runtime-build/rootfs/build.sh dev          # the Linux environment archive (needs Docker)
```

More in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); design decisions in [docs/adr](docs/adr).
