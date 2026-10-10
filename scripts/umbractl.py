#!/usr/bin/env python3
"""Talks to the debug build's control channel (see docs/DEVELOPMENT.md).

    scripts/umbractl.py setup                       start the bridge on the device and forward the port
    scripts/umbractl.py call COMMAND [JSON-ARGS]    send one control command, print the JSON result
    scripts/umbractl.py tool TOOL [JSON-ARGS]       call a host tool (android_observe, android_tap, ...)
    scripts/umbractl.py wait-session ID [TIMEOUT]   poll until the session is finished, print its timeline

Environment: ANDROID_SERIAL picks the device; UMBRA_PORT (default 47000) and UMBRA_TOKEN (default
"umbra-dev-token-0000") choose the bridge port and secret; UMBRA_CTL_TIMEOUT (seconds, default 900) is how long
one command may take, for installs in the Linux environment that download a lot.
"""
import json
import os
import socket
import subprocess
import sys
import time

PORT = int(os.environ.get("UMBRA_PORT", "47000"))
CTL_TIMEOUT = int(os.environ.get("UMBRA_CTL_TIMEOUT", "900"))
TOKEN = os.environ.get("UMBRA_TOKEN", "umbra-dev-token-0000")
ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def adb(*args, check=True):
    cmd = [ADB]
    if os.environ.get("ANDROID_SERIAL"):
        cmd += ["-s", os.environ["ANDROID_SERIAL"]]
    p = subprocess.run(cmd + list(args), capture_output=True, text=True)
    if check and p.returncode != 0:
        sys.exit("adb %s failed: %s" % (" ".join(args), p.stderr.strip() or p.stdout.strip()))
    return p.stdout


_SU_FORMS = ("su 0 sh -c '%s'", "su -c '%s'", "su root -c '%s'")
_su_form = None


def root(command):
    """Runs a command as root on the device (it must not contain single quotes), whichever way its `su` wants to be
    called: emulator images take `su 0 sh -c`, Magisk and KernelSU `su -c`. Returns the output."""
    global _su_form
    if _su_form is None:
        for form in _SU_FORMS:
            if adb("shell", form % "id -u", check=False).strip() == "0":
                _su_form = form
                break
        else:
            sys.exit("the device does not give root to adb through su (tried su 0 sh -c, su -c, su root -c)")
    return adb("shell", _su_form % command, check=False)


def setup():
    out = adb("shell", "am", "broadcast", "-a", "app.umbra.agent.dev.BRIDGE_START", "--es", "token", TOKEN, "--ei", "port", str(PORT), "app.umbra.agent")
    adb("forward", "tcp:%d" % PORT, "tcp:%d" % PORT)
    print(out.strip().splitlines()[-1] if out.strip() else "bridge started")


class Bridge:
    def __init__(self):
        self.sock = socket.create_connection(("127.0.0.1", PORT), timeout=30)
        self.file = self.sock.makefile("rw", encoding="utf-8", newline="\n")
        self.n = 0

    def send(self, body, timeout=None):
        timeout = timeout or CTL_TIMEOUT
        self.n += 1
        body = dict(body, id=str(self.n), auth=TOKEN)
        self.sock.settimeout(timeout)
        self.file.write(json.dumps(body) + "\n")
        self.file.flush()
        while True:
            line = self.file.readline()
            if not line:
                sys.exit("the bridge closed the connection")
            reply = json.loads(line)
            if reply.get("id") == str(self.n):
                return reply

    def control(self, command, args=None, timeout=None):
        reply = self.send({"control": command, "args": args or {}}, timeout)
        if not reply.get("ok"):
            raise RuntimeError("%s failed: %s" % (command, reply.get("error") or reply))
        return reply["result"]

    def tool(self, name, args=None, session="umbractl", timeout=None):
        return self.send({"session": session, "tool": name, "args": args or {}}, timeout)


def timeline(bridge, session_id, after=0):
    events = bridge.control("session.events", {"id": session_id, "after": after})
    for e in events:
        ev = e["event"]
        kind = ev.get("type")
        if kind == "agent_message":
            print("[%d] AGENT: %s" % (e["seq"], ev["text"][:400].replace("\n", " ")))
        elif kind == "user_message":
            print("[%d] USER: %s" % (e["seq"], ev["text"][:200].replace("\n", " ")))
        elif kind == "tool_call":
            print("[%d] CALL %s %s" % (e["seq"], ev["tool"], json.dumps(ev.get("args"))[:160]))
        elif kind == "tool_result":
            print("[%d]   -> %s: %s" % (e["seq"], ev["status"], ev["summary"][:200].replace("\n", " ")))
        elif kind == "state":
            print("[%d] STATE %s -> %s %s" % (e["seq"], ev["from"], ev["to"], ev.get("reason") or ""))
        elif kind in ("failure", "recovery", "app_event", "notice", "approval"):
            print("[%d] %s %s" % (e["seq"], kind.upper(), json.dumps(ev)[:240]))
    return events[-1]["seq"] if events else after


def wait_session(bridge, session_id, timeout=2400, auto_approve=False):
    deadline = time.time() + timeout
    seen = 0
    while time.time() < deadline:
        seen = timeline(bridge, session_id, seen)
        s = bridge.control("session.get", {"id": session_id})
        if auto_approve:
            for a in bridge.control("approvals.list"):
                if a["session"] == session_id:
                    print("APPROVING:", a["description"][:200])
                    bridge.control("approvals.resolve", {"id": a["id"], "decision": "approve"})
        if s["state"] in ("COMPLETED", "FAILED", "STOPPED"):
            seen = timeline(bridge, session_id, seen)
            return s
        time.sleep(3)
    raise SystemExit("timed out waiting for session %s" % session_id)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    if cmd == "setup":
        setup()
        return 0
    bridge = Bridge()
    if cmd == "call":
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        print(json.dumps(bridge.control(sys.argv[2], args), indent=2))
    elif cmd == "tool":
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        reply = bridge.tool(sys.argv[2], args)
        for block in reply.get("content", []):
            if block.get("type") == "text":
                print(block["text"])
            else:
                print("[%s image, %d base64 chars]" % (block.get("mimeType"), len(block.get("data", ""))))
    elif cmd == "wait-session":
        s = wait_session(bridge, sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 2400, auto_approve="--approve" in sys.argv)
        print(json.dumps(s))
        return 0 if s["state"] == "COMPLETED" else 1
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
