#!/usr/bin/env python3
"""Scene D: the section-33 loop. Build an Android app in the Linux environment, install it, run it in a background display,
read the crash from the log, fix the source, rebuild, reinstall, sign in. Same flow as scripts/ondevice-scripted-acceptance.py,
paced for the camera and screen-recorded. Power mode: the task's chat shows the background display live (a card that
opens full size), and the second run is watched on the Screens page."""
import importlib.util
import re
import threading
import time
from lib import *

spec = importlib.util.spec_from_file_location("acc", os.path.join(ROOT, "scripts", "ondevice-scripted-acceptance.py"))
acc = importlib.util.module_from_spec(spec); spec.loader.exec_module(acc)

b = umbractl.Bridge(); umbractl.setup()
ident, dest = acc.put_project(b)
project = "/workspace/projects/%s" % ident
FIX = acc.FIXTURE; SRC = acc.SOURCE
display = {}
state = {"sid": None}
rec = None
P = 2.4


def ui(fn):
    """Run a UI action, then hand the step over."""
    def run(_):
        fn()
    return run


def with_display(name, args, wait=P, before=None):
    def step(request):
        if before:
            before()
        time.sleep(wait)
        if "n" not in display:
            found = re.search(r"display (\d+)", last_tool_text(request) or "")
            display["n"] = int(found.group(1)) if found else 0
        return tool(name, dict(args, display=display["n"]))
    return step


def plain(step, wait=P, before=None):
    def run(request):
        if before:
            before()
        time.sleep(wait)
        return step
    return run


def close_live():
    p = find("Close", timeout=0.6, exact=True)
    if p:
        tap(*p); time.sleep(0.8)


def to_task():
    close_live()
    sh("am", "start", "-n", "app.umbra.agent/.MainActivity", "--es", "session", state["sid"])
    time.sleep(1.2)
    for _ in range(4):                      # follow the newest cards: the chat does not auto-scroll under a long prompt
        swipe(360, 1050, 360, 330, 250); time.sleep(0.25)


def follow_answer():
    """Once the answer is in the chat (the step returns after its pause), scroll it into view."""
    def later():
        time.sleep(2.5 + 0.9)
        for _ in range(3):
            swipe(360, 1050, 360, 450, 500); time.sleep(0.5)
        rec.mark("answer")
    threading.Thread(target=later, daemon=True).start()


def to_displays():
    page("screens")


def open_live():
    """The live card in the chat (it appears once the agent works on a background display), opened full size."""
    for _ in range(4):
        swipe(360, 1050, 360, 330, 250); time.sleep(0.25)
    time.sleep(1.5)
    p = find("Screens", timeout=6, exact=True)
    if p:
        tap(*p)


def mark(label):
    return lambda: rec.mark(label)


def both(*fns):
    def f():
        for g in fns:
            g()
    return f


fix = "cd %s && sed -i 's/lastUser.equals(user)/user.equals(lastUser)/' %s && grep -n 'equals(lastUser)' %s" % (project, SRC, SRC)
model = ScriptedModel([
    plain(tool("bash", {"command": "cd %s && ./build.sh 2>&1 | tail -5" % project, "timeout": 900}), 1.5, lambda: (rec and rec.t0 and rec.mark("build"))),
    plain(tool("android_packages", {"action": "install", "apk_path": project + "/build/crashy-login.apk"}), P, mark("install")),
    plain(tool("android_display", {"action": "create", "width": 720, "height": 1280, "dpi": 320}), P),
    with_display("android_launch", {"package": FIX}, P, both(open_live, mark("display"))),
    with_display("android_tap", {"label": "Username"}, 3),
    with_display("android_type", {"text": "demo"}, 2.5),
    with_display("android_tap", {"label": "Password"}, 2),
    with_display("android_type", {"text": "umbra123", "secret": True}, 2.5),
    with_display("android_tap", {"label": "Log in"}, 2.5),
    plain(tool("android_logs", {"action": "query", "package": FIX, "level": "E", "max_lines": 40}), 3.5, both(to_task, mark("logs"))),
    plain(tool("bash", {"command": fix}), P, mark("fix")),
    plain(tool("bash", {"command": "cd %s && ./build.sh 2>&1 | tail -3" % project, "timeout": 900}), P),
    plain(tool("android_packages", {"action": "install", "apk_path": project + "/build/crashy-login.apk"}), P),
    with_display("android_launch", {"package": FIX}, P, both(to_displays, mark("relaunch"))),
    with_display("android_tap", {"label": "Username"}, 3),
    with_display("android_type", {"text": "demo"}, 2.5),
    with_display("android_tap", {"label": "Password"}, 2),
    with_display("android_type", {"text": "umbra123", "secret": True}, 2.5),
    with_display("android_tap", {"label": "Log in"}, 2.5),
    with_display("verify", {"expect": {"text_visible": "Welcome"}, "timeout_ms": 8000}, 3),
    # Tidy up: close the app it launched. (Without this, the app moves onto the phone's own screen when the finished
    # task's background display is released: the display is created without DESTROY_CONTENT_ON_REMOVAL.)
    plain(tool("android_stop", {"package": FIX}), 1.5),
    plain(say("Signing in crashed because the greeting code called lastUser.equals(user) while lastUser is null on a fresh install. "
              "I changed it to user.equals(lastUser), rebuilt and reinstalled, and signing in now shows the welcome message."), 2.5,
          both(to_task, mark("done"), follow_answer)),
]).start()
b.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
b.control("approval_mode", {"mode": "autonomous"}); b.control("capsule.set", {"on": False})
ui_set(b, mode="power", onboarded=True)
task = ("This project is in %s (an Android app, package %s). Signing in as demo / umbra123 crashes the app. Build it with ./build.sh, "
        "install the apk on the phone, reproduce the crash in a background display, read the log to find the cause, fix the source, "
        "rebuild and reinstall, and show that signing in works." % (project, FIX))
sh("input", "keyevent", "3")
sid = None
for _ in range(60):
    try:
        sid = b.control("session.create", {"request": task, "workspace": ident})["id"]; break
    except RuntimeError as e:
        print(e); time.sleep(15)
state["sid"] = sid
rec = Recorder("scene_d")
to_task(); time.sleep(2.5)
rec.start(); rec.mark("start")
final = None
deadline = time.time() + 1500
while time.time() < deadline:
    for a in b.control("approvals.list"):
        if a["session"] == sid:
            time.sleep(1.2)           # let the card show for a moment
            b.control("approvals.resolve", {"id": a["id"], "decision": "approve"})
    st = b.control("session.get", {"id": sid})
    if st["state"] in ("COMPLETED", "FAILED", "STOPPED"):
        final = st; break
    time.sleep(0.7)
time.sleep(4)
sh("input", "keyevent", "3")          # end the still on a change, so the recording keeps it
time.sleep(1.5)
rec.stop(); model.stop()
print("final", final and final["state"], "session", sid)
