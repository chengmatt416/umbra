#!/usr/bin/env python3
"""Scene B (the redesign's Capsule): over the real Settings app, the Capsule centred on the camera opens its card in
place; Select on screen, a circle around one row, "What is this"; the task runs from the Capsule, which peeks with the
answer when it is done; the task's chat shows it. The Capsule is an overlay window: it gets real touch streams
(mtap/mdrag), not `input tap`."""
import time
from lib import *

b = umbractl.Bridge(); umbractl.setup()
model = ScriptedModel([
    paced(tool("android_observe", {}), 2.5),
    paced(say("That row is Android version: this phone runs Android 16, the newest release. It is the system version, "
              "not an app's, and Settings → About shows it."), 3.0),
]).start()
# A selection is sent as a picture with its text: the model must be one that reads pictures.
b.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": "", "images": "true"})
b.control("approval_mode", {"mode": "autonomous"})
b.control("capsule.set", {"on": False}); time.sleep(1.5)            # a fresh Capsule (no selection left open)
b.control("capsule.set", {"on": True})
ui_set(b, mode="simple", onboarded=True, effort="auto")
sh("input", "keyevent", "3"); time.sleep(1)
sh("am", "force-stop", "com.android.settings")
sh("am", "start", "-a", "android.settings.DEVICE_INFO_SETTINGS"); time.sleep(3)
CAPSULE = (520, 65)
before = {s["id"] for s in b.control("session.list", {})}

rec = Recorder("scene_b").start()
rec.mark("app"); time.sleep(2.2)
mtap(*CAPSULE); rec.mark("card"); time.sleep(3.2)          # the card opens where the Capsule is
mtap(460, 345); rec.mark("select"); time.sleep(2.4)        # Select on screen: the glow, the controls in the card
mdrag(circle(300, 1500, 250, 64), step=0.022); rec.mark("circled"); time.sleep(2.6)
p = None                                                   # overlay text is not in uiautomator dumps
mtap(*(p or (280, 166))); rec.mark("asked")
sid = None
for _ in range(60):                                        # the task runs; the Capsule shows it working, then done
    time.sleep(1)
    new = [s for s in b.control("session.list", {}) if s["id"] not in before]
    if new:
        sid = new[0]["id"]
        if b.control("session.get", {"id": sid})["state"] in ("COMPLETED", "FAILED", "STOPPED"):
            break
rec.mark("done"); time.sleep(4.5)                          # the finished task peeks in the Capsule
sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", sid); time.sleep(2.0)
for _ in range(2):
    swipe(360, 1150, 360, 520, 600); time.sleep(0.7)
time.sleep(3.5)
rec.mark("answer")
sh("input", "keyevent", "3")                               # end the still on a change, so the recording keeps it
time.sleep(1.5)
rec.stop(); model.stop()
b.control("capsule.set", {"on": False})
print("session", sid, b.control("session.get", {"id": sid})["state"])
