#!/usr/bin/env python3
"""Recovery on the device (spec §30): things die while a task is running, and the task goes on or can be resumed.

Each scenario runs a real task with a scripted model, breaks something for real from outside, and checks what the
app did about it. Only the model's answers are scripted.

    scripts/ondevice-recovery.py helper    the privileged helper is killed mid-task: the app starts it again and the task's
                                           next step, which needs it, works
    scripts/ondevice-recovery.py agent     the agent process (omp, in the Linux environment) is killed mid-task: it is started
                                           again, told, and the task completes
    scripts/ondevice-recovery.py app       the whole app is killed mid-task: on the next start the task is paused with the
                                           reason, nothing is left running in Linux, and resuming completes it
    scripts/ondevice-recovery.py all

Needs the debug build set up (scripts/dev-device.sh setup) and the Linux environment installed.
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, say, tool  # noqa: E402

ADB = umbractl.ADB
PKG = "app.umbra.agent"


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def root(cmd):
    return umbractl.root(cmd)


def processes():
    """(pid, user, args) of everything running on the device."""
    rows = []
    for line in adb("shell", "ps -A -o PID,USER,ARGS").splitlines()[1:]:
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[0].isdigit():
            rows.append((int(parts[0]), parts[1], parts[2]))
    return rows


def shell_helpers():
    return [p for p in processes() if "ServerMain --port" in p[2] and p[1] == "shell"]


def omp_processes():
    return [p for p in processes() if "omp --mode rpc-ui" in p[2] and "grep" not in p[2]]


def events(bridge, sid):
    return [e["event"] for e in bridge.control("session.events", {"id": sid, "after": 0})]


def wait_for(what, condition, timeout, poll=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = condition()
        if found:
            return found
        time.sleep(poll)
    raise AssertionError("timed out waiting for " + what)


def started_tool(bridge, sid, name):
    return any(e.get("type") == "tool_call" and e.get("tool") == name for e in events(bridge, sid))


def run_task(bridge, script, request):
    model = ScriptedModel(script).start()
    bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
    bridge.control("approval_mode", {"mode": "balanced"})
    # The app starts no new task while the phone is short of memory, and a task that just finished still holds its
    # agent process until the pressure release lets go of it; wait for that like a person would.
    deadline = time.time() + 180
    while True:
        try:
            sid = bridge.control("session.create", {"request": request})["id"]
            return model, sid
        except RuntimeError as e:
            if "under pressure" not in str(e) or time.time() > deadline:
                model.stop()
                raise
            time.sleep(5)


def helper(bridge):
    print("== the privileged helper dies while a task needs it")
    model, sid = run_task(bridge, [
        tool("bash", {"command": "echo holding; sleep 45; echo held"}),
        tool("android_shell", {"command": "echo after-the-helper-came-back; id -u"}),
        say("The helper was restarted and the task went on."),
    ], "Recovery: the helper")
    try:
        wait_for("the first command to start", lambda: started_tool(bridge, sid, "bash"), 120)
        before = shell_helpers()
        print("shell helpers before:", [p[0] for p in before])
        assert before, "no shell-user helper to kill"
        for pid, _, _ in before:
            root("kill -9 %d" % pid)
        time.sleep(1)
        assert not [p for p in shell_helpers() if p[0] in {b[0] for b in before}], "the helper survived"
        print("killed; waiting for the app to bring one back…")
        wait_for("a new helper process", lambda: [p for p in shell_helpers() if p[0] not in {b[0] for b in before}], 90)
        final = umbractl.wait_session(bridge, sid, timeout=300, auto_approve=True)
        evs = events(bridge, sid)
        recovered = [e for e in evs if e.get("type") == "recovery" and e.get("subsystem") == "helper" and e.get("success")]
        failed = [e for e in evs if e.get("type") == "failure" and e.get("subsystem") == "helper"]
        result = next((e for e in evs if e.get("type") == "tool_result" and e.get("tool") == "android_shell"), {})
        problems = []
        if final["state"] != "COMPLETED":
            problems.append("the task ended as %s %s" % (final["state"], final.get("error")))
        if not failed:
            problems.append("the timeline has no helper failure")
        if not recovered:
            problems.append("the timeline has no helper recovery")
        if result.get("status") != "ok" or "2000" not in result.get("summary", ""):
            problems.append("the step that needs the helper did not work afterwards: %s" % json.dumps(result)[:300])
        return problems
    finally:
        model.stop()


def agent(bridge):
    print("== the agent process dies while a task runs")
    model, sid = run_task(bridge, [
        tool("bash", {"command": "echo holding; sleep 25; echo held"}),
        say("The agent was restarted and finished the task."),
    ], "Recovery: the agent process")
    try:
        wait_for("the command to start", lambda: started_tool(bridge, sid, "bash"), 120)
        before = omp_processes()
        print("omp before:", [p[0] for p in before])
        assert before, "no agent process to kill"
        for pid, _, _ in before:
            root("kill -9 %d" % pid)
        final = umbractl.wait_session(bridge, sid, timeout=420, auto_approve=True)
        evs = events(bridge, sid)
        failed = [e for e in evs if e.get("type") == "failure" and e.get("subsystem") == "omp"]
        recovered = [e for e in evs if e.get("type") == "recovery" and e.get("success")]
        messages = [e.get("text", "") for e in evs if e.get("type") == "agent_message"]
        problems = []
        if final["state"] != "COMPLETED":
            problems.append("the task ended as %s %s" % (final["state"], final.get("error")))
        if not failed:
            problems.append("the timeline has no agent failure")
        if not recovered:
            problems.append("the timeline has no recovery")
        if not any("restarted" in m for m in messages):
            problems.append("the agent's closing message is missing: %s" % messages)
        return problems
    finally:
        model.stop()


def app(bridge):
    print("== the whole app dies while a task runs")
    model, sid = run_task(bridge, [
        tool("bash", {"command": "echo holding; sleep 60; echo held"}),
        say("Resumed after the app restarted."),
    ], "Recovery: the app")
    problems = []
    try:
        wait_for("the command to start", lambda: started_tool(bridge, sid, "bash"), 120)
        print("force-stopping the app")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(3)
        adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
        # Everything the control channel and the helpers had is gone with the process; the helpers live on in their own
        # processes and the app finds them again from its preferences.
        wait_for("the app process", lambda: any(p[2].strip() == PKG for p in processes()), 90)
        time.sleep(5)
        umbractl.setup()
        bridge = umbractl.Bridge()
        paused = wait_for("the task to be paused", lambda: bridge.control("session.get", {"id": sid})["state"] == "PAUSED", 120)
        note = [e for e in events(bridge, sid) if e.get("type") == "state"][-1]
        if "restarted" not in (note.get("reason") or ""):
            problems.append("the user is not told why it stopped: %s" % note)
        left = omp_processes()
        if left:
            problems.append("an agent process was left running in Linux: %s" % left)
        print("paused; resuming")
        bridge.control("session.resume", {"id": sid})
        final = umbractl.wait_session(bridge, sid, timeout=300, auto_approve=True)
        if final["state"] != "COMPLETED":
            problems.append("the resumed task ended as %s %s" % (final["state"], final.get("error")))
        return problems
    finally:
        model.stop()


SCENARIOS = {"helper": helper, "agent": agent, "app": app}


def main():
    names = sys.argv[1:] or ["all"]
    if names == ["all"]:
        names = list(SCENARIOS)
    all_problems = []
    for name in names:
        bridge = umbractl.Bridge()
        found = SCENARIOS[name](bridge)
        print("   ->", "ok" if not found else "PROBLEMS")
        all_problems += ["%s: %s" % (name, p) for p in found]
    if all_problems:
        print("\nPROBLEMS:")
        for p in all_problems:
            print(" -", p)
        return 1
    print("\nOK: the app recovered from what was broken, or told the user what to do.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
