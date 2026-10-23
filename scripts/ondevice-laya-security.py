#!/usr/bin/env python3
"""Verify Android HTTP policy and Laya review against a local protocol fixture; no model accounts or actions."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
from umbractl import Bridge, adb, setup


class Fixture(BaseHTTPRequestHandler):
    risk = 'MEDIUM'
    injection = 'CLEAR'
    calls = 0

    def do_POST(self):
        assert self.path == '/v1/systemone'
        size = int(self.headers['Content-Length'])
        assert 0 < size < 16_384
        payload = json.loads(self.rfile.read(size))
        assert set(payload['questions']) == {'risk', 'category', 'injection'}
        Fixture.calls += 1
        body = json.dumps({'answers': {
            'risk': {'choice': Fixture.risk, 'confidence': .99},
            'category': {'choice': 'SYSTEM_CONFIGURATION', 'confidence': .99},
            'injection': {'choice': Fixture.injection, 'confidence': .99},
        }}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


assert os.environ.get('ANDROID_SERIAL'), 'Select a disposable test emulator'
assert 'tcp:8765' not in adb('reverse', '--list'), 'Preserve an existing Laya reverse mapping'
setup()
bridge = Bridge()
original = bridge.control('approval_mode')['mode']
server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    adb('reverse', 'tcp:8765', 'tcp:' + str(server.server_port))
    for risk_index, risk in enumerate(('MEDIUM', 'HIGH', 'CRITICAL')):
        Fixture.risk = risk
        for mode_index, mode in enumerate(('CAUTIOUS', 'BALANCED', 'AUTONOMOUS')):
            bridge.control('approval_mode', {'mode': mode})
            result = bridge.control('laya.security.check')
            assert result['loopbackAllowed'] and not result['externalHttpAllowed'], result
            assert result['risk'] == risk, result
            assert result['decision'] == ('Ask' if risk_index >= mode_index else 'Allow'), result
            print(f'{mode}: {risk} -> {result["decision"]}', flush=True)
    Fixture.risk = 'NONE'
    Fixture.injection = 'SUSPECTED'
    for mode in ('CAUTIOUS', 'BALANCED', 'AUTONOMOUS'):
        bridge.control('approval_mode', {'mode': mode})
        result = bridge.control('laya.security.check')
        assert result['risk'] == 'CRITICAL' and result['decision'] == 'Ask', result
    assert Fixture.calls == 12
    print('Passed: real Android loopback HTTP, external HTTP blocked, 9 Settings/risk combinations and 3 injection reviews.')
finally:
    bridge.control('approval_mode', {'mode': original})
    adb('reverse', '--remove', 'tcp:8765')
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
