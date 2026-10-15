#!/usr/bin/env python3
"""Scene A: say what you want on Home; Umbra operates the real Settings app on the phone and answers."""
import time
from lib import *

bridge = umbractl.Bridge()
umbractl.setup()
SECONDS = 3.2
model = ScriptedModel([
    paced(tool("android_observe", {}), SECONDS),
    paced(tool("android_launch", {"package": "com.android.settings"}), SECONDS),
    paced(tool("android_scroll", {"direction": "down", "amount": 3}), SECONDS),
    paced(tool("android_scroll", {"direction": "down", "amount": 3}), 1.5),
    paced(tool("android_tap", {"label": "About emulated device"}), SECONDS),
    paced(tool("android_observe", {}), SECONDS),
    paced(say("This phone runs Android 16 (API level 36) on an emulated arm64 device. I opened Settings → About, read the version, and left everything as I found it."), 2.0),
]).start()
bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
bridge.control("approval_mode", {"mode": "autonomous"})
bridge.control("capsule.set", {"on": True})
sh("input", "keyevent", "3")
sh("am", "force-stop", "com.android.settings")
page("home")
time.sleep(3)
rec = Recorder("scene_a").start()
rec.mark("home")
time.sleep(1.5)
tap(360, 985)
time.sleep(1.2)
text("Open Settings and tell me which Android version this phone runs")
time.sleep(0.8)
sh("input", "keyevent", "4")
time.sleep(1.0)
rec.mark("typed")
tap(623, 1121)
rec.mark("sent")
sid = None
for _ in range(60):
    time.sleep(2)
    ss = bridge.control("session.list", {})
    if ss:
        newest = max(ss, key=lambda x: x["id"])
        if len(model.agent_requests) >= 7 and bridge.control("session.get", {"id": newest["id"]})["state"] in ("COMPLETED", "FAILED", "STOPPED"):
            sid = newest["id"]
            break
rec.mark("finished")
time.sleep(1.5)
sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", sid)
time.sleep(5)
rec.mark("answer")
rec.stop()
model.stop()
print("session", sid)
