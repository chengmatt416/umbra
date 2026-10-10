#!/usr/bin/env python3
"""Drives the Capsule and Snapshot selection (ADR 0014) on the emulator the way a person does, and checks what the
model receives.

    scripts/ondevice-capsule.py [--shots DIR]

Needs: the app installed and set up (scripts/dev-device.sh setup), Umbra's accessibility service on, the root helper
up, the Linux environment installed. Written for the project's AVD (umbra36: 720×1600, punch-hole camera); the
composer's buttons are found from its window's frame. uiautomator cannot see overlay windows, so taps go by position.

Real: the capsule and its windows, the screenshot and accessibility tree, the session, omp in the guest, the safety
system. Scripted: the model's replies (no API key), served from this machine on 10.0.2.2.
"""
import base64
import math
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, say, tool  # noqa: E402

ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
SHOTS = sys.argv[sys.argv.index("--shots") + 1] if "--shots" in sys.argv else None
problems = []


def sh(*args):
    return subprocess.run([ADB, "shell", *args], capture_output=True, text=True).stdout


def shot(name):
    if SHOTS:
        os.makedirs(SHOTS, exist_ok=True)
        with open(os.path.join(SHOTS, name + ".png"), "wb") as f:
            f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def need(cond, text):
    print(("ok   " if cond else "FAIL ") + text, flush=True)
    if not cond:
        problems.append(text)


def frames():
    """Umbra's overlay windows on screen: (left, top, right, bottom), top first."""
    found = re.findall(r"umbra-overlay, frame=\[Rect\((\d+), (\d+) - (\d+), (\d+)\)\]", sh("dumpsys", "window", "windows"))
    return sorted({tuple(map(int, f)) for f in found}, key=lambda r: r[1])


def wait_for(predicate, what, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            print("ok   " + what, flush=True)
            return value
        time.sleep(1)
    need(False, what)
    return None


def capsule():
    return next((f for f in frames() if f[1] < 60), None)


def composer():
    return next((f for f in frames() if f[3] - f[1] < 800 and f[1] > 600), None)


def tap(x, y):
    sh("input", "tap", str(int(x)), str(int(y)))


def lasso(cx, cy, rx, ry):
    points = [(int(cx + rx * math.cos(2 * math.pi * i / 40 - math.pi / 2)), int(cy + ry * math.sin(2 * math.pi * i / 40 - math.pi / 2))) for i in range(41)]
    sh("; ".join(["input motionevent DOWN %d %d" % points[0]] + ["input motionevent MOVE %d %d" % p for p in points[1:]] + ["input motionevent UP %d %d" % points[-1]]))


def main():
    subprocess.run([ADB, "logcat", "-c"], check=False)
    umbractl.setup()
    bridge = umbractl.Bridge()
    status = bridge.control("status")
    assert status["accessibility"] and status["guest"] in ("Ready", "Installed"), "set the device up first: %s" % status
    bridge.control("capsule.set", {"on": True})
    sh("input", "keyevent", "3")
    cap = wait_for(capsule, "the capsule is on screen", 60)
    if not cap:
        return 1
    if cap[3] - cap[1] > 150:
        # Left expanded by an earlier peek (a failure or an approval): collapse it with its × first.
        tap(cap[2] - 39, 153)
        cap = wait_for(lambda: next((f for f in [capsule()] if f and f[3] - f[1] < 150), None), "the capsule collapses", 10)
        if not cap:
            return 1
    hole = (504, 576)  # the AVD's punch-hole, x from..to
    need(cap[3] - cap[1] < 150 and cap[2] >= hole[1] and cap[0] < hole[0], "the collapsed capsule wraps the camera hole %s: %s" % (hole, cap))
    shot("01-capsule")

    # Snapshot selection over Settings: long-press the capsule, lasso two entries, Done.
    model = ScriptedModel([say("I see Network & internet and Connected devices.")] * 5).start()
    bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": "", "images": "true"})
    sh("am", "start", "-W", "-f", "0x10008000", "-n", "com.android.settings/.Settings")
    time.sleep(3)
    cap = capsule()
    sh("input", "swipe", str(cap[0] + 40), str((cap[1] + cap[3]) // 2), str(cap[0] + 40), str((cap[1] + cap[3]) // 2), "900")
    full = wait_for(lambda: next((f for f in frames() if f == (0, 0, 720, 1600)), None), "a long press opens the selection over the whole screen", 15)
    shot("02-selection")
    lasso(340, 645, 330, 140)
    time.sleep(1)
    tap(558, 1476)  # Done · Attach
    comp = wait_for(composer, "Done opens the Quick Composer", 15)
    shot("03-chip")
    if not (full and comp):
        return 1
    need(sh("dumpsys", "input_method").find("mInputShown=true") < 0, "the composer opens without the keyboard")

    # Type into the composer (its field lies under the chip), then send.
    tap(300, comp[1] + 267)
    need(wait_for(lambda: "mInputShown=true" in sh("dumpsys", "input_method"), "tapping the field opens the keyboard", 10) is not None, "the keyboard opens on tapping the field")
    sh("input", "text", "Which%sentries%sdid%sI%scircle")
    sh("input", "keyevent", "4")
    time.sleep(1.5)
    comp = composer()
    tap(636, comp[3] - 123)  # Send
    request = wait_for(lambda: model.agent_requests[0] if model.agent_requests else None, "the message reaches the model", 300)
    if request:
        user = [m for m in request["messages"] if m.get("role") == "user"][-1]
        parts = user["content"] if isinstance(user["content"], list) else [{"type": "text", "text": user["content"]}]
        text = "\n".join(p.get("text", "") for p in parts if p.get("type") == "text")
        images = [p for p in parts if p.get("type") == "image_url"]
        need("Which entries did I circle" in text, "the typed question is sent")
        need("Screen selection · Settings" in text and "“Network & internet”" in text and "“Connected devices”" in text, "the selection's accessibility text is sent")
        need(len(images) == 1 and base64.b64decode(images[0]["image_url"]["url"].split(",", 1)[1])[:3] == b"\xff\xd8\xff", "the cropped picture is sent as a JPEG")
    first = max(bridge.control("session.list", {}), key=lambda x: x["id"])["id"]
    wait_for(lambda: bridge.control("session.get", {"id": first})["state"] in ("COMPLETED", "FAILED", "STOPPED"), "the first session ends", 180)
    model.stop()

    # An approval peeks in the capsule with Previous / Current / Next, and is denied from it.
    model = ScriptedModel([
        tool("todo", {"op": "init", "list": [{"phase": "Work", "items": ["Inspect the phone", "Remove the SIM toolkit", "Verify the result"]}]}),
        tool("todo", {"op": "done", "task": "Inspect the phone"}),
        tool("android_shell", {"command": "pm uninstall --user 0 com.android.stk"}),
        say("I stopped because the removal was denied."),
    ]).start()
    bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
    bridge.control("approval_mode", {"mode": "balanced"})
    sh("input", "keyevent", "3")
    sid = None
    deadline = time.time() + 300
    while sid is None:
        try:
            sid = bridge.control("session.create", {"request": "Tidy up the phone's preinstalled apps"})["id"]
        except RuntimeError as e:
            # The resource governor holds new sessions while the phone is short of memory: wait, as a person would.
            if "under pressure" not in str(e) or time.time() > deadline:
                raise
            time.sleep(10)
    wait_for(lambda: bridge.control("approvals.list"), "the removal waits for approval", 300)
    peek = wait_for(lambda: next((f for f in frames() if f[3] > 600 and f[1] < 60), None), "the approval opens the capsule by itself", 15)
    shot("04-approval-peek")
    if peek:
        tap(100, 465)  # Deny
    wait_for(lambda: bridge.control("session.get", {"id": sid})["state"] in ("COMPLETED", "FAILED", "STOPPED"), "the session ends after the denial", 120)
    need("package:com.android.stk" in sh("pm", "list", "packages", "com.android.stk"), "the denied removal did not happen")
    model.stop()
    crashes = subprocess.run([ADB, "logcat", "-d"], capture_output=True, text=True).stdout.count("FATAL EXCEPTION")
    need(crashes == 0, "no crash in the log (%d)" % crashes)
    print("\n%d problem(s)" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
