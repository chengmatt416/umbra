#!/usr/bin/env python3
"""Scene E: the Power Studio tour, one recording with a mark per chapter. Needs scene_e_session.py running (a live session
with a plan). Nothing here taps anything that changes state, except Pause/Resume of that session."""
import sys
import time
from lib import *

SID = sys.argv[1]
rec = Recorder("scene_e")


def sweep(n, dist=640, ms=1100, wait=1.7, y0=1300):
    for _ in range(n):
        swipe(360, y0, 360, y0 - dist, ms)
        time.sleep(wait)


def up(n=3, dist=640):
    for _ in range(n):
        swipe(360, 500, 360, 500 + dist, 150); time.sleep(0.2)


def go(route, wait=2.2):
    page(route); time.sleep(wait)


def menu(label_y):
    tap(648, 369); time.sleep(1.4)
    if label_y:
        tap(520, label_y); time.sleep(1.2)


sh("input", "keyevent", "3"); time.sleep(1.5)
go("home", 2)
rec.start()
# plan
rec.mark("plan"); go("planning", 2.0); tap(170, 410); time.sleep(3.2)
tap(335, 756); time.sleep(2.6); tap(500, 756); time.sleep(2.4); tap(150, 756); time.sleep(1.2)
sweep(1, 520, 1000, 2.0); up(1, 520); time.sleep(0.8)
# capacity
rec.mark("resources"); go("resources", 2.2); sweep(3, 640, 1200, 1.9)
go("runtime", 2.2); sweep(3, 640, 1200, 1.9)
go("services", 2.2); sweep(2, 640, 1200, 1.9)
# resume
rec.mark("resume"); sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", SID); time.sleep(2.5)
tap(220, 605); time.sleep(2.6)             # Terminal (tmux)
tap(93, 605); time.sleep(1.2)               # Chat
tap(648, 369); time.sleep(1.5); tap(540, 565); time.sleep(3.0)   # Resume contract…
sh("input", "keyevent", "4"); time.sleep(1.0)
tap(648, 369); time.sleep(1.4); tap(476, 757); time.sleep(3.0)   # Pause
tap(648, 369); time.sleep(1.8); tap(520, 757); time.sleep(3.0)    # Resume
tap(270, 1460); time.sleep(3.0)                                    # Sessions
swipe(360, 1250, 360, 900, 900); time.sleep(1.5)
# nodes
rec.mark("nodes"); go("nodes", 2.4); sweep(3, 640, 1200, 2.0)
# vault & commit
rec.mark("vault"); go("vault", 2.2); sweep(3, 640, 1200, 1.9)
go("commit", 2.2); sweep(3, 640, 1200, 1.9)
# evidence & emergency
rec.mark("evidence"); go("security", 2.2); sweep(3, 640, 1200, 1.9)
go("emergency", 2.2); sweep(2, 640, 1200, 1.9)
# set up & access
rec.mark("access"); go("setup", 2.2); sweep(2, 640, 1200, 1.9)
go("linux", 2.2); sweep(2, 640, 1200, 1.9)
go("settings", 2.2); sweep(4, 640, 1200, 1.9)
rec.mark("end"); time.sleep(1.5)
sh("input", "keyevent", "3")
rec.stop()
