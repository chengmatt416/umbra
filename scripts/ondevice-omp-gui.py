#!/usr/bin/env python3
"""Drives the omp GUI (ADR 0013) on the device the way a person does: taps and typing through adb, checks
what the screens show with uiautomator, and checks the session behind them through the debug bridge.

    scripts/ondevice-omp-gui.py [--shots DIR]

Real: the app and its Porcelain screens, omp in the Linux guest, the session, slash commands, `!` bash,
branching. Scripted: the model's replies (no API key), served from this machine on 10.0.2.2.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402
from scripted_model import ScriptedModel, say, tool  # noqa: E402

ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
SHOTS = sys.argv[sys.argv.index("--shots") + 1] if "--shots" in sys.argv else None


def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check).stdout


def shot(name):
    if not SHOTS:
        return
    os.makedirs(SHOTS, exist_ok=True)
    with open(os.path.join(SHOTS, name + ".png"), "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)


def nodes():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/data/local/tmp/ui.xml", check=False)
        xml = adb("shell", "cat", "/data/local/tmp/ui.xml", check=False)
        if xml.strip().startswith("<?xml"):
            return list(ET.fromstring(xml).iter("node"))
        time.sleep(1)
    return []


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text, exact=False, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for n in nodes():
            label = (n.get("text") or "") + "|" + (n.get("content-desc") or "")
            if (exact and text in ((n.get("text") or ""), (n.get("content-desc") or ""))) or (not exact and text in label):
                return n
        time.sleep(1)
    raise AssertionError("not on screen: %r" % text)


def visible(text):
    return any(text in ((n.get("text") or "") + (n.get("content-desc") or "")) for n in nodes())


def tap(text, exact=False, timeout=30):
    x, y = center(find(text, exact, timeout))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.2)


def type_text(text):
    # `input text` takes %s for spaces and needs shell metacharacters escaped.
    escaped = "".join("\\" + c if c in "&|;<>()$`\\\"'*?[]#~!{}" else c for c in text).replace(" ", "%s")
    adb("shell", "input", "text", escaped)
    time.sleep(0.8)


def wait_until(predicate, what, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(2)
    raise AssertionError("timed out waiting for " + what)


def reply(request):
    """The scripted model: run one shell command for a request about a check, otherwise answer the last message."""
    messages = request.get("messages", [])
    last_user = next((m for m in reversed(messages) if m.get("role") == "user"), {})
    content = last_user.get("content")
    text = content if isinstance(content, str) else " ".join(b.get("text", "") for b in content or [] if isinstance(b, dict))
    tool_seen = messages and messages[-1].get("role") == "tool"
    if "check" in text and not tool_seen:
        return tool("bash", {"command": "echo gui-check-from-guest && uname -m"})
    if tool_seen:
        return say("The check printed its output. All good.")
    return say("Answer to: " + text.strip()[:80])


def main():
    bridge = umbractl.Bridge()
    status = bridge.control("status")
    assert status["guest"].startswith("Ready"), "install the Linux environment first: %s" % status["guest"]
    model = ScriptedModel([reply] * 50).start()
    problems = []

    def need(cond, text):
        print(("ok   " if cond else "FAIL ") + text)
        if not cond:
            problems.append(text)

    try:
        bridge.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
        bridge.control("approval_mode", {"mode": "autonomous"})
        adb("shell", "am", "start", "-n", "app.umbra.agent/.MainActivity")
        tap("Home", exact=True)
        find("What shall we", timeout=60)
        shot("01-home")

        # Home: type a request into the Smart Composer and send it.
        tap("Message", exact=True)
        type_text("Please run the gui check")
        adb("shell", "input", "keyevent", "4")  # hide the keyboard
        time.sleep(1)
        tap("Send", exact=True)
        print("sent from Home")

        # Current shows the conversation: the request, the tool card, and the answer.
        find("Please run the gui check", timeout=60)
        wait_until(lambda: visible("All good"), "the agent's answer in the chat", timeout=300)
        shot("02-current-chat")
        need(visible("bash"), "the bash tool card is shown")
        need(visible("gui-check-from-guest"), "the tool's output is shown in its card")
        sessions = bridge.control("session.list", {})
        sid = max((s for s in sessions if s.get("title", "").startswith("Please run the gui check")), key=lambda s: s["id"])["id"]
        wait_until(lambda: bridge.control("session.get", {"id": sid})["state"].upper() == "COMPLETED", "the session to complete", timeout=120)
        need(True, "the session completed")

        # Slash palette: typing / lists omp's commands.
        tap("Message", exact=True)
        type_text("/ses")
        time.sleep(2)
        need(visible("/session"), "the slash palette offers /session")
        shot("03-slash-palette")
        tap("/session", exact=False)
        type_text("info")
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
        tap("Send", exact=True)
        wait_until(lambda: visible("CWD:"), "the /session info output", timeout=60)
        shot("04-command-output")

        # `!` runs a shell command in the session folder and shows its output.
        tap("Message", exact=True)
        type_text("!echo bang-from-gui")
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
        tap("Send", exact=True)
        wait_until(lambda: visible("bang-from-gui") and visible("exit 0"), "the ! command's output", timeout=90)
        need(True, "the ! command ran and its output is shown")
        shot("05-bang")

        # The model sheet lists the scripted provider's model.
        tap("scripted-model")
        wait_until(lambda: visible("Model & thinking") or visible("MODEL & THINKING"), "the model sheet", timeout=30)
        shot("06-model-sheet")
        need(visible("scripted"), "the model sheet lists the scripted provider")
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)

        # A follow-up, then edit-and-resend the first message from the tree.
        tap("Message", exact=True)
        type_text("second message")
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
        tap("Send", exact=True)
        wait_until(lambda: visible("Answer to: second message"), "the follow-up's answer", timeout=120)
        shot("07-follow-up")

        tap("Session actions")
        tap("Conversation")
        tap("Tree & branches")
        wait_until(lambda: visible("Edit from here"), "the tree", timeout=60)
        shot("08-tree")
        edits = [n for n in nodes() if (n.get("text") or "") == "Edit from here"]
        x, y = center(edits[-1])
        adb("shell", "input", "tap", str(x), str(y))
        wait_until(lambda: not visible("Answer to: second message"), "the branch to drop the second exchange", timeout=60)
        need(visible("second message"), "the edited message is back in the composer")
        shot("09-branched")

        events = bridge.control("session.events", {"id": sid, "after": 0})
        need(any(e["event"].get("type") == "tool_result" for e in events), "the timeline recorded the tool")
    finally:
        model.stop()
    print("\n%d problem(s)" % len(problems))
    for p in problems:
        print(" -", p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
