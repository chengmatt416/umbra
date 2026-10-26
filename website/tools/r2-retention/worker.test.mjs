import assert from 'node:assert/strict';
import { test } from 'node:test';
import { planDeletes } from './worker.mjs';

test('keeps the two newest APK versions and deletes their older APK and checksum objects', () => {
  const keys = ['umbra-0.4.0.apk', 'umbra-0.4.0.apk.sha256', 'umbra-0.6.1.apk', 'umbra-0.6.1.apk.sha256',
    'umbra-0.6.2.apk', 'umbra-0.6.2.apk.sha256', 'umbra-0.6.3.apk'];
  assert.deepEqual(planDeletes(keys), ['umbra-0.4.0.apk', 'umbra-0.4.0.apk.sha256', 'umbra-0.6.1.apk', 'umbra-0.6.1.apk.sha256']);
});

test('compares versions numerically, not as text', () => {
  const keys = ['umbra-0.6.9.apk', 'umbra-0.6.10.apk', 'umbra-0.6.2.apk'];
  assert.deepEqual(planDeletes(keys), ['umbra-0.6.2.apk']);
});

test('never deletes when two or fewer versions exist', () => {
  assert.deepEqual(planDeletes(['umbra-0.6.2.apk', 'umbra-0.6.2.apk.sha256', 'umbra-0.6.3.apk']), []);
  assert.deepEqual(planDeletes([]), []);
});

test('ignores Laya, node, usage and other non-APK objects', () => {
  const keys = ['umbra-0.6.1.apk', 'umbra-0.6.2.apk', 'umbra-0.6.3.apk',
    'laya/1a793eb568e6/laya-typed-decisions-fp16.tflite', 'umbra-node-0.1.0-linux-amd64.tar.gz',
    'usage-counts/v1/installs/abc', 'download-counts/v1/2026-10/xyz', 'umbra-rootfs-0.4.0-arm64.tar.xz'];
  assert.deepEqual(planDeletes(keys), ['umbra-0.6.1.apk']);
});
