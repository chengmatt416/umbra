#!/usr/bin/env python3
"""Verify task controls on the disposable emulator. Fixtures never execute a shell command or call a model."""
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from umbractl import Bridge, adb, setup

OUT = Path('reports/task-controls')
OUT.mkdir(parents=True, exist_ok=True)


def tree():
    for _ in range(5):
        adb('shell', 'uiautomator', 'dump', '/sdcard/umbra-task-controls.xml', check=False)
        try:
            root = ET.fromstring(adb('shell', 'cat', '/sdcard/umbra-task-controls.xml', check=False))
        except ET.ParseError:
            time.sleep(.5)
            continue
        denied = next((n for n in root.iter('node') if n.get('resource-id') == 'com.android.systemui:id/log_access_dialog_deny_button'), None)
        if denied is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', denied.get('bounds')))
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
            continue
        return root
    raise AssertionError('Android UI hierarchy is unavailable')


def find(label, root=None):
    root = root if root is not None else tree()
    return next((n for n in reversed(list(root.iter('node'))) if label.casefold() in ((n.get('text') or '').casefold(), (n.get('content-desc') or '').casefold())), None)


def until(check, seconds=35):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if isinstance(result, ET.Element) or result:
            return result
        time.sleep(.4)
    raise AssertionError('Task controls did not reach the expected state')


def tap(label):
    node = until(lambda: find(label))
    assert node.get('enabled') != 'false', label
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def screenshot(name):
    adb_path = os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
    (OUT / (name + '.png')).write_bytes(subprocess.check_output([adb_path, '-s', os.environ['ANDROID_SERIAL'], 'exec-out', 'screencap', '-p']))
    (OUT / (name + '.xml')).write_text(ET.tostring(tree(), encoding='unicode'))


def open_session(session_id):
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'session', session_id)


def bridge():
    setup()
    return Bridge()


def main():
    assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556', 'Use only the disposable QA emulator'
    assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name'), 'Wrong emulator'
    b = bridge()
    old_ui = b.control('ui.set')
    old_mode = b.control('approval_mode')['mode']
    task = None
    try:
        b.control('ui.set', {'mode': 'simple', 'onboarded': True})
        b.control('test.command.clear')
        task = b.control('test.task.fixture', {'summary': '**Synthetic task result:** implementation and tests passed.\n\n- `Markdown` rendering verified.'})['id']
        open_session(task)
        until(lambda: find('Result summary'))
        assert find('Synthetic task result: implementation and tests passed.') is not None
        assert find('Markdown rendering verified.') is not None
        assert find('**Synthetic task result:** implementation and tests passed.') is None
        screenshot('result-summary')

        tap('AI usage limit')
        tap('Limit usage')
        assert find('API-equivalent USD') is not None
        screenshot('usage-options')
        tap('Save')
        until(lambda: b.control('session.get', {'id': task})['usage_limit'] is not None)
        assert b.control('session.get', {'id': task})['usage_limit']['unit'] == 'API_USD'

        b.control('approval_mode', {'mode': 'balanced'})
        b.control('test.command.review', {'id': task})
        until(lambda: b.control('approvals.list'))
        open_session(task)
        tap('Approve once')
        until(lambda: not b.control('approvals.list'))
        assert b.control('test.command.grants') == []

        b.control('test.command.review', {'id': task})
        until(lambda: b.control('approvals.list'))
        open_session(task)
        tap('Approve this prefix forever')
        assert b.control('test.command.grants') == [], 'A first tap must not create a permanent grant'
        assert b.control('approvals.list'), 'The command must still be waiting for confirmation'
        until(lambda: any('Always allow literal commands beginning' in n.get('text', '') for n in tree().iter('node')))
        screenshot('prefix-confirmation')
        tap('Approve this prefix forever')
        until(lambda: not b.control('approvals.list'))
        until(lambda: [r['prefix'] for r in b.control('test.command.grants')] == ['git status'])

        adb('shell', 'am', 'force-stop', 'app.umbra.agent')
        open_session(task)
        b = bridge()
        assert [r['prefix'] for r in b.control('test.command.grants')] == ['git status'], 'Permanent approval must survive restart'
        assert b.control('session.get', {'id': task})['usage_limit']['unit'] == 'API_USD'
        b.control('test.command.review', {'id': task})
        time.sleep(2)
        assert not b.control('approvals.list'), 'Exact literal prefix should be remembered'
        b.control('test.command.review', {'id': task, 'command': 'git status --short; echo fixture'})
        request = until(lambda: b.control('approvals.list'))[0]
        assert request['level'] == 'HIGH'
        b.control('approvals.resolve', {'id': request['id'], 'decision': 'deny'})
        print('PASS: summary, usage settings, approve once, two-step permanent prefix, restart persistence, compound-command refusal')
    finally:
        b.control('test.command.clear')
        if task:
            for request in b.control('approvals.list'):
                if request['session'] == task:
                    b.control('approvals.resolve', {'id': request['id'], 'decision': 'deny'})
            b.control('session.delete', {'id': task})
        b.control('approval_mode', {'mode': old_mode})
        b.control('ui.set', {'mode': old_ui['mode'], 'onboarded': old_ui['onboarded']})


if __name__ == '__main__':
    main()
