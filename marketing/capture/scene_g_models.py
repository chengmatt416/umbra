#!/usr/bin/env python3
"""Scene G (Simple mode): what the next task takes. The model chip opens the model list (Auto, each account's models,
Other model…); the effort chip opens the levels (Auto lets Laya decide); the menu's Accounts and models page shows the
sign-ins and keys. Nothing is changed: Auto stays Auto."""
import time
from lib import *

b = umbractl.Bridge(); umbractl.setup()
ui_set(b, mode="simple", onboarded=True, effort="auto")
b.control("capsule.set", {"on": False})
sh("input", "keyevent", "3")
fresh_app("home", 3)

rec = Recorder("scene_g").start()
rec.mark("home"); time.sleep(2.0)
tap_text("Auto", exact=True, below=1300); rec.mark("models"); time.sleep(2.6)      # the model chip
swipe(360, 1300, 360, 700, 900); time.sleep(2.2)
swipe(360, 700, 360, 1300, 700); time.sleep(1.4)
sh("input", "keyevent", "4"); time.sleep(1.4)
tap_text("Effort", below=1300); rec.mark("effort"); time.sleep(3.0)                # the effort chip
swipe(360, 1350, 360, 900, 900); time.sleep(2.2)
tap_text("Auto", exact=True); time.sleep(1.6)                                      # keep Auto
rec.mark("accounts"); menu("Accounts and models", 1.2); time.sleep(2.6)
swipe(360, 1250, 360, 600, 1100); time.sleep(2.2)
swipe(360, 1250, 360, 600, 1100); time.sleep(2.4)
rec.mark("end")
sh("input", "keyevent", "3")             # end the still on a change, so the recording keeps it
time.sleep(1.5)
rec.stop()
print(ui_set(b))
