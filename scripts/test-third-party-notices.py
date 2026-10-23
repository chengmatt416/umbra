#!/usr/bin/env python3
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('notices', Path(__file__).with_name('third-party-notices.py'))
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


def tar_bytes(files):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w:xz') as tar:
        for name, data in files.items():
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            tar.addfile(entry, io.BytesIO(data))
    return buf.getvalue()


def ar_bytes(files):
    data = b'!<arch>\n'
    for name, body in files.items():
        data += f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{100644:<8}{len(body):<10}`\n'.encode()
        data += body + (b'\n' if len(body) % 2 else b'')
    return data


class CollectorTests(unittest.TestCase):
    def test_notice_paths_cannot_escape_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            for path in ('/LICENSE', '../LICENSE', 'nested/../../LICENSE'):
                with self.assertRaises(ValueError):
                    notices.put(Path(tmp), path, b'notice')

    def test_debian_package_preserves_source_and_notice(self):
        deb = ar_bytes({
            'control.tar.xz': tar_bytes({'./control': b'Package: example\nVersion: 2-3\nSource: upstream (2-1)\n'}),
            'data.tar.xz': tar_bytes({'./usr/share/doc/example/copyright': b'Original copyright\n'}),
        })
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            row = notices.collect_deb(deb, out, 'example.deb')
            self.assertEqual(row['source'], 'upstream (2-1)')
            self.assertEqual(row['version'], '2-3')
            self.assertEqual((out / row['notices'][0]).read_bytes(), b'Original copyright\n')

    def test_rootfs_does_not_claim_missing_npm_license(self):
        data = tar_bytes({
            './var/lib/dpkg/status': b'Package: shell\nVersion: 1\nStatus: install ok installed\n\nPackage: removed\nVersion: 1\nStatus: deinstall ok config-files\n',
            './opt/bun/node_modules/example/package.json': b'{"name":"example","version":"1"}',
            './opt/bun/node_modules/example/LICENSE': b'Upstream terms\n',
        })
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'rootfs.tar.xz'
            path.write_bytes(data)
            row = notices.collect_rootfs(path, Path(tmp) / 'out')
            self.assertEqual([p['package'] for p in row['debian']], ['shell'])
            self.assertEqual(row['npm'][0]['license'], 'UNKNOWN')
            self.assertEqual(len(row['notices']), 1)
            self.assertEqual(len(row['sha256']), 64)

    def test_gradle_retains_nested_aar_notice_and_sanitizes_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, 'w') as z:
                z.writestr('META-INF/NOTICE', 'Original attribution')
            artifact = root / 'example.aar'
            with zipfile.ZipFile(artifact, 'w') as z:
                z.writestr('classes.jar', jar.getvalue())
            manifest = root / 'artifacts.json'
            manifest.write_text(json.dumps([{'group': 'org.example', 'name': 'demo', 'version': '1', 'artifact': str(artifact)}]))
            rows = notices.collect_gradle(manifest, root / 'out', root / 'empty-cache')
            self.assertEqual(rows[0]['licenses'][0]['name'], 'UNKNOWN')
            self.assertNotIn(str(root), json.dumps(rows))
            self.assertEqual((root / 'out' / rows[0]['notices'][0]).read_text(), 'Original attribution')
            self.assertNotIn(':', rows[0]['notices'][0])

    def test_invalid_debian_archive_fails(self):
        with self.assertRaises(ValueError):
            list(notices.deb_members(b'not a package'))


if __name__ == '__main__':
    unittest.main()
