#!/usr/bin/env python3
"""A long-running scripted session with a real plan, kept alive for the Power Studio tour (scene E). Run it in the
background; kill it when the tour is done. It prints the session id."""
import time
from lib import *

b = umbractl.Bridge(); umbractl.setup()
plan = [
    {"phase": "Prepare", "items": ["Read the release notes", "Run the unit tests"]},
    {"phase": "Build", "items": ["Build the release APK", "Sign the APK"]},
    {"phase": "Publish", "items": ["Upload to the release bucket", "Verify the download"]},
]
model = ScriptedModel([
    paced(tool("todo", {"op": "init", "list": plan}), 1),
    paced(tool("todo", {"op": "done", "task": "Read the release notes"}), 2),
    paced(tool("bash", {"command": "cd /workspace && echo 'running unit tests…' && uname -m"}), 2),
    paced(tool("todo", {"op": "done", "task": "Run the unit tests"}), 2),
    paced(tool("bash", {"command": "echo 'building release APK…'; sleep 3000", "timeout": 3600}), 2),
    say("Release built."),
]).start()
b.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
b.control("approval_mode", {"mode": "autonomous"}); b.control("capsule.set", {"on": True})
sid = None
for _ in range(80):
    try:
        sid = b.control("session.create", {"request": "Prepare the 0.6.0 release: test, build, sign and upload the APK"})["id"]; break
    except RuntimeError as e:
        print(e, flush=True); time.sleep(15)
print("session", sid, flush=True)
while True:
    time.sleep(30)
