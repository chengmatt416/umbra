#!/usr/bin/env python3
"""Runs a scripted-model task on the device and leaves its decisions to a person (or another script).

Where ondevice-scripted-agent.py checks what each tool did, this one exists to put the real app in the
situations that need hands: a task that stays running (notification, floating controls), an action that
needs approval (in-app card, notification buttons, floating card), a question from the agent. It prints
the timeline as it grows and what is waiting for a decision, and exits when the task ends.

    scripts/ondevice-scenario.py approvals     # sleeps 60 s, then asks to uninstall a package, then asks a question
    scripts/ondevice-scenario.py --list

Only the language model is scripted. Everything the agent does with its answers is real.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, say, tool  # noqa: E402

SCENARIOS = {
    "approvals": (
        "Hold, then do something that needs a decision.",
        [
            tool("bash", {"command": "echo holding; sleep 60; echo held"}),
            tool("android_shell", {"command": "pm uninstall --user 0 com.android.stk"}),
            tool("ask", {"questions": [{"id": "q1", "question": "Which color do you prefer?", "options": [{"label": "Red"}, {"label": "Blue"}]}]}),
            say("That is everything I wanted to do."),
        ],
    ),
    "long": (
        "Keep working for a while.",
        [
            tool("bash", {"command": "echo start; sleep 120; echo end"}),
            say("The long command finished."),
        ],
    ),
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "--list":
        for name, (title, _) in SCENARIOS.items():
            print("%-10s %s" % (name, title))
        return 0
    title, script = SCENARIOS[sys.argv[1]]
    bridge = umbractl.Bridge()
    model = ScriptedModel(script).start()
    print("scripted model on", model.url_for_emulator, flush=True)
    bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
    bridge.control("approval_mode", {"mode": "balanced"})
    sid = bridge.control("session.create", {"request": title})["id"]
    print("session", sid, flush=True)
    seen = 0
    announced = set()
    deadline = time.time() + 1800
    while time.time() < deadline:
        seen = umbractl.timeline(bridge, sid, seen)
        for a in bridge.control("approvals.list"):
            if a["id"] not in announced:
                announced.add(a["id"])
                print("WAITING APPROVAL [%s] %s: %s" % (a["level"], a["id"], a["description"][:160]), flush=True)
        for q in bridge.control("questions.list"):
            if q["id"] not in announced:
                announced.add(q["id"])
                print("WAITING QUESTION %s (%s)" % (q["id"], q["kind"]), flush=True)
        state = bridge.control("session.get", {"id": sid})["state"]
        if state in ("COMPLETED", "FAILED", "STOPPED"):
            seen = umbractl.timeline(bridge, sid, seen)
            print("FINAL", state, flush=True)
            break
        time.sleep(2)
    model.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
