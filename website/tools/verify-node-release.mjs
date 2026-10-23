// Verify public companion downloads, signatures, installer and both language pages.
import assert from 'node:assert/strict';
import { createHash, createPublicKey, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';

const release = JSON.parse(readFileSync(new URL('../src/node-release.json', import.meta.url), 'utf8'));
const base = process.argv[2] || 'https://umbra-agent.pages.dev';
const get = async (path, options = {}) => {
  const response = await fetch(new URL(path, base), { signal: AbortSignal.timeout(300_000), ...options });
  assert(response.ok, `${path}: HTTP ${response.status}`);
  return response;
};
const rawKey = Buffer.from(release.publicKey, 'base64');
const key = createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), rawKey.subarray(10)]), format: 'der', type: 'spki' });
const signed = async (path, expected = null) => {
  const response = await get(path);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (expected) {
    assert.equal(bytes.length, expected.bytes, `${path}: size`);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected.sha256, `${path}: checksum`);
  }
  const lines = (await (await get(`${path}.minisig`)).text()).trim().split('\n');
  const signature = Buffer.from(lines[1], 'base64');
  assert.equal(signature.subarray(0, 2).toString(), 'ED');
  assert(signature.subarray(2, 10).equals(rawKey.subarray(2, 10)), 'signing key ID');
  assert(verify(null, createHash('blake2b512').update(bytes).digest(), key, signature.subarray(10)), `${path}: signature`);
  console.log(`PASS: ${path} SHA-256 and minisign signature`);
};
for (const entry of Object.values(release.files)) {
  const head = await get(entry.url, { method: 'HEAD' });
  assert.equal(Number(head.headers.get('content-length')), entry.bytes);
  const range = await get(entry.url, { headers: { Range: 'bytes=0-31' } });
  assert.equal(range.status, 206);
  assert.equal((await range.arrayBuffer()).byteLength, 32);
  await signed(entry.url, entry);
}
await signed(release.checksums);
assert.equal(await (await get('/node/install.sh')).text(), readFileSync(new URL('../public/node/install.sh', import.meta.url), 'utf8'));
for (const language of ['en', 'zh-tw']) {
  const html = await (await get(`/${language}/nodes/`)).text();
  assert(html.includes(release.version));
  for (const [name, entry] of Object.entries(release.files)) {
    if (/\.(dmg|AppImage|tar\.gz)$/.test(name)) assert(html.includes(entry.url), `${language}: ${entry.url}`);
  }
}
console.log('PASS: installer and both remote-node pages');
