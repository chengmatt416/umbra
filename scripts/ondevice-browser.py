#!/usr/bin/env python3
"""Smoke-test the actual Chromium browser pack in the debug app's Linux guest.

Requires `umbractl.py setup` and the browser pack. Uses only a local synthetic page, no provider credentials.
"""
from umbractl import Bridge


def main():
    bridge = Bridge()
    bridge.control("guest.status")
    page = 'data:text/html,<html><body><output id="result">ready</output><script>document.getElementById("result").textContent="umbra-browser-ok"</script></body></html>'
    result = bridge.control("guest.exec", {
        "argv": ["chromium", "--headless", "--no-sandbox", "--disable-dev-shm-usage", "--dump-dom", page],
        "timeout_ms": 30_000,
    })
    assert result["exit"] == 0, result["stderr"][-2000:]
    assert '<output id="result">umbra-browser-ok</output>' in result["stdout"], "Chromium did not execute the page's JavaScript"
    print("PASS: actual guest Chromium starts headless, executes JavaScript, and returns the DOM")


if __name__ == "__main__":
    main()
