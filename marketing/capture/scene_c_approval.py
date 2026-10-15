#!/usr/bin/env python3
"""Scene C: a risky action waits in the Inbox; the person denies it; the phone is untouched."""
import time
from lib import *

b = umbractl.Bridge(); umbractl.setup()
m = ScriptedModel([
    paced(tool("todo", {"op": "init", "list": [{"phase": "Work", "items": ["Inspect the phone", "Remove the SIM toolkit", "Verify the result"]}]}), 1),
    tool("android_shell", {"command": "pm uninstall --user 0 com.android.stk"}),
    say("I stopped: the removal was denied, so the SIM toolkit is still installed and nothing else was changed."),
]).start()
b.control("provider.set", {"id": "scripted", "base_url": m.url_for_emulator, "model": "scripted-model", "key": ""})
b.control("approval_mode", {"mode": "balanced"}); b.control("capsule.set", {"on": False})
page("home"); time.sleep(2)
sid = None
for _ in range(60):
    try:
        sid = b.control("session.create", {"request": "Tidy up the phone's preinstalled apps"})["id"]; break
    except RuntimeError as e:
        print(e); time.sleep(15)
for _ in range(60):
    if b.control("approvals.list"): break
    time.sleep(1)
page("home"); time.sleep(2)
rec = Recorder("scene_c").start()
rec.mark("home")
time.sleep(1.5)
tap(636, 1460)           # Inbox tab
time.sleep(3.5)
rec.mark("card")
tap(120, 912)            # Deny
rec.mark("denied")
time.sleep(1.5)
page("current"); time.sleep(5)
rec.mark("answer")
rec.stop()
m.stop()
print(b.control("session.get", {"id": sid})["state"], sh("pm", "list", "packages", "com.android.stk").strip())
