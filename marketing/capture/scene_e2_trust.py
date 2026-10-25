#!/usr/bin/env python3
"""Scene E2 (the redesign, Power mode): nodes, safety, set up and take back -> raw/scene_e2_0.mp4.

    node-runner/build/install/umbra-node/bin/umbra-node serve --pair --port 47100 > NODE_LOG &
    python3 marketing/capture/scene_e2_trust.py NODE_LOG

nodes: pair this Mac with the code umbra-node printed (address 10.0.2.2), preflight, raise its trust, run a job under
a lease. safety: the Safety hub (hold to stop everything, the credential vault, approval history, security and
evidence with the digest anchored on the paired Mac, emergency control). access: Set up (the Linux environment) and
Settings → Everything else → Stop and take back."""
import re
import sys
import time
from lib import *

# With a NODE_LOG argument the scene pairs a fresh umbra-node on camera; without one it shows the node already paired
# (no job is placed on that computer: only its page and the read-only preflight).
CODE = re.search(r"Pairing code: ([A-Z0-9-]+)", open(sys.argv[1]).read()).group(1) if len(sys.argv) > 1 else None
b = umbractl.Bridge(); umbractl.setup()
ui_set(b, mode="power", onboarded=True, effort="auto", homeAgents=False)
b.control("capsule.set", {"on": False})


def go(route, wait=2.2):
    page(route); time.sleep(wait)


def sweep(n, dist=640, ms=1200, wait=1.8):
    for _ in range(n):
        swipe(360, 1300, 360, 1300 - dist, ms); time.sleep(wait)


def up(n=4):
    for _ in range(n):
        swipe(360, 500, 360, 1140, 150); time.sleep(0.2)


sh("input", "keyevent", "3"); time.sleep(1)
fresh_app("home", 3)
rec = Recorder("scene_e2").start()

# ---- nodes: the menu's Nodes, pair the Mac, preflight, trust, a job under a lease
rec.mark("nodes"); menu("Nodes", 1.2); time.sleep(2.2)
if CODE:
    if not find("Pair a node", timeout=1, exact=True):
        tap_text("Pair a computer"); time.sleep(1.4)
    tap_text("Pair a node", exact=True); time.sleep(1.4)
    tap(360, 731); time.sleep(0.8); text("10.0.2.2"); time.sleep(0.6)          # Address
    sh("input", "keyevent", "4"); time.sleep(1.0)                             # keyboard away: the dialog moves back
    tap(360, 1015); time.sleep(0.8); text(CODE); time.sleep(0.8)              # Pairing code
    sh("input", "keyevent", "4"); time.sleep(1.4)                             # keyboard away: the whole dialog
    tap_text("Pair", exact=True, below=1000); time.sleep(4.0)
    tap_text("Preflight", exact=True); time.sleep(3.5)
    tap_text("Owned", exact=True); time.sleep(1.8)                           # raise its trust
    tap_text("Run…", exact=True); time.sleep(1.6)
    tap_text("Place job", exact=True); time.sleep(4.5)
else:
    time.sleep(1.0)
    p = find("MacBook", timeout=3) or find("@", timeout=2)
    if p: tap(*p); time.sleep(2.6)
    pf = find("Preflight", timeout=2, exact=True)
    if pf: tap(*pf); time.sleep(3.5)
sweep(1, 640, 1200, 2.4)

# ---- safety: the Safety hub and what it holds
rec.mark("safety"); fresh_app("home", 1.6); menu("Safety", 1.2); time.sleep(2.6)
tap_text("Credential vault"); time.sleep(2.2); sweep(2, 640, 1200, 1.6)
sh("input", "keyevent", "4"); time.sleep(1.2)
tap_text("Approval history"); time.sleep(2.4)
sh("input", "keyevent", "4"); time.sleep(1.2)
tap_text("Security events"); time.sleep(2.2)
p = find("Anchor", timeout=1, exact=True)
for _ in range(4):
    if p: break
    sweep(1, 520, 1000, 1.2); p = find("Anchor", timeout=1, exact=True)
if p:
    tap(*p); time.sleep(3.0)
    v = find("Verify", timeout=2, exact=True)
    if v: tap(*v); time.sleep(3.0)
up(); time.sleep(2.0)
sh("input", "keyevent", "4"); time.sleep(1.2)
tap_text("Emergency control"); time.sleep(2.2); sweep(2, 640, 1200, 1.6)

# ---- access: set up, and taking everything back
rec.mark("access"); go("linux", 2.4)
d = find("Details", timeout=1, exact=True)
if d: tap(*d); time.sleep(1.8)
sweep(2, 640, 1200, 1.8)
go("more_settings", 2.2)
for _ in range(6):
    if find("Stop and take back", timeout=0.5): break
    sweep(1, 640, 1200, 1.2)
time.sleep(2.6)
rec.mark("end")
sh("input", "keyevent", "3")
time.sleep(1.5)
rec.stop()
