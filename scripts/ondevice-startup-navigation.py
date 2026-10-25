#!/usr/bin/env python3
"""Start a task from Home, navigate during startup, and require completion without pause/resume.

Uses the disposable QA emulator and a local scripted model. Provider settings are backed up and
restored, and only this test's sessions are removed. Requires the debug bridge and installed Linux.
"""
import importlib
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, 'scripts')
from umbractl import Bridge, adb, setup
ui = importlib.import_module('ondevice-task-controls')
requests = []

class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        requests.append(body)
        if any(m['role'] == 'tool' for m in body['messages']):
            delta = {'role': 'assistant', 'content': 'Startup navigation test completed without pause or resume.'}
            finish = 'stop'
        else:
            delta = {'role': 'assistant', 'content': None, 'tool_calls': [{'index': 0, 'id': 'startup-bash', 'type': 'function', 'function': {'name': 'bash', 'arguments': json.dumps({'command': 'printf startup-navigation-ok'})}}]}
            finish = 'tool_calls'
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for d, f in [(delta, None), ({}, finish)]:
            chunk = {'id': 'chatcmpl-startup', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'fake-model', 'choices': [{'index': 0, 'delta': d, 'finish_reason': f}]}
            self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556', 'Use the disposable QA emulator'
assert adb('emu', 'avd', 'name').splitlines()[0].strip() in ('umbra-orb-release-043', 'umbra-proactive-qa-050'), 'Wrong emulator'
setup()
b = Bridge()
server = ThreadingHTTPServer(('127.0.0.1', 54831), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:54831', 'tcp:54831')
original = {s['id'] for s in b.control('session.list')}
configured = False
created = []
try:
    b.control('test.provider.begin', {'base_url': 'http://127.0.0.1:54831/v1'})
    configured = True
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'home')
    ui.tap('Coding')
    ui.tap('Message')
    adb('shell', 'input', 'text', 'Startup_navigation_regression')
    adb('shell', 'input', 'keyevent', '4')
    ui.tap('Send')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        created = [s['id'] for s in b.control('session.list') if s['id'] not in original]
        if created:
            break
        time.sleep(.1)
    assert len(created) == 1, created
    sid = created[0]
    before_navigation = len(requests)
    assert before_navigation == 0, 'The test must navigate while startup is still in progress'
    assert b.control('test.composer.draft')['text'] == '', 'The sent request must leave the composer immediately'
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'session', sid)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        session = b.control('session.get', {'id': sid})
        if session['state'] in ('COMPLETED', 'FAILED', 'STOPPED', 'PAUSED'):
            break
        time.sleep(.3)
    events = b.control('session.events', {'id': sid})
    assert session['state'] == 'COMPLETED', {'session': session, 'events': events}
    assert len(requests) == 2, len(requests)
    assert b.control('test.composer.draft')['text'] == '', 'Navigation must not restore an already-sent request'
    assert not any(e['event'].get('to') == 'paused' for e in events)
    result = {'state': session['state'], 'requestsBeforeNavigation': before_navigation, 'requestsTotal': len(requests), 'pauseResumeUsed': False, 'sentDraftCleared': True}
    Path('reports/startup-navigation').mkdir(parents=True, exist_ok=True)
    Path('reports/startup-navigation/result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
finally:
    for sid in created:
        b.control('session.stop', {'id': sid})
        b.control('session.delete', {'id': sid})
    if configured:
        b.control('test.provider.restore')
    adb('reverse', '--remove', 'tcp:54831')
    server.shutdown()
    server.server_close()
