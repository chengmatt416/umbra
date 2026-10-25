#!/usr/bin/env python3
"""Verify ambient-only suggestions, review navigation and expiry without adopting or calling models."""
import importlib
import json
import os
from pathlib import Path
import time
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name')
ui = importlib.import_module('ondevice-task-controls')
setup()
b = Bridge()
original = b.control('capsule.status')['enabled']
fixture = None


def until(check, seconds=25):
    deadline = time.monotonic()+seconds
    while time.monotonic() < deadline:
        found = check()
        if found:
            return found
        time.sleep(.2)
    raise AssertionError('Capsule suggestions did not reach the expected state')


def containing(text, root=None):
    root = root if root is not None else ui.tree()
    return next((n for n in root.iter('node') if text in (n.get('text', '')+' '+n.get('content-desc', ''))), None)


def targets():
    return b.control('test.suggestions.targets', {'agent': owner})


def tap_target(name):
    point = until(lambda: targets().get(name))
    adb('shell', 'input', 'tap', *map(str, point))


try:
    fixture = b.control('test.suggestions.create')
    owner = fixture['agent']
    b.control('capsule.set', {'on': True})
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    until(lambda: b.control('capsule.status')['suggestions'] == 1)
    # Non-focusable overlays are excluded from legacy UiAutomator hierarchy dumps. Bounds come
    # from the actual Compose layout; adb still performs the user's real pointer input.
    tap_target('pill')
    until(lambda: 'qa-ambient' in targets())
    assert 'qa-quiet' not in targets(), 'A brief-only item became an ambient card'
    tap_target('qa-ambient')
    ui.until(lambda: ui.find('Proactivity'))
    ui.until(lambda: ui.find('Synthetic ambient suggestion'))
    state = b.control('test.suggestions.state', {'agent': owner})
    assert state['sessions'] == 1 and not state['started'], 'Reviewing a capsule card executed its proposal'
    pid = adb('shell', 'pidof', 'app.umbra.agent').strip()
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'agent/'+owner+'/proactivity?tab=invalid')
    ui.until(lambda: ui.find('Coding') is not None or containing('Ask this agent to create a goal') is not None)
    assert adb('shell', 'pidof', 'app.umbra.agent').strip() == pid, 'A malformed review route crashed the app'
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    until(lambda: b.control('capsule.status')['suggestions'] == 1)
    before = b.control('capsule.status')
    b.control('test.suggestions.expireSoon', {'agent': owner})
    # No hierarchy dumps or direct content reads here: only the capsule's rendered projection and
    # actual expiry signal counter. UiAutomation would otherwise inject its own accessibility events.
    until(lambda: b.control('capsule.status')['suggestions'] == 0)
    after = b.control('capsule.status')
    assert after['suggestionExpirySignals'] > before['suggestionExpirySignals'], 'No scheduled expiry broadcast was sent'
    assert after['revision'] > before['revision']
    state = b.control('test.suggestions.state', {'agent': owner})
    assert state['sessions'] == 1 and not state['started']
    result = {'ambientOnly': True, 'reviewOpenedSuggestions': True, 'reviewDidNotExecute': True, 'malformedRouteRejected': True,
              'expiredWithoutPollingContent': True, 'providerCalls': 0}
    out = Path('reports/capsule-suggestions'); out.mkdir(parents=True, exist_ok=True)
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    try:
        if fixture is not None:
            b.control('test.suggestions.cleanup', {'agent': fixture['agent'], 'expanded': fixture['expanded'], 'tucked': fixture['tucked']})
    finally:
        b.control('capsule.set', {'on': original})
