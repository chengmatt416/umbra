#!/usr/bin/env python3
"""Verify notification retention against the real debug app; requires `umbractl.py setup`.

Posts only synthetic local notifications, never messages to another account. Does not change app policies.
"""
import shlex
import os
import subprocess
import time
import uuid

from umbractl import ADB, Bridge, adb


def main():
    bridge = Bridge()
    policy = bridge.control("notifications.policy", {"package": "com.android.shell"})["policy"]
    if policy != "CONTEXT":
        raise RuntimeError("The shell notification policy must be Context; refusing to change it or wake an agent.")
    token = uuid.uuid4().hex
    title = "Umbra retention test " + token
    text = "Synthetic local notification " + token
    otp_title = "Umbra verification code " + token
    otp_text = "Your verification code is 654321"
    for tag, heading, body in [("umbra-test-" + token, title, text), ("umbra-otp-" + token, otp_title, otp_text)]:
        adb("shell", shlex.join(["cmd", "notification", "post", "-t", heading, tag, body]))
    deadline = time.monotonic() + 15
    while True:
        records = bridge.control("notifications.list")
        if any(r["title"] == title and r["text"] == text for r in records):
            break
        if time.monotonic() >= deadline:
            raise AssertionError("The actual notification listener did not retain the synthetic notification")
        time.sleep(0.25)
    assert not any(r["title"] == otp_title for r in records), "A code-bearing notification should be ignored by default"
    command = [ADB]
    if os.environ.get("ANDROID_SERIAL"):
        command += ["-s", os.environ["ANDROID_SERIAL"]]
    database = subprocess.run(command + ["exec-out", "run-as", "app.umbra.agent", "cat", "databases/notifications.db"], check=True, capture_output=True).stdout
    assert database.startswith(b"SQLite format 3"), "Did not read the app's real notification database"
    assert text.encode() not in database and title.encode() not in database, "Notification content was stored as plaintext"
    assert otp_text.encode() not in database, "A verification code reached storage"
    print("PASS: real notification capture, default OTP exclusion, and encrypted SQLite record content")


if __name__ == "__main__":
    main()
