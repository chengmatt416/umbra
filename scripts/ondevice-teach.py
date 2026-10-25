#!/usr/bin/env python3
"""Verify native teaching on the disposable QA emulator using synthetic input, without model calls."""
import importlib
import json
import os
from pathlib import Path
import re
import time
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name')
assert adb('shell', 'pm', 'path', 'app.umbra.fixture.crashylogin', check=False).strip(), 'Install the disposable Crashy Login fixture first'
ui = importlib.import_module('ondevice-task-controls')
setup()
b = Bridge()
previous_services = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
previous_accessibility = adb('shell', 'settings', 'get', 'secure', 'accessibility_enabled').strip()
component = 'app.umbra.agent/app.umbra.platform.android.UmbraAccessibilityService'
services = [] if previous_services in ('', 'null') else previous_services.split(':')
if component not in services: services.append(component)
owner = None
try:
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    owner = b.control('test.teach.create')['agent']
    adb('shell', 'am', 'start', '-n', 'app.umbra.fixture.crashylogin/.MainActivity')
    tree = ui.tree()  # UiAutomation briefly disconnects accessibility: never use it while recording.
    points = {}
    for label in ('Username', 'Password'):
        node = ui.find(label, tree)
        assert node is not None, label
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        points[label] = (str((x1+x2)//2), str((y1+y2)//2))
    deadline = time.monotonic() + 25
    while True:
        try:
            b.control('test.teach.start', {'agent': owner})
            break
        except Exception:
            if time.monotonic() >= deadline: raise
            time.sleep(1)
    for label, value in [('Username', 'QA_Public_Input_1357'), ('Password', 'qaSecretFixture7')]:
        adb('shell', 'input', 'tap', *points[label])
        adb('shell', 'input', 'text', value)
        time.sleep(.8)
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    state = b.control('test.teach.stop', {'agent': owner})
    record = state['records'][0]
    assert record['status'] == 'completed' and not record.get('incomplete', False)
    steps = record['steps']
    assert any(s['action'] == 'TYPE' and s.get('secretInput', False) for s in steps), 'Missing password interaction'
    assert any(s['action'] == 'TYPE' and not s.get('secretInput', False) for s in steps), 'Missing public-input parameter'
    encoded = json.dumps(record)
    assert 'QA_Public_Input_1357' not in encoded and 'qaSecretFixture7' not in encoded, 'Typed values entered the trace'
    assert all('inputmethod' not in s['packageName'] for s in steps), 'Keyboard events entered the trace'
    assert all(not s.get('keyframe') for s in steps if s['packageName'] == 'app.umbra.fixture.crashylogin'), 'A form screenshot was saved'
    state = b.control('test.teach.extract', {'agent': owner, 'id': record['id']})
    skill = state['skills'][0]
    assert skill.get('status', 'DRAFT') == 'DRAFT' and not skill.get('approvedVersion') and not skill.get('testedVersion')
    assert skill['parameters'] and skill['createdFrom'] == 'demonstration:'+record['id']
    result = {'completed': True, 'typedValuesOmitted': True, 'passwordHandoffRecorded': True, 'keyboardEventsOmitted': True,
              'formKeyframesOmitted': True, 'parameterizedDraftOnly': True, 'steps': len(steps), 'providerCalls': 0}
    out = Path('reports/teach'); out.mkdir(parents=True, exist_ok=True)
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    try:
        if owner is not None: b.control('test.teach.cleanup', {'agent': owner})
        adb('shell', 'am', 'force-stop', 'app.umbra.fixture.crashylogin')
    finally:
        for key, value in [('enabled_accessibility_services', previous_services), ('accessibility_enabled', previous_accessibility)]:
            if value in ('', 'null'): adb('shell', 'settings', 'delete', 'secure', key)
            else: adb('shell', 'settings', 'put', 'secure', key, value)
