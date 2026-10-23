#!/usr/bin/env python3
"""Collect inventories and original notices without installing dependencies or executing archives.

This preserves available notices; it does not certify license compatibility or supply source code.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import xml.etree.ElementTree as ET
import zipfile


def legal_name(name):
    return bool(re.match(r'^(licen[cs]e|copying|copyright|notice|third[-_]?party)',
                         PurePosixPath(name).name, re.I))


def put(out, name, data):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts:
        raise ValueError(f'Unsafe notice path: {name}')
    target = out / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return path.as_posix()


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def deb_members(data):
    if data[:8] != b'!<arch>\n':
        raise ValueError('Not a Debian ar archive')
    pos = 8
    while pos < len(data):
        header = data[pos:pos + 60]
        size = int(header[48:58])
        yield header[:16].decode().strip().rstrip('/'), data[pos + 60:pos + 60 + size]
        pos += 60 + size + size % 2


def fields(text):
    result = {}
    for line in text.splitlines():
        if line and not line[0].isspace() and ':' in line:
            key, value = line.split(':', 1)
            result[key] = value.strip()
    return result


def collect_deb(data, out, label):
    info, notices = {}, []
    for name, body in deb_members(data):
        if name.startswith(('control.tar', 'data.tar')):
            with tarfile.open(fileobj=io.BytesIO(body)) as tar:
                for member in tar:
                    if not member.isfile():
                        continue
                    path = member.name.removeprefix('./')
                    if name.startswith('control.') and path == 'control':
                        info = fields(tar.extractfile(member).read().decode())
                    elif name.startswith('data.') and legal_name(path):
                        notices.append(put(out, f'notices/packs/{label}/{path}', tar.extractfile(member).read()))
    return {'package': info.get('Package'), 'version': info.get('Version'),
            'source': info.get('Source', info.get('Package')), 'notices': notices}


def collect_rootfs(path, out):
    packages, npm, notices, links = [], [], [], []
    # Stream the rootfs: do not materialize its executables or trust archive paths.
    with tarfile.open(path, 'r|*') as tar:
        for member in tar:
            name = member.name.removeprefix('./')
            if (member.issym() or member.islnk()) and legal_name(name):
                links.append({'path': name, 'target': member.linkname})
            if not member.isfile():
                continue  # Linked Debian copyright files remain available in the original image.
            if name == 'var/lib/dpkg/status':
                text = tar.extractfile(member).read().decode()
                for paragraph in text.split('\n\n'):
                    d = fields(paragraph)
                    if d.get('Status') == 'install ok installed':
                        packages.append({'package': d['Package'], 'version': d['Version'],
                                         'source': d.get('Source', d['Package'])})
            elif name.startswith('opt/bun/') and name.endswith('/package.json'):
                d = json.load(tar.extractfile(member))
                if d.get('name') and d.get('version'):
                    npm.append({'name': d['name'], 'version': d['version'],
                                'license': d.get('license', 'UNKNOWN'), 'path': name})
            elif (name.startswith('usr/share/common-licenses/') or
                  (name.startswith('usr/share/doc/') and legal_name(name)) or
                  (name.startswith('opt/bun/') and legal_name(name))):
                notices.append(put(out, f'notices/rootfs/{name}', tar.extractfile(member).read()))
    return {'archive': path.name, 'sha256': digest(path),
            'debian': sorted(packages, key=lambda d: d['package']),
            'npm': sorted(npm, key=lambda d: (d['name'], d['version'], d['path'])),
            'notices': sorted(notices), 'noticeLinks': links}


def collect_packs(path, out):
    packages, extras = [], []
    with tarfile.open(path) as tar:
        for member in tar:
            if not member.isfile():
                continue
            if member.name.endswith('.deb'):
                packages.append(collect_deb(tar.extractfile(member).read(), out,
                                            PurePosixPath(member.name).name))
            elif '/extras/' in member.name:
                data = tar.extractfile(member).read()
                notices = []
                with zipfile.ZipFile(io.BytesIO(data)) as z:
                    for name in z.namelist():
                        if not name.endswith('/') and legal_name(name):
                            notices.append(put(out, f'notices/extras/{PurePosixPath(member.name).name}/{name}', z.read(name)))
                extras.append({'file': PurePosixPath(member.name).name,
                               'sha256': hashlib.sha256(data).hexdigest(), 'notices': notices})
    return {'archive': path.name, 'sha256': digest(path),
            'debian': sorted(packages, key=lambda d: d['package']), 'extras': extras}


def collect_gradle(path, out, cache):
    rows = []
    for artifact in json.loads(path.read_text()):
        group, name, version = (artifact[k] for k in ('group', 'name', 'version'))
        coordinate = f'{group}:{name}:{version}'
        notice_dir = f'{group}/{name}/{version}'
        poms = sorted((cache / group / name / version).glob('*/*.pom'))
        licenses = []
        if poms:
            xml = ET.parse(poms[0])
            for node in xml.findall('.//{*}licenses/{*}license'):
                licenses.append({'name': node.findtext('{*}name'), 'url': node.findtext('{*}url')})
        notices = []
        binary = Path(artifact['artifact'])
        with zipfile.ZipFile(binary) as z:
            for entry in z.namelist():
                if not entry.endswith('/') and legal_name(entry):
                    notices.append(put(out, f'notices/{notice_dir}/{entry}', z.read(entry)))
                elif entry == 'classes.jar':
                    with zipfile.ZipFile(io.BytesIO(z.read(entry))) as jar:
                        for nested in jar.namelist():
                            if not nested.endswith('/') and legal_name(nested):
                                notices.append(put(out, f'notices/{notice_dir}/classes/{nested}', jar.read(nested)))
        rows.append({'coordinate': coordinate, 'licenses': licenses or [{'name': 'UNKNOWN', 'url': None}],
                     'sha256': digest(binary), 'notices': notices})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', type=Path)
    parser.add_argument('--packs', type=Path)
    parser.add_argument('--gradle', type=Path, action='append', default=[])
    parser.add_argument('--cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not (args.rootfs or args.packs or args.gradle):
        parser.error('At least one input is required')
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    report = {'scope': 'Notices and metadata found in the specified artifacts; not legal clearance or corresponding source.'}
    if args.rootfs:
        report['rootfs'] = collect_rootfs(args.rootfs, out)
    if args.packs:
        report['packs'] = collect_packs(args.packs, out)
    for path in args.gradle:
        report[path.parents[2].name] = collect_gradle(path, out, args.cache)
    (out / 'inventory.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(f'Wrote inventory and original notices to {out}')


if __name__ == '__main__':
    main()
