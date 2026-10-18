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


def ui_nodes():
    """The on-screen nodes with text or a description: [(cx, cy, text, desc, (x1, y1, x2, y2))]. Used to find a control
    by its label instead of a fixed position, so a script survives layout changes."""
    import re
    for _ in range(4):
        out = sh("uiautomator", "dump", "/sdcard/umbra-ui.xml")
        if "dumped" in out:
            break
        time.sleep(0.5)
    xml = sh("cat", "/sdcard/umbra-ui.xml")
    nodes = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, d, x1, y1, x2, y2 = m.groups()
        if t or d:
            x1, y1, x2, y2 = map(int, (x1, y1, x2, y2))
            nodes.append(((x1 + x2) // 2, (y1 + y2) // 2, t, d, (x1, y1, x2, y2)))
    return nodes


def find(label, timeout=6.0, exact=False, below=0):
    """Centre of the first node whose text or description contains (or equals) label, below y=below; None if absent."""
    end = time.time() + timeout
    while True:
        for cx, cy, t, d, _ in ui_nodes():
            if cy > below and ((label in (t, d)) if exact else (label in t or label in d)):
                return cx, cy
        if time.time() > end:
            return None
        time.sleep(0.4)


def tap_text(label, timeout=6.0, exact=False, below=0, dx=0, dy=0):
    p = find(label, timeout, exact, below)
    if p is None:
        raise RuntimeError("not on screen: %r" % label)
    tap(p[0] + dx, p[1] + dy)
    return p


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


# ---- the redesign (0.3): overlay windows (the Capsule) take real touch streams, not `input tap`
def mtap(x, y, hold=0.06):
    sh("input", "motionevent", "DOWN", str(int(x)), str(int(y)))
    time.sleep(hold)
    sh("input", "motionevent", "UP", str(int(x)), str(int(y)))


def mdrag(points, step=0.016):
    """A finger along [(x, y), ...]: down at the first point, up at the last."""
    (x0, y0), rest = points[0], points[1:]
    sh("input", "motionevent", "DOWN", str(int(x0)), str(int(y0)))
    for x, y in rest:
        time.sleep(step)
        sh("input", "motionevent", "MOVE", str(int(x)), str(int(y)))
    x1, y1 = points[-1]
    sh("input", "motionevent", "UP", str(int(x1)), str(int(y1)))


def mlong(x, y, hold=0.9):
    mtap(x, y, hold)


def circle(cx, cy, rx, ry, n=36, turns=1.08):
    import math
    return [(cx + rx * math.cos(2 * math.pi * turns * i / n - math.pi / 2), cy + ry * math.sin(2 * math.pi * turns * i / n - math.pi / 2)) for i in range(n + 1)]


def menu(label, wait=1.0):
    """The bottom-right menu button, then one of its choices."""
    tap_text("Open the menu", exact=True)
    time.sleep(wait)
    tap_text(label, exact=True)


def ui_set(bridge, **kw):
    return bridge.control("ui.set", {k: str(v).lower() if isinstance(v, bool) else v for k, v in kw.items()})


def fresh_app(route="home", wait=3.0):
    """A new activity at [route]: pages keep their scroll and disclosures while the app runs."""
    sh("am", "start", "-f", "0x10008000", "-n", "app.umbra.agent/.MainActivity", "--es", "page", route)
    time.sleep(wait)
