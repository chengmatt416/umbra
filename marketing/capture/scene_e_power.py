#!/usr/bin/env python3
"""Scene E (the redesign): Power and Dev mode around a live session (scene_e_session.py running in the background).

    python3 marketing/capture/scene_e_power.py SESSION_ID     -> raw/scene_e_0.mp4, marks: power, resume, dev

power: Home in Power (filters, rows with the model), the menu button, the task's four views (chat, steps, terminal,
changes), Workspaces. resume: the session menu (keep going after a restart, pause, resume). dev: Dev mode, the
Developer hub, Planning & Agents, the Resource Manager. Controls are found by their labels."""
import sys
import time
from lib import *

SID = sys.argv[1]
b = umbractl.Bridge(); umbractl.setup()
ui_set(b, mode="power", onboarded=True, effort="auto", homeAgents=False)
b.control("capsule.set", {"on": False})


def session(wait=2.4):
    sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", SID); time.sleep(wait)


def sweep(n, dist=600, ms=1100, wait=1.6):
    for _ in range(n):
        swipe(360, 1250, 360, 1250 - dist, ms); time.sleep(wait)


def actions(label, wait=2.6):
    tap_text("Session actions", exact=True); time.sleep(1.2)
    tap_text(label, exact=True); time.sleep(wait)


# ---- before the camera: the terminal connects once, every page starts at its top
sh("input", "keyevent", "3"); time.sleep(1)
fresh_app("home", 3)
session(2.5); tap_text("Terminal", exact=True); time.sleep(1.5)
if find("New terminal", timeout=2):
    tap_text("New terminal"); time.sleep(1.5)
print("terminal", find("agent@", timeout=20)); time.sleep(1); tap_text("Chat", exact=True); time.sleep(1)
fresh_app("home", 3)

rec = Recorder("scene_e").start()
# ---- power
rec.mark("power"); time.sleep(2.4)
tap_text("Open the menu", exact=True); time.sleep(2.6)
tap_text("Close the menu", exact=True); time.sleep(1.0)
tap_text("Prepare the 0.6.0"); time.sleep(2.6)
tap_text("Steps", exact=True); time.sleep(2.8)
tap_text("Terminal", exact=True); time.sleep(3.2)
tap_text("Changes", exact=True); time.sleep(2.4)
tap_text("Chat", exact=True); time.sleep(1.6)
sh("input", "keyevent", "4"); time.sleep(1.2)
menu("Workspaces", 1.2); time.sleep(2.6); sweep(1)

# ---- resume
rec.mark("resume"); session(2.6)
actions("Keep going after a restart…", 3.4)
tap_text("Cancel", exact=True); time.sleep(1.0)
tap_text("Pause", exact=True); time.sleep(1.4)              # the composer's Pause: the task stops at a safe point
tap_text("Pause the task (resume later)", exact=True); time.sleep(3.2)
actions("Resume", 3.4)

# ---- dev
rec.mark("dev"); ui_set(b, mode="dev"); sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "page", "home"); time.sleep(2.0)
menu("Developer", 1.2); time.sleep(2.4); sweep(1, 500)
tap_text("Planning"); time.sleep(2.8)
p = find("Phases", timeout=2, exact=True)
if p: tap(*p); time.sleep(2.6)
sh("input", "keyevent", "4"); time.sleep(1.4)
tap_text("Resource Manager"); time.sleep(2.6); sweep(2)
rec.mark("end")
sh("input", "keyevent", "3")             # end the still on a change, so the recording keeps it
time.sleep(1.5)
rec.stop()
ui_set(b, mode="power")
