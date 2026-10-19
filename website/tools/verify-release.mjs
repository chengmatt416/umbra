// Verify the published update feed and download against the uploaded APK metadata. Downloads the APK once.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const expected = JSON.parse(readFileSync(new URL('../src/apk.json', import.meta.url), 'utf8'));
const base = process.argv[2] || 'https://umbra-agent.pages.dev';
const get = async (path, options = {}) => {
  const response = await fetch(new URL(path, base), { signal: AbortSignal.timeout(900_000), ...options });
  assert(response.ok, `${path}: HTTP ${response.status}`);
  return response;
};
const update = await (await get(`/update.json?verify=${Date.now()}`)).json();
for (const key of ['version', 'versionCode', 'file', 'bytes', 'sha256']) assert.equal(update[key], expected[key], key);
assert.equal(update.url, `/download/${expected.file}`);
console.log('PASS: update feed');

const head = await get(update.url, { method: 'HEAD' });
assert.equal(Number(head.headers.get('content-length')), expected.bytes);
const range = await get(update.url, { headers: { Range: 'bytes=0-31' } });
assert.equal(range.status, 206);
assert.equal(range.headers.get('content-range'), `bytes 0-31/${expected.bytes}`);
assert.equal((await range.arrayBuffer()).byteLength, 32);
const latest = await fetch(new URL('/download/latest', base), { redirect: 'manual', signal: AbortSignal.timeout(30_000) });
assert.equal(latest.status, 302);
assert.equal(new URL(latest.headers.get('location'), base).pathname, update.url);
const checksum = await (await get(`${update.url}.sha256`)).text();
assert.equal(checksum.trim(), `${expected.sha256}  ${expected.file}`);
console.log('PASS: download headers, resume range, latest redirect and checksum file');

const response = await get(update.url);
const hash = createHash('sha256');
let bytes = 0;
for await (const chunk of response.body) { hash.update(chunk); bytes += chunk.length; }
assert.equal(bytes, expected.bytes);
assert.equal(hash.digest('hex'), expected.sha256);
console.log(`PASS: published APK SHA-256 (${bytes} bytes)`);

for (const language of ['en', 'zh-tw']) {
  const html = await (await get(`/${language}/`)).text();
  assert(html.includes(`/download/${expected.file}`), `${language}: download link`);
  assert(html.includes(expected.version), `${language}: release version`);
}
console.log('PASS: both website languages');
