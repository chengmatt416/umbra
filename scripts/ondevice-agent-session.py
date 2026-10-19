#!/usr/bin/env python3
"""Exercise real assistant → coding delegation on Android with a local scripted model, no credentials.

Requires the debug bridge. Temporarily replaces provider settings and restores them in finally.
If interrupted externally, run `umbractl.py call test.provider.restore` after stopping the test sessions.
"""
import json
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from umbractl import Bridge, adb

PORT = 54830
OUT = Path('reports/agent-session')
OUT.mkdir(parents=True, exist_ok=True)
seen_tools = []


def text(message):
    content = message.get('content', '')
    return content if isinstance(content, str) else ''.join(x.get('text', '') for x in content)


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        messages = body['messages']
        system = '\n'.join(text(m) for m in messages if m['role'] == 'system')
        last_user = next((text(m) for m in reversed(messages) if m['role'] == 'user'), '')
        tools = [x['function']['name'] for x in body.get('tools', [])]
        seen_tools.append(tools)
        call = None
        delay = 0
        if 'Umbra personal assistant' in system:
            assert 'bash' not in tools and 'edit' not in tools and 'write' not in tools, tools
            assert 'coding_start' in tools and 'agent_status' in tools and 'subagent_start' not in tools, tools
            calls = [c['function']['name'] for m in messages for c in m.get('tool_calls', [])]
            if last_user.startswith('[Report from'):
                content = 'Delegated verification finished successfully.'
            elif 'agent_status' not in calls:
                call = ('agent_status', {'summary': 'Coordinating verification'})
            elif 'coding_start' not in calls:
                call = ('coding_start', {'task': 'umbra-test-child: return a synthetic verification result.', 'title': 'Agent delegation check', 'model': 'umbra-test-agent/fake-model', 'effort': 'minimal'})
            else:
                content = 'I started a separate coding session.'
                delay = 25
        else:
            assert 'umbra-test-child' in last_user, last_user[:100]
            assert 'bash' in tools, tools
            content = 'Synthetic child verification complete.'
            delay = 8
        if call:
            delta = {'role': 'assistant', 'content': None, 'tool_calls': [{'index': 0, 'id': 'call-' + call[0], 'type': 'function', 'function': {'name': call[0], 'arguments': json.dumps(call[1])}}]}
            finish = 'tool_calls'
        else:
            delta = {'role': 'assistant', 'content': content}
            finish = 'stop'
        time.sleep(delay)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for d, f in [(delta, None), ({}, finish)]:
            data = {'id': 'chatcmpl-test', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'fake-model', 'choices': [{'index': 0, 'delta': d, 'finish_reason': f}]}
            self.wfile.write(('data: ' + json.dumps(data) + '\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n')


def snapshot(name):
    adb('shell', 'uiautomator dump /sdcard/umbra-agent-test.xml')
    xml = adb('exec-out', 'cat', '/sdcard/umbra-agent-test.xml')
    (OUT / (name + '.xml')).write_text(xml)
    from umbractl import ADB
    png = subprocess.run([ADB, 'exec-out', 'screencap', '-p'], check=True, capture_output=True).stdout
    (OUT / (name + '.png')).write_bytes(png)
    return xml


def wait_for(fn, timeout=180):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        result = fn()
        if result:
            return result
        time.sleep(.3)
    raise AssertionError('Timed out waiting for Android verification')


def main():
    server = ThreadingHTTPServer(('127.0.0.1', PORT), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    b = Bridge()
    agent = child = None
    configured = False
    original_ids = {s['id'] for s in b.control('session.list')}
    try:
        b.control('test.provider.begin', {'base_url': 'http://127.0.0.1:%d/v1' % PORT})
        configured = True
        agent = b.control('agent.create', {'name': 'Verification assistant', 'soul': 'A synthetic verification assistant. Delegate the test task, no external actions.'})
        print('Created dedicated assistant session', flush=True)
        b.control('agent.talk', {'id': agent['id'], 'text': 'Run synthetic agent verification and delegate the work.'})
        wait_for(lambda: b.control('session.get', {'id': agent['session']})['activity'] == 'Coordinating verification')
        adb('shell', 'am', 'start', '-n', 'app.umbra.agent/.MainActivity', '--es', 'page', 'agent/' + agent['id'])
        xml = snapshot('working')
        assert 'Coordinating verification' in xml, 'Broad activity missing'
        assert any(n.get('content-desc') == 'Coordinating verification' for n in ET.fromstring(xml).iter('node')), 'Thinking orb missing'
        print('Verified live broad activity and native orb', flush=True)
        wait_for(lambda: any(s['title'] == 'Agent delegation check' for s in b.control('session.list')))
        child = next(s for s in b.control('session.list') if s['title'] == 'Agent delegation check')
        assert child['kind'] == 'CODING'
        assert b.control('session.get', {'id': agent['session']})['kind'] == 'AGENT'
        def linked_event():
            for event in b.control('session.events', {'id': agent['session']}):
                e = event['event']
                if e.get('type') == 'tool_result' and e.get('tool') == 'coding_start' and '[Session: ' + child['id'] + ']' in e.get('summary', ''):
                    return True
        wait_for(linked_event)
        xml = snapshot('delegation-event')
        root = ET.fromstring(xml)
        note = next(n for n in root.iter('node') if 'Agent delegation check' in n.get('text', ''))
        coords = list(map(int, re.findall(r'\d+', note.attrib['bounds'])))
        adb('shell', 'input', 'tap', str((coords[0]+coords[2])//2), str((coords[1]+coords[3])//2))
        time.sleep(.5)
        opened = snapshot('delegated-session')
        assert 'Agent delegation check' in opened and 'Verification assistant' not in opened, 'Event did not open the coding session'
        wait_for(lambda: any(e['event'].get('type') == 'user_message' and '[Session: ' + child['id'] + ']' in e['event'].get('text', '') for e in b.control('session.events', {'id': agent['session']})))
        wait_for(lambda: b.control('session.get', {'id': agent['session']})['state'] == 'COMPLETED')
        assert b.control('session.get', {'id': agent['session']})['activity'] == '', 'Settled status was not cleared'
        assert b.control('session.get', {'id': child['id']})['state'] == 'COMPLETED', 'Delegated work failed'
        (OUT / 'result.json').write_text(json.dumps({'assistant_kind': 'AGENT', 'child_kind': 'CODING', 'event_opened_child': True, 'report_returned': True, 'tools_checked': len(seen_tools)}, indent=2))
        print('PASS: dedicated assistant prompt/tools, live status + orb, separate coding session, tappable event, returning report, status cleanup', flush=True)
    finally:
        for candidate in b.control('session.list'):
            if candidate['id'] not in original_ids and candidate['title'] == 'Agent delegation check':
                b.control('session.delete', {'id': candidate['id']})
        if agent:
            b.control('agent.delete', {'id': agent['id']})
        if configured:
            b.control('test.provider.restore')
        adb('reverse', '--remove', 'tcp:%d' % PORT, check=False)
        server.shutdown()


if __name__ == '__main__':
    main()
