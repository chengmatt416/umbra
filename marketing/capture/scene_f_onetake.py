#!/usr/bin/env python3
"""Scene F: one continuous take, no cuts. Ask on Home -> the agent operates the real Settings app (the capsule follows it) ->
a risky step waits for approval -> the person denies it -> the answer. Touches are shown by Android itself (show_touches).
Marks are written for the renderer (camera path and captions)."""
import time
from lib import *

b = umbractl.Bridge(); umbractl.setup()
P = 2.6
rec = None


def mk(label, step):
    def run(request):
        if rec and rec.t0:
            rec.mark(label)
        time.sleep(P)
        return step
    return run


model = ScriptedModel([
    mk("observe", tool("android_observe", {})),
    mk("launch", tool("android_launch", {"package": "com.android.settings"})),
    mk("scroll", tool("android_scroll", {"direction": "down", "amount": 3})),
    mk("scroll2", tool("android_scroll", {"direction": "down", "amount": 3})),
    mk("about", tool("android_tap", {"label": "About emulated device"})),
    mk("version", tool("android_observe", {})),
    mk("risky", tool("android_shell", {"command": "pm uninstall --user 0 com.android.stk"})),
    mk("answer", say("This phone runs Android 16 (API level 36). I did not remove the SIM toolkit app, because you denied that step. Nothing else was changed.")),
]).start()
b.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
b.control("approval_mode", {"mode": "balanced"})
b.control("capsule.set", {"on": True})
for s in b.control("session.list", {}):
    if s["state"] not in ("COMPLETED", "FAILED", "STOPPED"):
        b.control("session.stop", {"id": s["id"]})
sh("settings", "put", "system", "show_touches", "1")
sh("input", "keyevent", "3"); sh("am", "force-stop", "com.android.settings")
page("home"); time.sleep(3)
rec = Recorder("scene_f").start()
rec.mark("home"); time.sleep(2.0)
tap(360, 985); time.sleep(1.0); rec.mark("typing")
text("Check my Android version, then remove the SIM toolkit app")
time.sleep(0.8); sh("input", "keyevent", "4"); time.sleep(1.0)
rec.mark("typed")
tap(623, 1121); rec.mark("sent")     # Send
sid = None
for _ in range(60):
    ss = b.control("session.list", {})
    if ss:
        sid = max(ss, key=lambda x: x["id"])["id"]
        if len(model.agent_requests) >= 1:
            break
    time.sleep(1)
for _ in range(120):
    if b.control("approvals.list"):
        break
    time.sleep(0.5)
rec.mark("approval")
time.sleep(3.0)                       # the capsule peeks open by itself; let it be seen
page("inbox"); time.sleep(0.6)
rec.mark("inbox"); time.sleep(3.2)
tap(120, 912); rec.mark("deny")       # Deny
time.sleep(2.0)
sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", sid)
rec.mark("current"); time.sleep(5.0)
swipe(360, 1000, 360, 400, 700); time.sleep(2.0)
rec.mark("end"); time.sleep(1.0)
sh("settings", "put", "system", "show_touches", "0")
rec.stop(); model.stop()
print("session", sid, b.control("session.get", {"id": sid})["state"])
