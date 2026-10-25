#!/usr/bin/env python3
"""Provider-free QA: proactive entries, keyboard editing and direct child-task navigation."""
import importlib
import json
import os
from pathlib import Path
import re
import subprocess
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert adb('emu', 'avd', 'name').splitlines()[0].strip() in ('umbra-orb-release-043', 'umbra-proactive-qa-050')
ui = importlib.import_module('ondevice-task-controls')
setup()
b = Bridge()
original = b.control('ui.set')
assert original['providers'] == 0, 'Use a provider-free QA app'
agent = None
seeded = False


def tap_node(node):
    assert node is not None and node.get('enabled') != 'false'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    assert x2 > x1 and y2 > y1
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def open_agent():
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'agent/'+agent)
    ui.until(lambda: ui.find('Documents QA navigation'))


def prefix(label):
    return next((node for node in ui.tree().iter('node') if node.get('text', '').startswith(label)), None)


def home_entry(label):
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'home')
    def target():
        tree = ui.tree()
        row = next((node for node in tree.iter('node') if node.get('text', '').startswith('Documents QA navigation · ')), None)
        if row is None:
            adb('shell', 'input', 'swipe', '900', '1700', '900', '650', '400')
            return None
        left, top, right, bottom = map(int, re.findall(r'\d+', row.get('bounds')))
        fields = [node for node in tree.iter('node') if node.get('class') == 'android.widget.EditText']
        safe_bottom = min((int(re.findall(r'\d+', node.get('bounds'))[1]) for node in fields), default=2200) - 40
        for node in tree.iter('node'):
            if node.get('text') != label and node.get('content-desc') != label:
                continue
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            if left <= (x1+x2)//2 <= right and bottom <= (y1+y2)//2 <= top + 300 and y2 < safe_bottom:
                return node
        adb('shell', 'input', 'swipe', '900', '1700', '900', '650', '400')
        return None
    tap_node(ui.until(target))


try:
    agent = b.control('test.documents.create')['agent']
    links = b.control('test.note_ui.seed', {'agent': agent})
    seeded = True
    b.control('ui.set', {'onboarded': True, 'homeAgents': True})
    open_agent()
    ui.tap('Suggestions')
    ui.until(lambda: ui.find('UI suggestion fixture'))
    open_agent()
    ui.tap('Briefs')
    ui.until(lambda: ui.find('UI brief fixture'))
    ui.until(lambda: ui.find('Morning brief'))
    home_entry('Suggestions')
    ui.until(lambda: ui.find('UI suggestion fixture'))
    home_entry('Briefs')
    ui.until(lambda: ui.find('UI brief fixture'))
    open_agent()
    for title, key in [('UI child task · ', 'child'), ('UI nested task · ', 'nested')]:
        tap_node(ui.until(lambda: prefix(title)))
        ui.until(lambda: b.control('test.note_ui.state', {'agent': agent})['focus'] == links[key])
        open_agent()
    ui.tap('Suggestions')
    ui.tap('Review UI instruction')
    field = ui.until(lambda: next((node for node in ui.tree().iter('node') if node.get('class') == 'android.widget.EditText'), None))
    assert field.get('text') == 'Synthetic original instruction', field.attrib
    tap_node(field)
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'text', '_edited')
    ui.until(lambda: ui.find('Synthetic original instruction_edited'))
    out = Path('reports/note-ui'); out.mkdir(parents=True, exist_ok=True)
    image = subprocess.check_output([os.path.expanduser('~/Library/Android/sdk/platform-tools/adb'), '-s', 'emulator-5556', 'exec-out', 'screencap', '-p'])
    (out/'review-keyboard.png').write_bytes(image)
    ui.tap('Confirm')  # The real keyboard remains open; this must hit the visible confirmation.
    def adopted():
        tasks = b.control('test.note_ui.state', {'agent': agent})['tasks']
        return next((task for task in tasks if task['request'].startswith('[User adopted opportunity qa-note-ui]')), None)
    task = ui.until(adopted)
    assert task['request'].endswith('Synthetic original instruction_edited'), task['request']
    assert not b.control('test.note_ui.state', {'agent': agent})['usageCounts']
    result = {'homeSuggestionsEntry': True, 'homeBriefsEntry': True, 'chatSuggestionsEntry': True, 'chatBriefsEntry': True,
              'childTaskOpens': True, 'nestedTaskOpens': True, 'plainInstructionEditor': True, 'confirmationWithKeyboardOpen': True,
              'editedInstructionSaved': True, 'usageCountsRemainOff': True, 'providerCalls': 0}
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
except BaseException:
    out = Path('reports/note-ui'); out.mkdir(parents=True, exist_ok=True)
    try:
        (out/'failure.png').write_bytes(subprocess.check_output([os.path.expanduser('~/Library/Android/sdk/platform-tools/adb'), '-s', 'emulator-5556', 'exec-out', 'screencap', '-p']))
        import xml.etree.ElementTree as ET
        (out/'failure.xml').write_text(ET.tostring(ui.tree(), encoding='unicode'))
    except Exception:
        pass
    raise
finally:
    setup()
    b = Bridge()
    if agent is not None:
        b.control('test.note_ui.cleanup' if seeded else 'test.documents.cleanup', {'agent': agent})
    b.control('ui.set', {'onboarded': original['onboarded'], 'homeAgents': original['homeAgents']})
