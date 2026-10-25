#!/usr/bin/env python3
"""Run a bounded real-runtime group discussion against a local scripted provider on the QA emulator."""
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
member_ids = []
requests = []
delegates = os.environ.get('UMBRA_TEST_TEAM_DELEGATES') == '1'
held = threading.Event()
release = threading.Event()
errors = []

def content(message):
    value = message.get('content', '')
    return value if isinstance(value, str) else '\n'.join(c.get('text', '') for c in value or [])

class Model(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append(body)
            text = '\n'.join(content(m) for m in body['messages'] if m['role'] == 'user')
            available = {t['function']['name'] for t in body.get('tools', [])}
            tools = [m for m in body['messages'] if m['role'] == 'tool']
            name = None
            if delegates and 'coding_start' not in available:
                assert '[Teams QA child]' in text, 'Unexpected coding request'
                if not tools:
                    held.set()
                    assert release.wait(120), 'The held coding response was not released'
                    name, args = 'write', {'path': 'team_qa_check.py', 'content': 'import unittest\nclass TeamCheck(unittest.TestCase):\n    def test_sum(self):\n        self.assertEqual(2+3, 5)\n'}
                elif len(tools) == 1:
                    name, args = 'bash', {'command': 'python3 -m unittest team_qa_check'}
                answer = 'Delegated synthetic check passed.'
            else:
                match = re.search(r'\[GroupTurn ([^ ]+) (\d+)\]', text)
                assert match, 'Unexpected group request'
                hop = int(match[2])
                called = {c['function']['name'] for m in body['messages'] for c in m.get('tool_calls', [])}
                if delegates and hop == 1 and 'coding_start' not in called:
                    name, args = 'coding_start', {'task': '[Teams QA child] Write a synthetic unittest and run its check.', 'title': 'Teams QA child', 'effort': 'minimal'}
                elif hop < 3 and 'group_send' not in called and (not delegates or hop != 1 or '[Report from ' in text):
                    name, args = 'group_send', {'target_agent_ids': [member_ids[hop % 2]]}
                answer = (f'Coordinator final summary at hop {hop}.' if hop == 4 else
                          'Waiting for the delegated coding check.' if delegates and hop == 1 and '[Report from ' not in text else f'Contribution at hop {hop}.')
            if name:
                assert name in available, f'{name} is not available'
                delta = {'role': 'assistant', 'content': None, 'tool_calls': [{'index': 0, 'id': f'team-{len(requests)}', 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}]}
                finish = 'tool_calls'
            else:
                delta = {'role': 'assistant', 'content': answer}
                finish = 'stop'
            self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
            for d, f in [(delta, None), ({}, finish)]:
                chunk = {'id': 'chatcmpl-teams', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'fake-model', 'choices': [{'index': 0, 'delta': d, 'finish_reason': f}]}
                self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            errors.append(str(error))
            self.send_error(500)
server = ThreadingHTTPServer(('127.0.0.1', 54833), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:54833', 'tcp:54833')
configured = False
group = None
try:
    b.control('test.provider.begin', {'base_url': 'http://127.0.0.1:54833/v1'})
    configured = True
    group = b.control('test.teams.create')
    member_ids[:] = group['memberAgentIds']
    root = b.control('test.teams.send', {'group': group['id'], 'text': f'@{member_ids[0]} Discuss this synthetic fixture only. Ask the other member for the next contribution; stop when the host ends the discussion.'})['root']
    if delegates:
        deadline = time.monotonic()+90
        while time.monotonic() < deadline:
            assert not errors, errors
            state = b.control('test.teams.state', {'group': group['id']})
            turn = state['turns'][-1]
            active = turn.get('activeSessionId')
            if held.is_set() and active and b.control('session.get', {'id': active})['state'] == 'COMPLETED':
                break
            time.sleep(.2)
        else:
            raise AssertionError('The parent did not yield while its delegate was active')
        time.sleep(2)  # Allow native completion/reconciliation callbacks to run.
        waiting = b.control('test.teams.state', {'group': group['id']})
        assert waiting['turns'][-1]['hop'] == 1 and waiting['turns'][-1]['activeSessionId'] == active, waiting
        assert len(waiting['messages']) == 1, 'The group published an unfinished delegated contribution'
        assert b.control('test.teams.receipt', {'group': group['id'], 'id': active})['verification']['verified'] is False
        release.set()
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        assert not errors, errors
        state = b.control('test.teams.state', {'group': group['id']})
        turn = state['turns'][-1]
        if turn.get('status', 'active') in ('completed', 'failed', 'paused'): break
        time.sleep(.5)
    assert turn.get('status', 'active') == 'completed', {'state': state, 'sessions': b.control('session.list')}
    assert [m.get('hop', 0) for m in state['messages']] == [0, 1, 2, 3, 4], state
    assert state['messages'][-1]['fromAgentId'] == group['coordinatorAgentId']
    assert state['messages'][-1]['sessionId'] == root
    for m in state['messages'][1:-1]:
        assert state['parents'][m['sessionId']] == root
    assert len(requests) == (11 if delegates else 6), len(requests)
    if delegates:
        first = state['messages'][1]['sessionId']
        assert b.control('test.teams.receipt', {'group': group['id'], 'id': first})['verification']['verified'] is True
    result = {'completed': True, 'hops': 4, 'coordinatorFinalSummary': True, 'sharedParentTask': True,
              'waitedForCheckedDelegate': delegates, 'providerRequests': len(requests)}
    output = Path('reports/teams'); output.mkdir(parents=True, exist_ok=True)
    (output/('delegates.json' if delegates else 'result.json')).write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    release.set()
    if group is not None: b.control('test.teams.cleanup', {'group': group['id']})
    if configured: b.control('test.provider.restore')
    adb('reverse', '--remove', 'tcp:54833')
    server.shutdown(); server.server_close()
