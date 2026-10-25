#!/usr/bin/env python3
"""Grant a synthetic folder through Android's picker, then verify scope, restart and revocation.

Uses no root privileges or provider/model calls. Only this fixture's agents and temporary files are
removed, and only its own persisted folder permission is released.
"""
import importlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
import zipfile
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert adb('emu', 'avd', 'name').splitlines()[0].strip() in ('umbra-orb-release-043', 'umbra-proactive-qa-050'), 'Use a disposable QA emulator'
ui = importlib.import_module('ondevice-task-controls')
setup()
b = Bridge()
b.control('status')
setup()
b.control('status')  # Repeated setup must preserve an already authenticated client.
original_ui = b.control('ui.set')
agents = []
folder = 'UmbraQADocuments'+str(time.time_ns())
remote = '/sdcard/Documents/'+folder
assert re.fullmatch(r'/sdcard/Documents/UmbraQADocuments\d+', remote)


def point(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    return (x1+x2)//2, (y1+y2)//2


def open_context(agent):
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'agent/'+agent+'/proactivity')
    for _ in range(20):
        tree = ui.tree()
        target = ui.find('Personal context', tree)
        if target is not None:
            x, y = point(target)
            if 0 < x < 1080 and y > 0:
                adb('shell', 'input', 'tap', str(x), str(y))
                ui.until(lambda: ui.find('Documents folder'))
                return
        anchors = [ui.find(label, tree) for label in ('Goals', 'Suggestions', 'Briefs', 'Watchers', 'Routines', 'Skills', 'Groups', 'Templates')]
        anchor = next((n for n in anchors if n is not None and point(n)[1] > 0), None)
        if anchor is None:
            time.sleep(.5)
            continue
        y = str(point(anchor)[1])
        if ui.find('Templates', tree) is not None:
            adb('shell', 'input', 'swipe', '130', y, '700', y, '400')
        else:
            adb('shell', 'input', 'swipe', '950', y, '130', y, '400')
    raise AssertionError('Personal context tab did not become visible')


try:
    blocked = b.control('test.documents.blocked_create')
    assert blocked == {'blocked': True, 'profilesUnchanged': True, 'sessionsUnchanged': True}, blocked
    with tempfile.TemporaryDirectory(prefix='umbra-document-fixture-') as temp:
        root = Path(temp)
        (root/'invoice.md').write_text('Synthetic research invoice: total 42. QA only.\n')
        (root/'binary.pdf').write_bytes(b'research invoice: this binary document must be skipped')
        (root/'other.txt').write_text('Unrelated text.\n')
        with zipfile.ZipFile(root/'office.docx', 'w', compression=zipfile.ZIP_DEFLATED) as office:
            office.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Synthetic Office research invoice: total 84. QA only.</w:t></w:r></w:p></w:body></w:document>')
        adb('shell', 'mkdir', '-p', remote)
        for path in root.iterdir():
            adb('push', str(path), remote+'/'+path.name)
    for _ in range(2):
        agents.append(b.control('test.documents.create')['agent'])
    owner, other = agents
    if original_ui['providers'] == 0:
        b.control('ui.set', {'onboarded': False})
        pid = b.control('status')['pid']
        adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'agent/'+owner+'/proactivity')
        time.sleep(3)
        assert b.control('status')['pid'] == pid, 'A deep link before onboarding crashed the app'
    b.control('ui.set', {'onboarded': True})
    open_context(owner)
    ui.tap('Documents folder')
    ui.until(lambda: ui.find('Use this folder') is not None or ui.find('Show roots') is not None)
    if ui.find('Documents') is None:
        ui.tap('Show roots')
        model = adb('shell', 'getprop', 'ro.product.model').strip()
        if ui.find(model) is not None:
            ui.tap(model)
        else:
            ui.tap('Internal storage')
    ui.tap('Documents')
    ui.tap(folder)
    ui.tap('Use this folder')
    ui.tap('Allow')
    ui.until(lambda: b.control('test.documents.state', {'agent': owner})['connected'])
    state = b.control('test.documents.state', {'agent': owner})
    assert state['fixtureTree'] and state['persistentRead'], 'The synthetic folder did not receive a persistent read grant'
    assert not state['persistentWrite'], 'Document context persisted write access'
    result = b.control('test.documents.query', {'agent': owner})
    assert len(result) == 2, result
    assert any('Synthetic research invoice' in item['text'] for item in result)
    assert any('Synthetic Office research invoice' in item['text'] for item in result), 'Office text was not extracted'
    assert not b.control('test.documents.query', {'agent': other}), 'Another agent inherited the folder'
    assert not b.control('test.documents.query', {'agent': owner, 'restricted': True}), 'A routine without document scope read the folder'
    legacy = b.control('test.documents.legacy')['agent']
    agents.append(legacy)
    adb('shell', 'am', 'force-stop', 'app.umbra.agent')
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity')
    setup()
    b = Bridge()
    def legacy_stopped():
        state = b.control('test.documents.state', {'agent': legacy})
        return state if not state['running'] else None
    legacy_state = ui.until(legacy_stopped)
    assert not legacy_state['running'] and not legacy_state['hasSession'], 'A blocked legacy profile was not stopped safely'
    assert b.control('test.documents.state', {'agent': owner})['persistentRead']
    assert len(b.control('test.documents.query', {'agent': owner})) == 2, 'Folder access did not survive restart'
    b.control('test.documents.revoke', {'agent': owner})
    assert not b.control('test.documents.query', {'agent': owner}), 'Revocation left document access active'
    result = {'pickerGrant': True, 'readOnly': True, 'officeTextRead': True, 'binarySkipped': True, 'otherAgentDenied': True, 'repeatedBridgeSetup': True,
              'routineScopeEnforced': True, 'restartPersisted': True, 'revocationEnforced': True,
              'blockedCreateLeavesNoProfile': True, 'blockedLegacyProfileDoesNotCrashStartup': True, 'providerCalls': 0}
    result['onboardingDeepLinkDeferred'] = original_ui['providers'] == 0
    out = Path('reports/document-context'); out.mkdir(parents=True, exist_ok=True)
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    try:
        setup()
        b = Bridge()
        for agent in agents:
            b.control('test.documents.cleanup', {'agent': agent})
        b.control('ui.set', {'onboarded': original_ui['onboarded']})
    finally:
        adb('shell', 'rm', '-rf', remote)
