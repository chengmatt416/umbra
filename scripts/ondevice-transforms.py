#!/usr/bin/env python3
"""Exercise the JSON renderer and deadlines on the disposable Android QA emulator."""
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
main_pid = adb('shell', 'pidof', '-s', 'app.umbra.agent').strip()
requests = []
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        self.send_response(200); self.end_headers(); self.wfile.write(b'fixture')
    def log_message(self, *args):
        pass
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:47921', f'tcp:{server.server_port}')
try:
    def run(source, data=None):
        return b.control('test.transform.run', {'source': source, 'input': data if data is not None else {}}, timeout=30)
    assert run('return input.map(x => x * 2);', [2, 3, 7]) == {'result': [4, 6, 14]}
    assert run('throw new Error("private error details");').get('error') == 'Transform execution failed'
    assert 'error' in run('return "x".repeat(65000);')
    assert run('return {process:typeof process,require:typeof require,bridge:typeof Android};')['result'] == {'process':'undefined', 'require':'undefined', 'bridge':'undefined'}
    # Exercise the runtime directly, bypassing the production source validator on purpose.
    network = run("try { const x=new XMLHttpRequest(); x.open('GET','http://127.0.0.1:47921/probe',false); x.send(); return {blocked:false}; } catch (_) {return {blocked:true};}")
    assert network == {'result': {'blocked': True}}, network
    file_access = run("try { const x=new XMLHttpRequest(); x.open('GET','file:///data/user/0/app.umbra.agent/files/settings.json',false); x.send(); return {blocked:false}; } catch (_) {return {blocked:true};}")
    assert file_access == {'result': {'blocked': True}}, file_access
    proc_rows = []
    def sample():
        time.sleep(.7)
        proc_rows.extend(adb('shell', 'ps', '-A', '-o', 'USER,PID,NAME').splitlines())
    sampler = threading.Thread(target=sample); sampler.start()
    assert run('const until=Date.now()+1600; while(Date.now()<until){}; return 4;') == {'result': 4}
    sampler.join()
    isolated = [line for line in proc_rows if 'sandboxed_process' in line and 'u0_i' in line]
    assert isolated, 'No isolated renderer UID observed'
    before = time.monotonic()
    deadline = run('while(true){}')
    elapsed = time.monotonic() - before
    assert 'deadline' in deadline.get('error', '') and elapsed < 12, (deadline, elapsed)
    assert run('return input.value + 1;', {'value': 41}) == {'result': 42}
    assert not requests, requests
    assert adb('shell', 'pidof', '-s', 'app.umbra.agent').strip() == main_pid
    result = {'jsonTransformPassed': True, 'networkDenied': True, 'filesDenied': True,
              'isolatedRendererUid': True, 'deadlineSeconds': round(elapsed, 2), 'recoveredAfterDeadline': True, 'sameAppProcess': True}
    output = Path('reports/transforms'); output.mkdir(parents=True, exist_ok=True)
    (output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
finally:
    adb('reverse', '--remove', 'tcp:47921')
    server.shutdown()
