#!/usr/bin/env python3
"""Revoke an in-flight native watcher run, then require a fresh grant before a note can be written.

Uses only the disposable emulator, synthetic notification metadata and a localhost provider.
No notifications or account messages are sent; all fixture-owned state is removed afterward.
"""
import json
import os
from pathlib import Path
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert 'umbra-orb-release-043' in adb('emu', 'avd', 'name')
setup()
b = Bridge()
requests = []
held = threading.Event()
release = threading.Event()
errors = []


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append(body)
            available = {t['function']['name'] for t in body.get('tools', [])}
            assert 'note_write' in available and 'coding_start' not in available and 'bash' not in available
            first = len(requests) == 1
            if first:
                held.set()
                assert release.wait(90)
            if not any(m['role'] == 'tool' for m in body['messages']):
                delta = {'role': 'assistant', 'content': None, 'tool_calls': [{'index': 0, 'id': f'watcher-note-{len(requests)}', 'type': 'function',
                    'function': {'name': 'note_write', 'arguments': json.dumps({'topic': 'qa-watcher', 'content': 'Synthetic watcher note', 'append': False})}}]}
                finish = 'tool_calls'
            else:
                delta = {'role': 'assistant', 'content': 'The newly authorized watcher saved its synthetic note.'}
                finish = 'stop'
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for d, f in [(delta, None), ({}, finish)]:
                chunk = {'id': 'chatcmpl-watcher', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'fake-model', 'choices': [{'index': 0, 'delta': d, 'finish_reason': f}]}
                self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            errors.append(str(error))
            self.send_error(500)


def state():
    return b.control('test.watchers.state', {'agent': agent})


def until(check, seconds=90):
    deadline = time.monotonic()+seconds
    latest = None
    while time.monotonic() < deadline:
        assert not errors, errors
        latest = state()
        if check(latest):
            return latest
        time.sleep(.2)
    raise AssertionError({'state': latest, 'providerRequests': len(requests)})


server = ThreadingHTTPServer(('127.0.0.1', 54835), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:54835', 'tcp:54835')
configured = False
agent = None
try:
    b.control('test.provider.begin', {'base_url': 'http://127.0.0.1:54835/v1'})
    configured = True
    agent = b.control('test.watchers.create')['agent']
    old = b.control('test.watchers.dispatch', {'agent': agent})['created']
    until(lambda s: held.is_set())
    before = state()
    stamp = next(t for t in before['tasks'] if t['id'] == old)['attachments']['watcher.authorization.v1']
    b.control('test.watchers.revoke', {'agent': agent})
    release.set()
    revoked = until(lambda s: any(t['id'] == old and t.get('state', 'created') in ('paused', 'failed', 'completed') for t in s['tasks']))
    assert revoked['note'] == '', 'The revoked model response wrote a private note'
    assert len(requests) == 1, 'A revoked run reached another provider request'
    events = b.control('session.events', {'id': old})
    assert not any(e['event'].get('tool') == 'note_write' and e['event'].get('status') == 'ok' for e in events)
    b.control('session.stop', {'id': old})
    b.control('test.watchers.renew', {'agent': agent})
    fresh = b.control('test.watchers.dispatch', {'agent': agent})['created']
    finished = until(lambda s: any(t['id'] == fresh and t.get('state') == 'completed' for t in s['tasks']))
    assert finished['note'] == 'Synthetic watcher note', finished
    fresh_task = next(t for t in finished['tasks'] if t['id'] == fresh)
    assert fresh_task['attachments']['watcher.authorization.v1'] != stamp
    assert len(requests) == 3, len(requests)
    result = {'readOnlyDiscovery': True, 'revokedResponseDidNotWrite': True, 'revokedProviderFollowUpBlocked': True,
              'freshGrantAllowedNote': True, 'providerRequests': len(requests)}
    out = Path('reports/watchers')
    out.mkdir(parents=True, exist_ok=True)
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    release.set()
    try:
        if agent is not None:
            b.control('test.watchers.cleanup', {'agent': agent})
    finally:
        try:
            if configured:
                b.control('test.provider.restore')
        finally:
            adb('reverse', '--remove', 'tcp:54835')
            server.shutdown()
            server.server_close()
