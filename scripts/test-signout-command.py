#!/usr/bin/env python3
"""Run the actual sign-out command against disposable SQLite credentials; never use an account."""
from pathlib import Path
import os
import re
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'app/src/main/kotlin/app/umbra/agent/data/OmpLogin.kt'
SCRIPT = re.search(r'SIGN_OUT_SCRIPT = """(.*?)"""\.trimIndent', SOURCE.read_text(), re.S).group(1)


class SignOutCommandTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='umbra-signout-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db = self.root / 'agent.db'
        self.bun = shutil.which('bun')
        self.assertIsNotNone(self.bun, 'Bun is required to test the shipped command')

    def run_command(self):
        return subprocess.run([self.bun, '-e', SCRIPT], env={
            'HOME': str(self.root), 'PATH': os.defpath,
            'UMBRA_DB': str(self.db), 'UMBRA_PROVIDER': 'dummy-target',
        }, cwd=self.root, capture_output=True, text=True, timeout=10)

    def test_verifies_only_the_selected_provider_is_revoked(self):
        with sqlite3.connect(self.db) as db:
            db.execute('CREATE TABLE auth_credentials (provider TEXT, disabled_cause TEXT, updated_at INTEGER)')
            db.executemany('INSERT INTO auth_credentials VALUES (?, ?, 0)', [
                ('dummy-target', None), ('dummy-target', 'already disabled'), ('dummy-other', None),
            ])
        result = self.run_command()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'UMBRA_SIGNOUT_OK')
        with sqlite3.connect(self.db) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM auth_credentials WHERE provider='dummy-target' AND disabled_cause IS NULL").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT count(*) FROM auth_credentials WHERE provider='dummy-other' AND disabled_cause IS NULL").fetchone()[0], 1)
        self.assertEqual(self.run_command().returncode, 0, 'sign-out is idempotent')

    def test_missing_database_is_not_created_or_reported_as_success(self):
        result = self.run_command()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('UMBRA_SIGNOUT_OK', result.stdout)
        self.assertFalse(self.db.exists())

    def test_schema_failure_is_not_reported_as_success(self):
        with sqlite3.connect(self.db):
            pass
        result = self.run_command()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('UMBRA_SIGNOUT_OK', result.stdout)


if __name__ == '__main__':
    unittest.main()
