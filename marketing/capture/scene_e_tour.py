#!/usr/bin/env python3
"""Scene E: the Power Studio tour, two recordings with a mark per chapter. Needs scene_e_session.py running (a live
session with a plan, and a terminal opened in it), and umbra-node on this Mac waiting to pair:

    node-runner/build/install/umbra-node/bin/umbra-node serve --pair --port 47100 > NODE_LOG &
    python3 marketing/capture/scene_e_tour.py SESSION_ID NODE_LOG     (writes raw/scene_e_0.mp4: plan, resources, resume; raw/scene_e2_0.mp4: nodes .. access)

What it changes: Pause/Resume of that session; it pairs the Mac as a node (address 10.0.2.2, the code umbra-node
printed), raises its trust, runs `uname -a` there, and anchors the evidence digest on it. Controls are found by their
labels where the layout moves; the fixed ones (tabs, the session menu) by position."""
import re
import sys
import time
from lib import *

SID = sys.argv[1]
NODE_LOG = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != "-" else None
PARTS = sys.argv[3] if len(sys.argv) > 3 else "12"      # "1": plan .. resume only; "2": nodes .. access only
CODE = re.search(r"Pairing code: ([A-Z0-9-]+)", open(NODE_LOG).read()).group(1) if NODE_LOG else None


def sweep(n, dist=640, ms=1100, wait=1.7, y0=1300):
    for _ in range(n):
        swipe(360, y0, 360, y0 - dist, ms)
        time.sleep(wait)


def up(n=3, dist=640):
    for _ in range(n):
        swipe(360, 500, 360, 500 + dist, 150); time.sleep(0.2)


def go(route, wait=2.2):
    page(route); time.sleep(wait)


def session(wait=2.5):
    sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", SID); time.sleep(wait)


# ---- before the camera: a fresh activity (every page at its top, every Details closed: pages keep their scroll
# and disclosures while the app runs), and the Planning page showing this session
sh("input", "keyevent", "3"); time.sleep(1.5)
sh("am", "start", "-f", "0x10008000", "-n", "app.umbra.agent/.MainActivity", "--es", "page", "home"); time.sleep(3)
go("planning", 2.5)
if not find("Prepare the 0.2.0", timeout=1, below=380):
    tap(330, 454); time.sleep(1.5)
    tap_text("Prepare the 0.2.0"); time.sleep(1.5)
tap(120, 598); time.sleep(1.0)                       # Progress first (the page keeps the tab it was left on)
session(2.5); tap(359, 527)                          # the terminal connects once now, not on camera
find("agent@localhost", timeout=12); tap(147, 527); time.sleep(1.0)
go("home", 2)


def part1():
    """Plan, capacity, resume: raw/scene_e_0.mp4. (Two recordings: no chapter straddles screenrecord's 170 s segments.)"""
    rec = Recorder("scene_e").start()
    # ---- plan: progress, the phase DAG, helpers (agent tree), waits, the revision history
    rec.mark("plan"); go("planning", 2.6)
    tap(279, 598); time.sleep(2.6)                       # Phases
    sweep(1, 460, 1000, 2.0); up(2, 640); time.sleep(0.8)      # back to the very top: the tabs are tapped by position
    tap(439, 598); time.sleep(2.4)                       # Helpers
    tap(599, 598); time.sleep(1.6)                       # Waits
    tap_text("Details", exact=True); time.sleep(2.6)    # plan changes: the read-only revision history
    tap(120, 598); time.sleep(1.2)                       # back to Progress

    # ---- capacity: admission and budget, runtime evidence, shared services and their health checks
    rec.mark("resources"); go("resources", 2.2); sweep(3, 640, 1200, 1.9)
    go("runtime", 2.2); sweep(3, 640, 1200, 1.9)
    go("services", 2.2); tap_text("Details", exact=True); time.sleep(1.6); sweep(2, 640, 1200, 1.9)

    # ---- resume: the tmux terminal, the resume contract, pause and resume, the sessions list
    rec.mark("resume"); session(2.6)
    tap(359, 527); find("agent@localhost", timeout=10); time.sleep(2.2)   # Terminal: a tmux pane in the Linux environment
    tap(147, 527); time.sleep(1.4)                       # Chat
    tap(648, 342); time.sleep(1.5); tap(354, 538); time.sleep(3.2)   # ⋯ → Keep going after a restart…
    tap_text("Cancel", exact=True); time.sleep(1.0)
    tap(648, 342); time.sleep(1.5); tap(214, 716); time.sleep(3.2)   # ⋯ → Pause
    tap(648, 342); time.sleep(1.5); tap_text("Resume", exact=True); time.sleep(3.2)
    tap(268, 1512); time.sleep(2.8)                      # Sessions
    swipe(360, 1250, 360, 900, 900); time.sleep(1.5)

    rec.mark("end"); time.sleep(1.0)
    sh("input", "keyevent", "3")             # end the still on a change, so the recording keeps it
    time.sleep(1.2)
    rec.stop()


def part2():
    """Nodes, vault & commit, evidence, set up & access: raw/scene_e2_0.mp4."""
    rec = Recorder("scene_e2").start()
    # ---- nodes: pair the Mac with its one-time code, preflight, raise trust, run a job under a lease
    rec.mark("nodes"); go("nodes", 2.4)
    if not find("Pair a node", timeout=1, exact=True):
        tap_text("Pair a computer"); time.sleep(1.2)
    tap_text("Pair a node", exact=True); time.sleep(1.4)
    tap(360, 731); time.sleep(1.0); text("10.0.2.2"); time.sleep(0.6)            # Address
    tap(360, 740); time.sleep(0.6); text(CODE); time.sleep(0.8)                  # Pairing code (dialog moved up)
    sh("input", "keyevent", "4"); time.sleep(1.4)                               # keyboard away: the whole dialog
    tap(574, 1167); time.sleep(4.0)                                              # Pair
    tap_text("Preflight", exact=True); time.sleep(3.5)
    tap_text("Owned", exact=True); time.sleep(1.6)                     # raise its trust
    tap_text("Run…", exact=True); time.sleep(1.6)
    tap_text("Place job", exact=True); time.sleep(4.5)
    sweep(1, 640, 1200, 2.4)

    # ---- vault & commit
    rec.mark("vault"); go("vault", 2.2); sweep(4, 640, 1200, 1.9)
    go("commit", 2.4); tap_text("Details", exact=True); time.sleep(2.4)

    # ---- evidence: journals intact, the digest anchored on the paired Mac and verified; emergency controls
    rec.mark("evidence"); go("security", 2.2)
    tap_text("Details", exact=True); time.sleep(1.8)
    sweep(2, 640, 1200, 1.6)
    priv = find("PRIVACY SETTINGS")
    tap_text("Details", exact=True, below=priv[1] if priv else 0); time.sleep(1.6)
    sweep(1, 520, 1000, 1.4)
    tap_text("Anchor", exact=True); time.sleep(3.0)
    tap_text("Verify", exact=True); time.sleep(3.0)
    up(4); time.sleep(2.4)                               # the result, at the top
    go("emergency", 2.2); sweep(2, 640, 1200, 1.9)

    # ---- set up & access
    rec.mark("access"); go("linux", 2.4)
    tap_text("Details", exact=True); time.sleep(1.8); sweep(2, 640, 1200, 1.9)
    go("settings", 2.2); sweep(4, 640, 1200, 1.9)
    rec.mark("end"); time.sleep(1.5)
    sh("input", "keyevent", "3")
    time.sleep(1.5)
    rec.stop()


if "1" in PARTS:
    part1()
if "2" in PARTS:
    part2()
