#!/usr/bin/env python3
"""The §33 loop on the device, with a scripted model: build an Android app in the Linux environment, install it
on the phone, operate it in a background display until it crashes, read the crash from the log, fix the source,
rebuild, reinstall and sign in. Every step is a real tool call in the real app; only the model's decisions are
scripted (they are what a good model would do), so this shows the environment, the tools and the fixture work
before a real model is asked to do the same. With a model key use scripts/acceptance-crashy-login.sh.

    scripts/ondevice-install-android-toolchain.sh      (once: JDK and Android build tools in the Linux environment)
    scripts/ondevice-scripted-acceptance.py
"""
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, last_tool_text, say, tool  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = umbractl.ADB
PKG = "app.umbra.agent"
FIXTURE = "app.umbra.fixture.crashylogin"
SOURCE = "src/app/umbra/fixture/crashylogin/MainActivity.java"


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def put_project(bridge):
    """Gives the task its project, the way a user would hand over a folder."""
    ws = bridge.control("workspace.create", {"name": "crashy-login"})
    ident, dest = ws["id"], ws["host_path"]
    uid = re.search(r"uid:(\d+)", adb("shell", "pm", "list", "packages", "-U", PKG)).group(1)
    # COPYFILE_DISABLE: macOS tar would add ._* resource-fork files that javac chokes on.
    subprocess.run(["tar", "-C", os.path.join(ROOT, "fixtures/crashy-login"), "-cf", "/tmp/crashy-login.tar", "AndroidManifest.xml", "build.sh", "README.md", "src"], check=True, env=dict(os.environ, COPYFILE_DISABLE="1"))
    adb("push", "/tmp/crashy-login.tar", "/data/local/tmp/crashy-login.tar")
    umbractl.root("mkdir -p %s && tar -xf /data/local/tmp/crashy-login.tar -C %s && chown -R %s:%s %s" % (dest, dest, uid, uid, dest))
    adb("shell", "rm -f /data/local/tmp/crashy-login.tar")
    adb("shell", "pm uninstall %s" % FIXTURE)
    return ident, dest


def main():
    bridge = umbractl.Bridge()
    print("device:", json.dumps(bridge.control("status")))
    ident, dest = put_project(bridge)
    project = "/workspace/projects/%s" % ident
    display = {}

    def with_display(name, args):
        def step(request):
            if "n" not in display:
                found = re.search(r"display (\d+)", last_tool_text(request) or "")
                display["n"] = int(found.group(1)) if found else 0
            return tool(name, dict(args, display=display["n"]))
        return step

    def sign_in():
        return [
            with_display("android_tap", {"label": "Username"}),
            with_display("android_type", {"text": "demo"}),
            with_display("android_tap", {"label": "Password"}),
            with_display("android_type", {"text": "umbra123", "secret": True}),
            with_display("android_tap", {"label": "Log in"}),
        ]

    fix = "cd %s && sed -i 's/lastUser.equals(user)/user.equals(lastUser)/' %s && grep -n 'equals(lastUser)' %s" % (project, SOURCE, SOURCE)
    model = ScriptedModel([
        tool("bash", {"command": "cd %s && ./build.sh 2>&1 | tail -5" % project, "timeout": 900}),
        tool("android_packages", {"action": "install", "apk_path": project + "/build/crashy-login.apk"}),
        tool("android_display", {"action": "create", "width": 720, "height": 1280, "dpi": 320}),
        with_display("android_launch", {"package": FIXTURE}),
        *sign_in(),
        tool("android_logs", {"action": "query", "package": FIXTURE, "level": "E", "max_lines": 40}),
        tool("bash", {"command": fix}),
        tool("bash", {"command": "cd %s && ./build.sh 2>&1 | tail -3" % project, "timeout": 900}),
        tool("android_packages", {"action": "install", "apk_path": project + "/build/crashy-login.apk"}),
        with_display("android_launch", {"package": FIXTURE}),
        *sign_in(),
        with_display("verify", {"expect": {"text_visible": "Welcome"}, "timeout_ms": 8000}),
        say("Signing in crashed because the greeting code called lastUser.equals(user) while lastUser is null on a fresh install. "
            "I changed it to user.equals(lastUser), rebuilt and reinstalled, and signing in now shows the welcome message."),
    ]).start()
    print("scripted model on", model.url_for_emulator)
    try:
        bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
        task = ("This project is in %s (an Android app, package %s). Signing in as demo / umbra123 crashes the app. Build it with ./build.sh, "
                "install the apk on the phone, reproduce the crash in a background display, read the log to find the cause, fix the source, "
                "rebuild and reinstall, and show that signing in works." % (project, FIXTURE))
        sid = bridge.control("session.create", {"request": task, "workspace": ident})["id"]
        final = umbractl.wait_session(bridge, sid, timeout=2700, auto_approve=True)
        events = bridge.control("session.events", {"id": sid, "after": 0})
        results = {}
        for e in events:
            ev = e["event"]
            if ev.get("type") == "tool_result":
                results.setdefault(ev["tool"], []).append(ev)
        problems = []

        def need(cond, text):
            if not cond:
                problems.append(text)

        need(final["state"] == "COMPLETED", "the session did not complete: %s %s" % (final["state"], final.get("error")))
        builds = [r for r in results.get("bash", []) if "built build/crashy-login.apk" in r["summary"]]
        need(len(builds) == 2, "the project did not build twice in the Linux environment: " + " | ".join(r["summary"][:200] for r in results.get("bash", [])))
        installs = results.get("android_packages", [])
        need(len(installs) == 2 and all(r["status"] == "ok" for r in installs), "installing from the workspace failed: " + json.dumps(installs)[:400])
        # The timeline keeps a short summary of each result; what the agent was shown is the whole text.
        shown = [
            "".join(part.get("text", "") for part in (m["content"] if isinstance(m["content"], list) else [{"text": m["content"] or ""}]))
            for m in model.agent_requests[-1]["messages"] if m.get("role") == "tool"
        ]
        logs = " ".join(t for t in shown if "log line(s)" in t)
        need("NullPointerException" in logs and "signIn" in logs and "<== CRASH" in logs, "the crash was not in the log the agent read: " + logs[:600])
        verified = results.get("verify", [])
        need(bool(verified) and verified[-1]["status"] == "ok", "signing in did not show the welcome message: " + json.dumps(verified)[-300:])
        source = open(os.path.join(dest, SOURCE)).read() if os.access(os.path.join(dest, SOURCE), os.R_OK) else umbractl.root("cat %s/%s" % (dest, SOURCE))
        need("lastUser.equals(user)" not in source and "user.equals(lastUser)" in source, "the source was not fixed")
        if problems:
            print("\nPROBLEMS:")
            for p in problems:
                print(" -", p)
            return 1
        print("\nOK: build in Linux, install from the workspace, crash found in the log, fixed, rebuilt, reinstalled, sign-in verified.")
        return 0
    finally:
        model.stop()


if __name__ == "__main__":
    sys.exit(main())
