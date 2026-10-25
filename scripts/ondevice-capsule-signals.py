#!/usr/bin/env python3
"""Check signal-driven capsule startup and idle behavior on the disposable QA emulator."""
import json
import os
from pathlib import Path
import time
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556', 'Use only the disposable QA emulator'
assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name'), 'Wrong emulator'
setup()
b = Bridge()
original = b.control('capsule.status')['enabled']

def until(check):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        status = b.control('capsule.status')
        if check(status):
            return status
        time.sleep(.2)
    raise AssertionError(status)

try:
    b.control('capsule.set', {'on': True})
    started = until(lambda s: s['started'] and s['revision'] > 0)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    before = b.control('capsule.status')
    # Prior fixtures may still be delivering accessibility disconnect/completion broadcasts.
    # Require a bounded quiet settling period before measuring an otherwise idle capsule.
    stable_since = time.monotonic()
    deadline = stable_since + 20
    while time.monotonic() - stable_since < 3:
        assert time.monotonic() < deadline, 'Capsule never became idle'
        time.sleep(.3)
        current = b.control('capsule.status')
        if current['revision'] != before['revision']:
            stable_since = time.monotonic()
        before = current
    time.sleep(6)
    idle = b.control('capsule.status')
    assert idle['revision'] == before['revision'], {'before': before, 'idle': idle}
    b.control('test.capsule.signal')
    changed = until(lambda s: s['revision'] > idle['revision'])
    b.control('capsule.set', {'on': False})
    stopped = until(lambda s: not s['started'])
    result = {'startedAfterSignal': started['started'], 'idleSignals': idle['revision'] - before['revision'],
              'explicitSignalRefreshed': changed['revision'] > idle['revision'], 'stoppedWhenDisabled': not stopped['started']}
    output = Path('reports/capsule-signals')
    output.mkdir(parents=True, exist_ok=True)
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
finally:
    b.control('capsule.set', {'on': original})
