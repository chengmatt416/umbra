#!/usr/bin/env python3
"""Scene A (Simple mode): say what you want on Home (the voice panel, then typed); Umbra operates the real Settings app
on the phone and answers in the task's chat."""
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
bridge.control("capsule.set", {"on": False})
ui_set(bridge, mode="simple", onboarded=True, effort="auto")
sh("pm", "grant", "app.umbra.agent", "android.permission.RECORD_AUDIO")
sh("input", "keyevent", "3")
sh("am", "force-stop", "com.android.settings")
fresh_app("home")
rec = Recorder("scene_a").start()
rec.mark("home")
time.sleep(1.8)
tap_text("Speak", exact=True)            # the voice panel: the emulator has no microphone, so it only listens
rec.mark("voice")
time.sleep(3.6)
tap_text("Cancel", exact=True)
time.sleep(1.2)
tap_text("Message", exact=True)
time.sleep(1.0)
text("Open Settings and tell me which Android version this phone runs")
time.sleep(0.8)
sh("input", "keyevent", "4")
time.sleep(0.8)
rec.mark("typed")
tap_text("Send", exact=True)
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
time.sleep(1.0)
sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "page", "home")
time.sleep(2.5)
rec.mark("home-done")
sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", sid)
time.sleep(2.5)
for _ in range(3):                      # down to the answer: the chat opens at the request
    swipe(360, 1150, 360, 520, 600); time.sleep(0.7)
time.sleep(4)
rec.mark("answer")
sh("input", "keyevent", "3")            # screenrecord writes a frame only when the screen changes: end the still on a change
time.sleep(1.5)
rec.stop()
model.stop()
print("session", sid)
