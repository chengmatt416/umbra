#!/usr/bin/env python3
"""Exercise the real signed installer in disposable directories, with local download fixtures only."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
artifacts = root / 'node-go/build/release'
with tempfile.TemporaryDirectory(prefix='umbra-installer-') as temporary:
    work = Path(temporary)
    tools = work / 'tools'
    tools.mkdir()
    curl = tools / 'curl'
    curl.write_text(f'''#!{os.sys.executable}
import os, sys, shutil
from pathlib import Path
args=sys.argv[1:]
name=next(x for x in args if x.startswith('https://')).rsplit('/',1)[1]
output=Path(args[args.index('-o')+1])
shutil.copyfile(Path({str(artifacts)!r})/name, output)
if os.environ.get('FIXTURE_TAMPER') == ('signature' if name.endswith('.minisig') else 'binary'):
    output.write_bytes(b'tampered fixture')
''')
    curl.chmod(0o700)
    for name, tamper, minisign in [('valid-minisign', '', True), ('valid-builtin', '', False), ('bad-binary', 'binary', True), ('bad-signature', 'signature', False)]:
        destination = work / name
        env = dict(os.environ, PATH=f'{tools}:' + ('/opt/homebrew/bin:' if minisign else '') + '/usr/bin:/bin', UMBRA_NODE_BIN_DIR=str(destination), UMBRA_NODE_SKIP_SETUP='1', FIXTURE_TAMPER=tamper)
        result = subprocess.run(['/bin/sh', str(root / 'website/public/node/install.sh')], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=30)
        installed = destination / 'umbra-node'
        if tamper:
            assert result.returncode != 0 and not installed.exists(), (name, result.stdout)
        else:
            assert result.returncode == 0 and installed.is_file(), (name, result.stdout)
            assert installed.read_bytes() == (artifacts / 'umbra-node-0.4.4-darwin-universal').read_bytes()
        print(name + ': passed')
