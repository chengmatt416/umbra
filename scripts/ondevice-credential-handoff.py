#!/usr/bin/env python3
"""Exercise the native credential panel with synthetic input on the disposable QA emulator.

No model is started and no account is contacted. UiAutomation must not run while the accessibility
overlay is open: its connection would detach the very service hosting the panel.
"""
import importlib
import json
import os
from pathlib import Path
import re
import time
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert adb('emu', 'avd', 'name').splitlines()[0].strip() in ('umbra-orb-release-043', 'umbra-proactive-qa-050')
assert adb('shell', 'pm', 'path', 'app.umbra.fixture.crashylogin', check=False).strip()
verified_links = os.environ.get('UMBRA_TEST_VERIFIED_LINKS') == '1'
ui = importlib.import_module('ondevice-task-controls')
setup()
b = Bridge()
b.control('status')  # Authenticate before the bridge's short unauthenticated idle timeout.
original = {key: adb('shell', 'settings', 'get', 'secure', key).strip()
            for key in ('enabled_accessibility_services', 'accessibility_enabled')}
component = 'app.umbra.agent/app.umbra.platform.android.UmbraAccessibilityService'
services = [] if original['enabled_accessibility_services'] in ('', 'null') else original['enabled_accessibility_services'].split(':')
if component not in services:
    services.append(component)
task = None
value = 'NativeQaCredential7'


def until(check, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        found = check()
        if found:
            return found
        time.sleep(.2)
    raise AssertionError('Native credential panel did not reach its expected state')


def state():
    return b.control('test.handoff.state', {'id': task})


def panel():
    return b.control('test.handoff.ui', {'id': task})


def tap(name):
    bounds = until(lambda: panel().get(name))
    x, y, width, height = bounds
    adb('shell', 'input', 'tap', str(x+width//2), str(y+height//2))


def open_panel(request):
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.credentials.CredentialHandoffActivity', '--es', 'request', request)
    until(lambda: panel().get('positive'))
    assert panel()['secure'] == [1], 'Native credential panel is not screenshot protected'


try:
    results = []
    for stale, restart in ((False, False), (True, False), (False, True)):
        adb('shell', 'am', 'force-stop', 'app.umbra.fixture.crashylogin')
        adb('shell', 'am', 'start', '-n', 'app.umbra.fixture.crashylogin/.MainActivity')
        ui.tree()  # Dismiss any log-access dialog before enabling/using the service.
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
        adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        task = b.control('test.handoff.create')['id']
        deadline = time.monotonic()+25
        while True:
            try:
                request = b.control('test.handoff.request', {'id': task})
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.5)
        if stale:
            adb('shell', 'input', 'tap', *map(str, request['username']))
            adb('shell', 'input', 'text', 'changed-user')
            adb('shell', 'input', 'keyevent', '4')
        link = re.search(r'(?:umbra://credential-handoff/|https://umbra-agent\.pages\.dev/credential-handoff#)[A-Za-z0-9_-]+', request['link']).group(0)
        if verified_links:
            assert link.startswith('https:'), 'Umbra did not select its verified HTTPS link'
        nonce = link.split('#', 1)[1] if link.startswith('https:') else link.rsplit('/', 1)[1]
        link = 'https://umbra-agent.pages.dev/credential-handoff#'+nonce
        route = [] if verified_links else ['-p', 'app.umbra.agent']
        adb('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', link, *route)
        until(lambda: panel().get('input'))
        assert state()['linkUsed'], 'The local link was not consumed'
        assert panel()['secure'] == [1]
        tap('input')
        adb('shell', 'input', 'text', value)
        # Read bounds again after the keyboard changes layout. Never dump the hierarchy here.
        tap('positive')
        until(lambda: state().get('state') == 'injected')
        time.sleep(.8)
        injected = state()
        assert injected['protected'] and injected['timelineClean']
        assert injected['fieldClear'] == stale, 'A changed form was filled, or the unchanged field was not filled'
        cipher = adb('shell', 'run-as', 'app.umbra.agent', 'cat', 'shared_prefs/credential-handoff-values.xml')
        assert value not in cipher and 'HANDOFF_' in cipher, 'Credential was not stored as ciphertext'
        # No provider calls: end the synthetic source before the user returns control.
        b.control('test.handoff.deleteSource' if restart else 'test.handoff.stop', {'id': task})
        if stale:
            tap('neutral')  # The rejected fill keeps its at-most-once panel open for inspection.
        if restart:
            adb('shell', 'am', 'force-stop', 'app.umbra.agent')
            adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity')
            # Force-stop unbinds accessibility. Re-enable it as the user does in Android settings.
            others = ':'.join(s for s in services if s != component)
            if others:
                adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', others)
            else:
                adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services')
            adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
            adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
            setup()
            b = Bridge()
            assert state()['protected'], 'Deleting/restarting lost protection for a filled credential'
        open_panel(injected['request'])
        assert 'input' not in panel(), 'An injected/uncertain request allowed another fill'
        if restart:
            # Rebinding accessibility may replace window IDs. Manually clear the native field;
            # an old field reference must never authorize input into an unrecognized replacement.
            tap('neutral')
            point = b.control('test.handoff.field', {'id': task})
            adb('shell', 'input', 'tap', *map(str, point))
            adb('shell', 'input', 'keyevent', '123')
            adb('shell', 'input', 'keyevent', *(['67']*(len(value)+5)))
            adb('shell', 'input', 'keyevent', '4')
            open_panel(injected['request'])
            tap('positive')
        else:
            tap('negative')  # Native Clear and cancel.
        until(lambda: not state()['pending'])
        assert state()['timelineClean']
        if restart:
            cipher = adb('shell', 'run-as', 'app.umbra.agent', 'cat', 'shared_prefs/credential-handoff-values.xml')
            assert 'HANDOFF_'+injected['request'].upper() not in cipher, 'Deleted task retained encrypted input after completion'
        b.control('test.handoff.cleanup', {'id': task})
        task = None
        results.append({'changedForm': stale, 'restartedWithDeletedSource': restart, 'filled': not stale, 'clearedBeforeReturn': True,
                        'secureWindow': True, 'ciphertextOnly': True, 'timelineClean': True})
    result = {'cases': results, 'providerCalls': 0, 'verifiedDefaultRoute': verified_links}
    out = Path('reports/credential-handoff'); out.mkdir(parents=True, exist_ok=True)
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    try:
        if task is not None:
            adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
            adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
            adb('shell', 'am', 'force-stop', 'app.umbra.fixture.crashylogin')
            adb('shell', 'am', 'start', '-n', 'app.umbra.fixture.crashylogin/.MainActivity')
            setup()
            b = Bridge()
            deadline = time.monotonic()+15
            while True:
                try:
                    b.control('test.handoff.cleanup', {'id': task})
                    break
                except RuntimeError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(.5)
        adb('shell', 'am', 'force-stop', 'app.umbra.fixture.crashylogin')
    finally:
        for key, previous in original.items():
            if previous in ('', 'null'):
                adb('shell', 'settings', 'delete', 'secure', key)
            else:
                adb('shell', 'settings', 'put', 'secure', key, previous)
