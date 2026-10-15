"""Helpers for recording the real app on the emulator (marketing material). Everything on screen is the real app;
only the language model is scripted (docs: scripts/scripted_model.py), as in the project's own on-device tests."""
import json
import os
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, say, tool, last_tool_text  # noqa: E402,F401

ADB = umbractl.ADB
RAW = os.path.join(ROOT, "marketing", "raw")


def sh(*args):
    return subprocess.run([ADB, "shell", *args], capture_output=True, text=True).stdout


def tap(x, y):
    sh("input", "tap", str(int(x)), str(int(y)))


def swipe(x1, y1, x2, y2, ms=400):
    sh("input", "swipe", *map(str, map(int, (x1, y1, x2, y2, ms))))


def page(route):
    sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "page", route)


def text(s):
    sh("input", "text", s.replace(" ", "%s"))


def paced(step, seconds):
    """Wraps a scripted step so the model answers after a pause: a person can follow what the agent does."""
    def run(request):
        time.sleep(seconds)
        return step(request) if callable(step) else step
    return run


class Recorder:
    """screenrecord in 170 s segments (the tool's limit is 180 s); pulled to marketing/raw/NAME_k.mp4 on stop."""

    def __init__(self, name):
        self.name, self.segments, self.stopping, self.t0 = name, 0, False, None
        self.marks = []
        self.thread = None

    def _loop(self):
        while not self.stopping:
            k = self.segments
            self.segments += 1
            subprocess.run([ADB, "shell", "screenrecord --time-limit 170 --bit-rate 8000000 /sdcard/%s_%d.mp4" % (self.name, k)])

    def start(self):
        os.makedirs(RAW, exist_ok=True)
        self.t0 = time.time()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()
        time.sleep(1.5)
        return self

    def mark(self, label):
        self.marks.append({"t": round(time.time() - self.t0, 2), "label": label})
        print("mark %6.1fs %s" % (self.marks[-1]["t"], label), flush=True)

    def stop(self):
        self.stopping = True
        sh("pkill", "-INT", "screenrecord")
        self.thread.join(timeout=20)
        time.sleep(4)
        files = []
        for k in range(self.segments):
            dest = os.path.join(RAW, "%s_%d.mp4" % (self.name, k))
            subprocess.run([ADB, "pull", "/sdcard/%s_%d.mp4" % (self.name, k), dest], capture_output=True)
            files.append(dest)
            sh("rm", "-f", "/sdcard/%s_%d.mp4" % (self.name, k))
        with open(os.path.join(RAW, self.name + ".marks.json"), "w") as f:
            json.dump({"segments": files, "marks": self.marks}, f, indent=1)
        print("saved", files)
