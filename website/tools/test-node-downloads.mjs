import assert from 'node:assert/strict';
import { onRequest } from '../functions/download/[[path]].js';

let reads = 0;
const object = {
  size: 4, httpEtag: '"fixture"', body: new Uint8Array([1, 2, 3, 4]),
  writeHttpMetadata(headers) { headers.set('content-type', 'untrusted/metadata'); },
};
const env = { LATEST_APK: 'umbra-0.4.4.apk', RELEASES: {
  async get(name, options) { reads++; return options?.range ? { ...object, body: new Uint8Array([2, 3]), range: { offset: 1, length: 2 } } : object; },
  async head() { reads++; return object; },
}};
const call = (name, method = 'GET', headers = {}) => onRequest({ request: new Request('https://example.invalid/download/' + name, { method, headers }), env, params: { path: name.split('/') } });

for (const name of [
  'umbra-0.4.4.apk', 'umbra-0.4.4.apk.sha256', 'umbra-node-0.4.4-darwin-universal',
  'umbra-node-0.4.4-macos-universal.dmg', 'umbra-node-0.4.4-linux-amd64',
  'umbra-node-0.4.4-linux-amd64.AppImage', 'umbra-node-0.4.4-linux-arm64.tar.gz',
  'umbra-node-0.4.4-SHA256SUMS', 'umbra-node-0.4.4-SHA256SUMS.minisig',
  'umbra-node-0.4.4-macos-universal.dmg.minisig',
]) {
  const response = await call(name);
  assert.equal(response.status, 200, name);
  assert.equal((await response.arrayBuffer()).byteLength, 4);
  const head = await call(name, 'HEAD');
  assert.equal(head.status, 200); assert.equal((await head.arrayBuffer()).byteLength, 0);
}
const before = reads;
for (const name of ['../secret', 'umbra-node-0.4.4-linux-amd64/secret', 'umbra-node-0.4.4-linux-amd64.exe', 'umbra-node-0.4.4-linux-arm64.AppImage.html', 'umbra-node-0.4.4-linux-arm64.minisig/extra']) assert.equal((await call(name)).status, 404);
assert.equal((await call('umbra-node-0.4.4-linux-arm64', 'POST')).status, 405);
assert.equal(reads, before, 'Rejected names/methods must not access R2');
const ranged = await call('umbra-node-0.4.4-linux-arm64.AppImage', 'GET', { range: 'bytes=1-2' });
assert.equal(ranged.status, 206); assert.equal(ranged.headers.get('content-range'), 'bytes 1-2/4');
assert.equal(ranged.headers.get('content-type'), 'application/octet-stream');
assert.equal((await ranged.arrayBuffer()).byteLength, 2);
assert.equal((await call('umbra-node-0.4.4-macos-universal.dmg')).headers.get('content-type'), 'application/x-apple-diskimage');
assert.equal((await call('umbra-node-0.4.4-SHA256SUMS')).headers.get('content-type'), 'text/plain; charset=utf-8');
const beforeRedirects = reads;
for (const alias of ['', 'latest']) {
  for (const method of ['GET', 'HEAD']) {
    const response = await call(alias, method);
    assert.equal(response.status, 302);
    assert.equal(response.headers.get('location'), 'https://example.invalid/download/umbra-0.4.4.apk');
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal((await response.arrayBuffer()).byteLength, 0);
  }
}
env.LATEST_APK = 'umbra-0.6.2.apk';
assert.equal((await call('latest')).headers.get('location'), 'https://example.invalid/download/umbra-0.6.2.apk');
assert.equal(reads, beforeRedirects, 'Latest redirects must not read the APK from R2');
console.log('Node and APK download allowlists, types, HEAD, Range and traversal checks passed.');
