#!/usr/bin/env python3
"""Runs the whole agent on the device with a scripted model (no API key): the real app, the real omp in
the Linux guest, the real Android tools. Checks what each tool really did.

    scripts/ondevice-scripted-agent.py            (the app must be installed, set up and the bridge reachable: see ondevice-verify.sh)

What is real: the session, the omp process in the guest, the safety system and its approvals, the
accessibility and shell tools on the device, the timeline. What is scripted: the model's replies.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, say, tool  # noqa: E402


def main():
    bridge = umbractl.Bridge()
    status = bridge.control("status")
    print("device:", json.dumps(status))
    assert status["guest"].startswith("Ready"), "the Linux environment must be installed and mounted first"

    model = ScriptedModel([
        tool("android_observe", {}),
        tool("bash", {"command": "echo from-guest; uname -m; id -u; test -d /workspace && echo workspace-ok"}),
        tool("android_shell", {"command": "echo from-android; id -u"}),
        tool("android_screenshot", {}),
        tool("bash", {"command": "touch /workspace/agent-was-here && ls -l /workspace/agent-was-here"}),
        say("All steps done: I observed the screen, ran commands in Linux and Android, took a screenshot and wrote a file."),
    ]).start()
    print("scripted model on", model.url_for_emulator)
    try:
        bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
        bridge.control("approval_mode", {"mode": "balanced"})
        created = bridge.control("session.create", {"request": "Exercise every kind of tool once and report."})
        sid = created["id"]
        print("session", sid)
        final = umbractl.wait_session(bridge, sid, timeout=1800, auto_approve=True)
        print("final state:", final["state"], final.get("error"))
        events = bridge.control("session.events", {"id": sid, "after": 0})

        calls = {}
        for e in events:
            ev = e["event"]
            if ev.get("type") == "tool_result":
                calls.setdefault(ev["tool"], []).append(ev)
        problems = []

        def need(cond, text):
            if not cond:
                problems.append(text)

        need(final["state"] == "COMPLETED", "the session did not complete: %s %s" % (final["state"], final.get("error")))
        need(any(r["status"] == "ok" for r in calls.get("android_observe", [])), "android_observe did not succeed")
        guest_out = " ".join(r["summary"] for r in calls.get("bash", []))
        need("from-guest" in guest_out and "aarch64" in guest_out, "bash did not run in the arm64 guest: " + guest_out[:200])
        need("workspace-ok" in guest_out, "the workspace was not mounted at /workspace")
        need("agent-was-here" in guest_out, "the agent could not write to its workspace")
        need(any("from-android" in r["summary"] for r in calls.get("android_shell", [])), "android_shell did not run: %s" % calls.get("android_shell"))
        need(any(r["status"] == "ok" for r in calls.get("android_screenshot", [])), "android_screenshot did not succeed: %s" % calls.get("android_screenshot"))
        need(len(model.agent_requests) == 6, "the agent asked the model %d times, expected 6" % len(model.agent_requests))
        # The briefing and the tool list reached the model.
        first = model.agent_requests[0] if model.agent_requests else {"messages": []}
        system = " ".join(str(m.get("content")) for m in first["messages"] if m.get("role") == "system")
        need("Umbra: you operate an Android phone" in system, "the agent was not briefed")
        offered = {t["function"]["name"] for t in first.get("tools", [])}
        need({"android_observe", "android_tap", "android_shell", "bash"} <= offered, "tools offered: %s" % sorted(offered))
        if problems:
            print("\nPROBLEMS:")
            for p in problems:
                print(" -", p)
            return 1
        print("\nOK: the agent ran end to end on the device (omp in the guest, Android tools, safety, timeline).")
        return 0
    finally:
        model.stop()


if __name__ == "__main__":
    sys.exit(main())
