#!/usr/bin/env python3
"""Scene C: a risky action waits on its task's row on Home; the person reads it and denies it there; the phone is
untouched and the task says so."""
import time
from lib import *

b = umbractl.Bridge(); umbractl.setup()
# No todo list here: with items left open, omp asks the model once more at the end, and the scripted model (which
# cannot tell) would repeat its last answer in the chat.
m = ScriptedModel([
    paced(tool("android_shell", {"command": "pm uninstall --user 0 com.android.stk"}), 1),
    say("I stopped: the removal was denied, so the SIM toolkit is still installed and nothing else was changed."),
]).start()
b.control("provider.set", {"id": "scripted", "base_url": m.url_for_emulator, "model": "scripted-model", "key": ""})
b.control("approval_mode", {"mode": "balanced"}); b.control("capsule.set", {"on": False})
ui_set(b, mode="simple", onboarded=True)
fresh_app("home", 2)
sid = None
for _ in range(60):
    try:
        sid = b.control("session.create", {"request": "Tidy up the phone's preinstalled apps"})["id"]; break
    except RuntimeError as e:
        print(e); time.sleep(15)
for _ in range(60):
    if b.control("approvals.list"): break
    time.sleep(1)
fresh_app("home", 2.5)
rec = Recorder("scene_c").start()
rec.mark("home")
time.sleep(3.5)
rec.mark("row")
tap_text("Deny", exact=True)
rec.mark("denied")
time.sleep(3.0)
sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", sid)   # the task itself: it stopped
time.sleep(2.0)
for _ in range(2):
    swipe(360, 1150, 360, 520, 600); time.sleep(0.7)
time.sleep(4)
rec.mark("answer")
sh("input", "keyevent", "3")            # end the still on a change, so the recording keeps it
time.sleep(1.5)
rec.stop()
m.stop()
print(b.control("session.get", {"id": sid})["state"], sh("pm", "list", "packages", "com.android.stk").strip())
