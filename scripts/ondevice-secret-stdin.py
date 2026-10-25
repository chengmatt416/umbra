#!/usr/bin/env python3
"""Credentials reach guest commands and omp through stdin, never a file (security review item E).

1. A guest command started with secrets sees them exactly (quotes, $(), newlines) and no umbra-exec .env file exists.
2. A real omp session for a provider that needs a key sends that key to the local scripted model server.
Uses the disposable QA emulator and a local scripted model; settings and test sessions are restored/removed.
"""
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, 'scripts')
from umbractl import Bridge, adb, setup

KEY = 'sk-umbra-stdin-check-7f3a'
seen = []


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        seen.append(self.headers.get('Authorization', ''))
        self.rfile.read(int(self.headers['Content-Length']))
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for d, f in [({'role': 'assistant', 'content': 'Key check done.'}, None), ({}, 'stop')]:
            chunk = {'id': 'k', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'fake-model', 'choices': [{'index': 0, 'delta': d, 'finish_reason': f}]}
            self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()


assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556', 'Use the disposable QA emulator'
setup()
b = Bridge()
tricky = "quote ' \" $(touch /tmp/never) `id` \\ and\na newline"
out = b.control('guest.exec_secrets', {
    'argv': ['bash', '-c', 'printf "%s|%s|" "$UMBRA_T1" "$UMBRA_T2"; ls /tmp | grep -c "umbra-exec-.*env" || true; test -e /tmp/never && echo RAN; true'],
    'secrets': {'UMBRA_T1': 'sk-test-1', 'UMBRA_T2': tricky},
}, timeout=90)
assert out['exit'] == 0, out
assert out['stdout'] == f'sk-test-1|{tricky}|0\n', out

server = ThreadingHTTPServer(('127.0.0.1', 54832), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:54832', 'tcp:54832')
configured = False
created = None
try:
    b.control('test.provider.begin', {'base_url': 'http://127.0.0.1:54832/v1', 'key': KEY})
    configured = True
    created = b.control('session.create', {'request': 'Say done'})['id']
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        state = b.control('session.get', {'id': created})['state']
        if state in ('COMPLETED', 'FAILED', 'STOPPED'):
            break
        time.sleep(.5)
    assert state == 'COMPLETED', b.control('session.events', {'id': created})
    assert seen and all(h == f'Bearer {KEY}' for h in seen), seen
    leftovers = b.control('guest.exec_root', {'argv': ['bash', '-c', 'ls /tmp | grep -c "umbra-omp-.*env" || true']})['stdout'].strip()
    assert leftovers == '0', leftovers
    result = {'guestCommand': 'exact values, no env file', 'ompRequests': len(seen), 'authorizationMatched': True, 'envFilesLeft': 0}
    Path('reports/secret-stdin').mkdir(parents=True, exist_ok=True)
    Path('reports/secret-stdin/result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
finally:
    if created:
        b.control('session.stop', {'id': created})
        b.control('session.delete', {'id': created})
    if configured:
        b.control('test.provider.restore')
    adb('reverse', '--remove', 'tcp:54832')
    server.shutdown()
    server.server_close()
