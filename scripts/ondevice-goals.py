#!/usr/bin/env python3
"""Exercise native goal authorization, dependency progress, revocation, restart and replanning.

Uses only the disposable QA emulator, a local scripted provider and fixture-owned tasks.
No account traffic or external messages are permitted.
"""
import json
import os
from pathlib import Path
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from umbractl import Bridge, adb, setup

assert os.environ.get('ANDROID_SERIAL') == 'emulator-5556'
assert adb('emu', 'avd', 'name').splitlines()[0].strip() in ('umbra-orb-release-043', 'umbra-proactive-qa-050')
setup()
b = Bridge()
requests = []
blocked = threading.Event()
release = threading.Event()
errors = []


def text_content(message):
    content = message.get('content', '')
    return content if isinstance(content, str) else '\n'.join(c.get('text', '') for c in content or [])


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append(body)
            text = '\n'.join(text_content(m) for m in body['messages'] if m['role'] == 'user')
            match = re.search(r'Current milestone: (First|Second|Revised) fixture', text)
            child = re.search(r'\[Goal QA child (First|Second|Revised)\]', text)
            assert match or child, 'Unexpected provider request outside the goal fixture'
            milestone = (match or child)[1]
            available = {t['function']['name'] for t in body.get('tools', [])}
            parent = 'coding_start' in available
            tool_results = [m for m in body['messages'] if m['role'] == 'tool']
            if parent and milestone == 'Second' and not tool_results:
                blocked.set()
                assert release.wait(90), 'The revocation fixture did not release its held request'
            name = None
            if parent:
                if not tool_results and '[Report from ' not in text:
                    name = 'coding_start'
                    key = re.search(r'\[GoalRun ([^]]+)\]', text)[1]
                    args = {'task': f'[Goal QA child {milestone}] Write a synthetic unittest module, run its check, then report the result.',
                            'title': f'Goal QA {key} {milestone}', 'effort': 'minimal'}
            elif not tool_results:
                name = 'write'
                args = {'path': 'goal_qa_check.py', 'content': 'import unittest\nclass GoalCheck(unittest.TestCase):\n    def test_sum(self):\n        self.assertEqual(sum([2, 3, 5]), 10)\n'}
            elif len(tool_results) == 1:
                name = 'bash'
                args = {'command': 'python3 -m unittest goal_qa_check'}
            if name:
                assert name in available, f'{name} is not available in this session'
                delta = {'role': 'assistant', 'content': None, 'tool_calls': [{'index': 0, 'id': f'{milestone}-{len(tool_results)}', 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}]}
                finish = 'tool_calls'
            else:
                delta = {'role': 'assistant', 'content': f'{milestone} fixture: waiting for the coding receipt.' if parent and '[Report from ' not in text else f'{milestone} fixture completed with an independent unittest check.'}
                finish = 'stop'
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for d, f in [(delta, None), ({}, finish)]:
                chunk = {'id': 'chatcmpl-goal', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'fake-model', 'choices': [{'index': 0, 'delta': d, 'finish_reason': f}]}
                self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # Revocation deliberately aborts one in-flight request.
        except Exception as error:
            errors.append(str(error))
            self.send_error(500)


def state():
    return b.control('test.goals.state', {'agent': agent})


def until(check, seconds=120):
    deadline = time.monotonic()+seconds
    latest = None
    while time.monotonic() < deadline:
        assert not errors, errors
        latest = state()
        if check(latest):
            return latest
        time.sleep(.3)
    raise AssertionError({'state': latest, 'requestCount': len(requests), 'errors': errors})


server = ThreadingHTTPServer(('127.0.0.1', 54834), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:54834', 'tcp:54834')
configured = False
agent = None
try:
    b.control('test.provider.begin', {'base_url': 'http://127.0.0.1:54834/v1'})
    configured = True
    agent = b.control('test.goals.create')['agent']
    b.control('test.goals.authorize', {'agent': agent, 'enabled': True})
    until(lambda s: blocked.is_set())
    progressed = state()
    goal = progressed['goals'][0]
    assert goal['milestones'][0]['status'] == 'done', progressed
    assert goal['milestones'][0]['artifactPath'].startswith('session:')
    second_id = goal['milestones'][1]['assignedTo'].removeprefix('#')
    assert len(requests) == 7, len(requests)
    b.control('test.goals.authorize', {'agent': agent, 'enabled': False})
    release.set()
    revoked = until(lambda s: any(t['id'] == second_id and t.get('state', 'created').upper() == 'PAUSED' for t in s['tasks']))
    assert not revoked['goals'][0].get('autoAdvance', False)
    time.sleep(2)
    events = b.control('session.events', {'id': second_id})
    assert not any(e['event'].get('tool') == 'coding_start' and e['event'].get('status') == 'ok' for e in events), 'A revoked response started coding work'
    count = len(requests)
    adb('shell', 'am', 'force-stop', 'app.umbra.agent')
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity')
    setup()
    b = Bridge()
    recovered = state()
    assert not recovered['goals'][0].get('autoAdvance', False)
    time.sleep(3)
    assert len(requests) == count, 'Revoked work restarted without authorization'
    replanned = b.control('test.goals.replan', {'agent': agent})
    assert [m['id'] for m in replanned['goals'][0]['milestones']] == ['first', 'revised']
    assert replanned['goals'][0]['milestones'][0]['status'] == 'done'
    assert not replanned['goals'][0].get('autoAdvance', False)
    b.control('test.goals.authorize', {'agent': agent, 'enabled': True})
    finished = until(lambda s: s['goals'][0].get('state', 'IN_PROGRESS') == 'WAITING_USER' and all(m.get('status') == 'done' for m in s['goals'][0]['milestones']) and len(s['cards']) == 1 and s['cards'][0]['id'] in {a['id'] for a in s['attention']})
    goal = finished['goals'][0]
    assert len(goal.get('replanHistory', [])) == 1
    assert len(finished['cards']) == 1, finished['cards']
    assert finished['cards'][0]['id'] in {a['id'] for a in finished['attention']}
    assert len(requests) == 13, len(requests)
    assert all(t.get('state', 'created').upper() in ('CREATED', 'COMPLETED', 'PAUSED') for t in finished['tasks']), finished['tasks']
    result = {'startsWithoutPauseResume': True, 'dependentMilestoneAdvanced': True,
              'revokedResponseDidNotWrite': True, 'restartPreservedRevocation': True,
              'replanPreservedVerifiedWork': True, 'newGrantRequired': True,
              'finalAcceptanceRemainsForReview': True, 'providerRequests': len(requests)}
    output = Path('reports/goals')
    output.mkdir(parents=True, exist_ok=True)
    (output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    release.set()
    # A force-stop intentionally invalidates the old authenticated socket.
    adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity')
    setup()
    b = Bridge()
    try:
        if agent is not None:
            b.control('test.goals.authorize', {'agent': agent, 'enabled': False})
            b.control('test.goals.cleanup', {'agent': agent})
    finally:
        try:
            if configured:
                b.control('test.provider.restore')
        finally:
            adb('reverse', '--remove', 'tcp:54834')
            server.shutdown()
            server.server_close()
