#!/usr/bin/env python3
"""Real Android node pairing, every mode, pinned-TLS RFB and takeover against a disposable companion.

Requires a signed debug build, a running emulator, and node-go/build/umbra-node. Uses synthetic pixels/input only;
does not grant host screen permissions, use provider credentials, install a login service, or send account messages.
"""
import base64
import concurrent.futures
import http.client
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from umbractl import Bridge, adb, setup

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'reports/remote-nodes'
OUT.mkdir(parents=True, exist_ok=True)

def tree():
    for _ in range(5):
        adb('shell', 'uiautomator', 'dump', '/sdcard/umbra-node-qa.xml')
        root = ET.fromstring(adb('shell', 'cat', '/sdcard/umbra-node-qa.xml'))
        denied = next((n for n in root.iter('node') if n.get('resource-id') == 'com.android.systemui:id/log_access_dialog_deny_button'), None)
        if denied is None:
            return root
        adb('shell', 'input', 'tap', *map(str, bounds(denied)))
    raise AssertionError('Android UI hierarchy remained behind a log-access dialog')
def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    return ((x1 + x2) // 2, (y1 + y2) // 2)
def find(value, root=None):
    root = root if root is not None else tree()
    return next((n for n in root.iter('node') if value in (n.attrib.get('text'), n.attrib.get('content-desc'))), None)
def tap(value):
    node = until(lambda: find(value))
    assert node is not None, f'UI element missing: {value}'
    adb('shell', 'input', 'tap', *map(str, bounds(node)))
def shot(name):
    (OUT / (name + '.png')).write_bytes(subprocess.check_output([os.path.expanduser('~/Library/Android/sdk/platform-tools/adb'), '-s', os.environ['ANDROID_SERIAL'], 'exec-out', 'screencap', '-p']))
    (OUT / (name + '.xml')).write_text(adb('shell', 'cat', '/sdcard/umbra-node-qa.xml'))
def until(check, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if isinstance(value, ET.Element) or value:
            return value
        time.sleep(.3)
    raise AssertionError('Timed out waiting for node state')
class Local(http.client.HTTPConnection):
    def __init__(self, home):
        super().__init__('node', timeout=10)
        self.home = home
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.settimeout(10)
        self.sock.connect(str(self.home / 'control.sock'))
def control(home, action=None):
    client = Local(home)
    try:
        client.request('POST' if action else 'GET', '/action' if action else '/state', json.dumps(action).encode() if action else None)
        reply = client.getresponse()
        raw = reply.read()
        assert reply.status == 200, raw.decode()
        return json.loads(raw)
    finally:
        client.close()

def main():
    assert os.environ.get('ANDROID_SERIAL'), 'Set ANDROID_SERIAL to the disposable test emulator'
    setup()
    bridge = Bridge()
    original = bridge.control('ui.set', {})
    assert original['onboarded'], 'Complete onboarding before this test'
    home = Path(tempfile.mkdtemp(prefix='umbra-node-device-', dir='/tmp'))
    binary = ROOT / 'node-go/build/umbra-node'
    fake = home / 'bin'
    fake.mkdir()
    shutil.copy2(ROOT / 'scripts/fixtures/node-vnc.py', fake / 'x11vnc')
    (fake / 'x11vnc').chmod(0o755)
    log = home / 'input.jsonl'
    env = dict(os.environ, PATH=str(fake) + os.pathsep + os.environ['PATH'], UMBRA_NODE_TEST_INPUT_LOG=str(log))
    server = subprocess.Popen([str(binary), 'serve', '--home', str(home), '--port', '0', '--pair'], env=env, stdout=(home / 'server.log').open('w'), stderr=subprocess.STDOUT)
    node_id = None
    result = {}
    try:
        until(lambda: (home / 'control.sock').exists())
        state = control(home)
        node_id = state['config']['nodeId']
        for mode in ('simple', 'power', 'dev'):
            bridge.control('ui.set', {'mode': mode})
            adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'home')
            tap('Open the menu')
            assert find('Nodes') is not None, f'Nodes missing in {mode} mode'
            shot(mode + '-menu')
            tap('Nodes')
            assert find('Add node (scan QR)') is not None
            shot(mode + '-nodes')
            adb('shell', 'input', 'keyevent', '4')
            result[mode] = 'Nodes menu and add flow visible'
        tap('Open the menu'); tap('Nodes'); tap('Add node (scan QR)'); tap('Enter 12-character code')
        invite = state['invitation']
        invite['addresses'] = [{'host': '10.0.2.2', 'port': state['addresses'][0]['port'], 'kind': 'lan'}]
        uri = 'umbra-node://pair?data=' + base64.urlsafe_b64encode(json.dumps(invite, separators=(',', ':')).encode()).decode().rstrip('=')
        tap('Paste node invitation link')
        for offset in range(0, len(uri), 32):
            adb('shell', 'input', 'text', shlex.quote(uri[offset:offset + 32]))
            prefix = uri[:offset + 32]
            until(lambda: any(n.attrib.get('text') == prefix for n in tree().iter('node')))
        adb('shell', 'input', 'keyevent', '4')
        tap('Read invitation')
        assert find('Found “' + state['name'] + '”') is not None
        shot('invitation-confirm')
        tap('Pair')
        pending = until(lambda: control(home).get('pending'))
        assert not control(home)['phones'], 'Trusted before host confirmation'
        control(home, {'action': 'pair-decision', 'id': pending['id'], 'allow': True})
        until(lambda: any(n['id'] == node_id for n in bridge.control('nodes.list')))
        until(lambda: find('View screen') is not None)
        shot('paired-detail-jobs-only')
        control(home, {'action': 'screen', 'backend': 'x11vnc'})
        # Re-enter detail so the foreground preflight runs immediately.
        adb('shell', 'input', 'keyevent', '4')
        tap(state['name'])
        until(lambda: any(n['id'] == node_id and n.get('info', {}).get('screen', {}).get('available') for n in bridge.control('nodes.list')))
        tap('View screen')
        until(lambda: find('Live remote desktop') is not None)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', find('Live remote desktop').attrib['bounds']))
        assert x2 - x1 > 500 and .5 < (y2 - y1) / (x2 - x1) < .65, 'Remote framebuffer must fill the 16:9 viewer'
        shot('remote-screen-watch')
        assert not log.exists(), 'Viewing unexpectedly sent desktop input'
        tap('Take over')
        until(lambda: find('You control this screen · agent access is paused') is not None)
        image = find('Live remote desktop')
        adb('shell', 'input', 'tap', *map(str, bounds(image)))
        until(lambda: log.exists() and '"buttons": 1' in log.read_text())
        shot('remote-screen-takeover')
        tap('Keyboard')
        tap('Text to type on the computer')
        adb('shell', 'input', 'text', 'UmbraQA')
        tap('Type text')
        until(lambda: '"key": 85' in log.read_text())
        result['pairing'] = 'Real Android keystore and Go host approval over pinned TLS'
        result['screen'] = 'Synthetic framebuffer decoded in app; watch is input-free; takeover pointer and keyboard verified'
        result['hostInputEvents'] = len(log.read_text().splitlines())
        (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    finally:
        # Only the node created by this test is unpaired. Other sessions, nodes and credentials are preserved.
        if node_id:
            try:
                bridge.control('nodes.unpair', {'id': node_id})
            except Exception as e:
                print('Test node cleanup:', e)
        bridge.control('ui.set', {'mode': original['mode']})
        server.terminate()
        try:
            server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server.kill(); server.wait()
        shutil.rmtree(home)

if __name__ == '__main__':
    main()
