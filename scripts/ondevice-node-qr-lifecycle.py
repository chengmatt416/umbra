#!/usr/bin/env python3
"""Stress the QR camera teardown that also happens immediately after a successful scan.

Uses only the disposable QA emulator. It opens camera preview, switches to manual pairing while
frames are decoding, then reopens preview. No pairing, provider calls or user data changes occur.
Camera permission must already be granted. The pre-fix app crashes in the camera worker.
"""
import importlib
import json
import os
from pathlib import Path
import subprocess
import time

from umbractl import ADB, adb

ui = importlib.import_module('ondevice-task-controls')
OUT = Path('reports/qr-lifecycle')


def switch(label, expected):
    for _ in range(3):
        ui.tap(label)
        time.sleep(.3)
        if ui.find(expected) is not None:
            return
    raise AssertionError(f'Pairing screen did not switch to {expected}')


def main():
    assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556', 'Use the disposable QA emulator'
    assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name'), 'Wrong emulator'
    OUT.mkdir(parents=True, exist_ok=True)
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'nodes')
    current = ui.tree()
    if ui.find('Add node (scan QR)', current) is not None:
        ui.tap('Add node (scan QR)')
    elif ui.find('Scan again', current) is not None:
        switch('Scan again', 'Enter 12-character code')
    switch('Enter 12-character code', 'Scan again')
    pid = adb('shell', 'pidof', 'app.umbra.agent').strip()
    assert pid, 'App is not running'
    started = time.monotonic()
    try:
        for cycle in range(10):
            switch('Scan again', 'Enter 12-character code')
            time.sleep(.5)
            switch('Enter 12-character code', 'Scan again')
            assert adb('shell', 'pidof', 'app.umbra.agent', check=False).strip() == pid, f'Camera teardown crashed on cycle {cycle + 1}'
        assert ui.find('Paste node invitation link') is not None, 'Manual pairing did not remain usable'
        (OUT / 'after-teardown.png').write_bytes(subprocess.check_output([ADB, '-s', os.environ['ANDROID_SERIAL'], 'exec-out', 'screencap', '-p']))
        result = {'cycles': 10, 'sameProcess': True, 'manualPairingUsable': True, 'seconds': round(time.monotonic() - started, 1)}
        (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    except BaseException:
        (OUT / 'crash.log').write_text(adb('logcat', '-d', '-b', 'crash', '-t', '120', check=False))
        raise
    finally:
        adb('shell', 'input', 'keyevent', '4')


if __name__ == '__main__':
    try:
        main()
    except BaseException:
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / 'crash.log').write_text(adb('logcat', '-d', '-b', 'crash', '-t', '120', check=False))
        raise
