#!/usr/bin/env python3
"""Scene B: the Capsule over any app, Snapshot selection (circle part of the screen), the approval peek. Runs the
project's own on-device capsule script (scripts/ondevice-capsule.py) under the screen recorder."""
import importlib.util
import os
import time
from lib import *

spec = importlib.util.spec_from_file_location("capsule", os.path.join(ROOT, "scripts", "ondevice-capsule.py"))
capsule = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capsule)
sh("input", "keyevent", "3")
time.sleep(2)
rec = Recorder("scene_b").start()
try:
    code = capsule.main()
finally:
    time.sleep(2)
    rec.stop()
print("exit", code, "problems", capsule.problems)
