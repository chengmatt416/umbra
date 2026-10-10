#!/usr/bin/env python3
"""A graphical Linux program operated by an agent, on the device: the real app, the real omp in the
Linux guest, the real desktop tools (gui_*), a real GTK editor on the guest's virtual screen.

    scripts/ondevice-scripted-gui.py       (the desktop add-on must be installed in the app's Linux environment:
                                            scripts/ondevice-install-gui.sh)

Only the language model is scripted; what each tool does is checked from its real output.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, last_tool_text, say, tool  # noqa: E402

TEXT = "hello from the agent"


def type_into_editor(request):
    """Types into the first editable element the previous observation listed."""
    observation = last_tool_text(request) or ""
    for line in observation.splitlines():
        found = re.search(r"\[([^\]]+)\].*\{[^}]*\bedit\b", line)
        if found:
            return tool("gui_type", {"element": found.group(1), "text": TEXT})
    return say("I could not find a text field in: " + observation[:300])


def main():
    bridge = umbractl.Bridge()
    status = bridge.control("status")
    print("device:", json.dumps(status))
    model = ScriptedModel([
        tool("gui_launch", {"command": "mousepad"}),
        tool("bash", {"command": "sleep 20; echo desktop-ready"}),
        tool("gui_observe", {"level": "tree", "interactive_only": True}),
        type_into_editor,
        tool("gui_observe", {"level": "tree"}),
        tool("gui_screenshot", {}),
        tool("gui_window", {"action": "close", "title": "Mousepad"}),
        say("I opened the editor, typed a line into it, looked at it and closed it."),
    ]).start()
    print("scripted model on", model.url_for_emulator)
    try:
        bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
        sid = bridge.control("session.create", {"request": "Open a text editor on the Linux desktop, type a line, and close it."})["id"]
        final = umbractl.wait_session(bridge, sid, timeout=1800, auto_approve=True)
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
        for name in ("gui_launch", "gui_observe", "gui_type", "gui_screenshot", "gui_window"):
            need(any(r["status"] == "ok" for r in results.get(name, [])), "%s did not succeed: %s" % (name, results.get(name)))
        observed = " ".join(r["summary"] for r in results.get("gui_observe", []))
        need("Mousepad" in observed or "mousepad" in observed, "the editor was not on the desktop: " + observed[:300])
        # The timeline keeps a short summary of each result; the model was given the whole observation, which is where the text must be.
        seen = "\n".join(
            part.get("text", "") if isinstance(part, dict) else str(part)
            for m in model.agent_requests[-1]["messages"] if m.get("role") == "tool"
            for part in (m["content"] if isinstance(m.get("content"), list) else [m.get("content")])
        )
        need(TEXT in seen, "the typed text was not in the tree the agent was shown afterwards")
        need("*Untitled" in seen, "the editor did not mark its document as changed")
        if problems:
            print("\nPROBLEMS:")
            for p in problems:
                print(" -", p)
            return 1
        print("\nOK: the agent operated a Linux desktop program (launch, observe, type, screenshot, close).")
        return 0
    finally:
        model.stop()


if __name__ == "__main__":
    sys.exit(main())
