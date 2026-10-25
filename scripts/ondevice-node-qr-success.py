#!/usr/bin/env python3
"""Decode a real companion invitation through the QA emulator's virtual-scene camera."""
import base64
import importlib
import os
import math
import struct
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from umbractl import ADB, Bridge, adb, setup

fixture = importlib.import_module('ondevice-remote-nodes')
ui = importlib.import_module('ondevice-task-controls')
assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name')
setup()
b = Bridge()
b.control('status')  # Authenticate before camera/UI setup; unauthenticated sockets expire quickly.
previous_services = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
previous_accessibility = adb('shell', 'settings', 'get', 'secure', 'accessibility_enabled').strip()
root = Path(__file__).resolve().parents[1]
home = Path(tempfile.mkdtemp(prefix='umbra-qr-success-', dir='/tmp'))
server = subprocess.Popen([str(root/'node-go/build/umbra-node'), 'serve', '--home', str(home), '--port', '0', '--pair'], stdout=(home/'server.log').open('w'), stderr=subprocess.STDOUT)
restore_camera = None
try:
    services = [] if previous_services in ('', 'null') else previous_services.split(':')
    component = 'app.umbra.agent/app.umbra.platform.android.UmbraAccessibilityService'
    if component not in services: services.append(component)
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    fixture.until(lambda: (home/'control.sock').exists())
    state = fixture.control(home)
    image = home/'invitation.png'
    image.write_bytes(base64.b64decode(state['qr'].split(',', 1)[1]))
    adb('emu', 'virtualscene-image', 'wall', str(image))
    adb('emu', 'virtualscene-image', 'table')
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'nodes')
    ui.tap('Add node (scan QR)')
    ui.until(lambda: ui.find('Enter 12-character code'))
    pid = adb('shell', 'pidof', '-s', 'app.umbra.agent').strip()
    expected = 'Found “'+state['name']+'”'
    out = root/'reports/qr-success'; out.mkdir(parents=True, exist_ok=True)
    try:
        if os.environ.get('UMBRA_QR_GRPC_PORT'):
            import grpc
            port = int(os.environ['UMBRA_QR_GRPC_PORT'])
            assert port == 8556, 'Use only the QA emulator control port'
            discovery = Path.home()/'Library/Caches/TemporaryItems/avd/running'
            token = None
            for path in discovery.glob('pid_*.ini'):
                values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
                if values.get('grpc.port') == str(port): token = values.get('grpc.token')
            assert token, 'Start the QA emulator with -grpc 8556 -grpc-use-token'
            with grpc.insecure_channel('127.0.0.1:8556') as channel:
                rotate = channel.unary_unary('/android.emulation.control.EmulatorController/rotateVirtualSceneCamera')
                get_model = channel.unary_unary('/android.emulation.control.EmulatorController/getPhysicalModel')
                set_model = channel.unary_unary('/android.emulation.control.EmulatorController/setPhysicalModel')
                metadata = (('authorization', 'Bearer '+token),)
                original_position = get_model(b'', metadata=metadata, timeout=5)
                original_rotation = get_model(b'\x08\x01', metadata=metadata, timeout=5)
                restore_camera = (token, original_position, original_rotation)
                found = False
                # The stock scene's posters are in the dining room behind the initial living-room camera.
                positions = [(0., 0., 0.)] if os.environ.get('UMBRA_QR_FRONT_POSTER') == '1' else [(-2.3, .3, 2.7), (-2.8, .3, 1.85), (-1.8, .3, 2.4)]
                for pose, coordinates in enumerate(positions):
                    value = b'\x0a\x0c' + struct.pack('<fff', *coordinates)
                    set_model(b'\x1a'+bytes([len(value)])+value, metadata=metadata, timeout=5)
                    time.sleep(1)
                    for index in range(24):
                        if b.control('test.qr.state', {'name': state['name']})['found']:
                            found = True; break
                        if index in (0, 6, 12, 18):
                            (out/f'pose-{pose}-{index}.png').write_bytes(subprocess.check_output([ADB, '-s', os.environ['ANDROID_SERIAL'], 'exec-out', 'screencap', '-p']))
                        rotate(b'\x15'+struct.pack('<f', math.pi/12), metadata=metadata, timeout=5)
                        time.sleep(.5)
                    if found: break
                assert found, 'The stock virtual-scene poster was not visible to the camera'
        else:
            ui.until(lambda: ui.find(expected), seconds=40)
    finally:
        (out/'camera.png').write_bytes(subprocess.check_output([ADB, '-s', os.environ['ANDROID_SERIAL'], 'exec-out', 'screencap', '-p']))
    assert adb('shell', 'pidof', '-s', 'app.umbra.agent').strip() == pid
    assert ui.find(expected) is not None, 'The decoded invitation confirmation is missing'
    import json
    result = {'realQrDecoded': True, 'scanConfirmationVisible': True, 'sameAppProcess': True}
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    cleanup_errors = []
    def cleanup(action):
        try: action()
        except Exception as error: cleanup_errors.append(type(error).__name__)
    def restore_pose():
        if restore_camera is None: return
        import grpc
        token, position, rotation = restore_camera
        with grpc.insecure_channel('127.0.0.1:8556') as channel:
            setter = channel.unary_unary('/android.emulation.control.EmulatorController/setPhysicalModel')
            for value in (position, rotation): setter(value, metadata=(('authorization', 'Bearer '+token),), timeout=5)
    cleanup(restore_pose)
    cleanup(lambda: adb('emu', 'virtualscene-image', 'wall'))
    cleanup(lambda: adb('emu', 'virtualscene-image', 'table'))
    cleanup(lambda: adb('shell', 'input', 'keyevent', '4'))
    server.terminate()
    try: server.wait(timeout=10)
    except subprocess.TimeoutExpired: server.kill(); server.wait(timeout=10)
    cleanup(lambda: shutil.rmtree(home))
    for key, value in [('enabled_accessibility_services', previous_services), ('accessibility_enabled', previous_accessibility)]:
        if value in ('', 'null'): cleanup(lambda key=key: adb('shell', 'settings', 'delete', 'secure', key))
        else: cleanup(lambda key=key, value=value: adb('shell', 'settings', 'put', 'secure', key, value))
    if cleanup_errors: raise RuntimeError('QA fixture cleanup failed: '+', '.join(cleanup_errors))
