# ADR 0007 — Termux:X11 as an unmodified companion app, Xvfb as headless fallback

Status: accepted · Spec: §12, §13

## Context

Termux:X11 is GPLv3 and builds a native X server (NDK + meson). Its X server can be started
without the Termux app via `app_process / com.termux.x11.CmdEntryPoint :0`, and X clients connect
through `/tmp/.X11-unix`. A forked applicationId cannot share Termux's `sharedUserId`, but the
non-shared variant works across uids.

## Decision

* Use the upstream Termux:X11 APK as a **companion** (installed separately), started through
  `CmdEntryPoint`, with the guest's `/tmp/.X11-unix` bind-mounted (chroot) or shared (proot).
* When it is not installed, or for agent-only work, run **Xvfb** inside the guest. Screenshots use
  the X server (not the display app), so computer-use works in both modes.
* Linux GUI control is semantic first (AT-SPI via a guest helper) with xdotool for input and
  screenshots as the visual fallback.

## Consequences

* No second native build or GPL code embedded in the app; updates track upstream.
* The interactive desktop view lives in the companion app; the in-app Linux-desktop screen shows
  a live screenshot preview and launch/stop controls.
