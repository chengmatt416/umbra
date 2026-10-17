#!/usr/bin/env python3
"""Scene C: a risky action waits in the Inbox; the person denies it; the phone is untouched."""
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
INBOX = find("Inbox", exact=True)       # the Inbox tab, found by its label
rec = Recorder("scene_c").start()
rec.mark("home")
time.sleep(1.5)
tap(*INBOX)
time.sleep(3.5)
rec.mark("card")
tap_text("Deny", exact=True)
rec.mark("denied")
time.sleep(2.5)
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
