#!/usr/bin/env python3
"""Sign built artifacts and generate the site's download metadata and pinned installer. Never prints secret material."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
version = sys.argv[1] if len(sys.argv) > 1 else '0.4.4'
artifacts = root / 'node-go/build/release'
key = Path(os.environ.get('UMBRA_NODE_SIGNING_KEY', str(Path.home() / '.umbra-signing/node-minisign.key')))
public = (root / 'node-go/internal/node/release.pub').read_text().splitlines()[-1]
required = [f'umbra-node-{version}-darwin-universal', f'umbra-node-{version}-macos-universal.dmg']
for arch in ('amd64', 'arm64'):
    required += [f'umbra-node-{version}-linux-{arch}', f'umbra-node-{version}-linux-{arch}.tar.gz', f'umbra-node-{version}-linux-{arch}.AppImage']
files = {}
for name in required:
    path = artifacts / name
    assert path.is_file() and path.stat().st_size > 0, f'Missing artifact: {path}'
    digest = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
    files[name] = {'bytes': path.stat().st_size, 'sha256': digest, 'url': '/download/' + name, 'signature': '/download/' + name + '.minisig'}
    subprocess.run(['minisign', '-S', '-W', '-s', str(key), '-m', str(path), '-t', f'Umbra Node {version} {name}'], check=True)
sums_name = f'umbra-node-{version}-SHA256SUMS'
sums = artifacts / sums_name
sums.write_text(''.join(f'{entry["sha256"]}  {name}\n' for name, entry in files.items()))
subprocess.run(['minisign', '-S', '-W', '-s', str(key), '-m', str(sums), '-t', f'Umbra Node {version} checksums'], check=True)
meta = {'version': version, 'publicKey': public, 'files': files, 'checksums': '/download/' + sums_name, 'checksumsSignature': '/download/' + sums_name + '.minisig'}
(root / 'website/src/node-release.json').write_text(json.dumps(meta, indent=2) + '\n')
cases = []
for platform, arch in [('darwin', 'universal'), ('linux', 'amd64'), ('linux', 'arm64')]:
    name = f'umbra-node-{version}-{platform}-{arch}'
    cases.append(f'  {platform}-{arch}) expected=\'{files[name]["sha256"]}\';;')
installer = (root / 'node-go/packaging/install.sh.in').read_text().replace('@VERSION@', version).replace('@PUBKEY@', public).replace('@CASES@', '\n'.join(cases))
output = root / 'website/public/node'
output.mkdir(parents=True, exist_ok=True)
(output / 'install.sh').write_text(installer)
(output / 'minisign.pub').write_text((root / 'node-go/internal/node/release.pub').read_text())
print(f'Signed {len(files)} node artifacts; installer and metadata generated.')
