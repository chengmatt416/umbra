import test from 'node:test';
import assert from 'node:assert/strict';
import { collectUsage } from '../functions/api/usage-counts.js';
import { onRequest as download } from '../functions/download/[[path]].js';

const id = '1a25995e-76ee-45cb-ab59-b232650c8438';
const october = new Date('2026-10-24T00:00:00Z');
const request = (payload = { install_id: id }) => new Request('https://umbra-agent.pages.dev/api/usage-counts', {
  method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
});
class Bucket {
  values = new Map();
  async put(key, value, options) {
    assert.equal(options.onlyIf.etagDoesNotMatch, '*');
    if (this.values.has(key)) return null;
    this.values.set(key, value);
    return { key };
  }
}

test('duplicates and concurrent retries count one install and one active member per UTC month', async () => {
  const bucket = new Bucket();
  const env = { RELEASES: bucket };
  const results = await Promise.all(Array.from({ length: 20 }, () => collectUsage(request(), env, october)));
  assert.ok(results.every(result => result.status === 204));
  assert.equal(bucket.values.size, 2);
  assert.equal((await collectUsage(request(), env, new Date('2026-11-01T00:00:00Z'))).status, 204);
  assert.equal(bucket.values.size, 3);
  const keys = [...bucket.values.keys()];
  assert.equal(keys.filter(key => key.startsWith('usage-counts/v1/installs/')).length, 1);
  assert.ok(keys.every(key => !key.includes(id)));
  assert.ok([...bucket.values.values()].every(value => value === '1'));
  assert.notEqual(keys.find(key => key.includes('/2026-10/')).split('/').at(-1), keys.find(key => key.includes('/2026-11/')).split('/').at(-1));
});

test('arbitrary fields, identifiers, methods, content types and oversized streams are refused before storage', async () => {
  const bucket = new Bucket();
  const env = { RELEASES: bucket };
  for (const payload of [{ install_id: id, prompt: 'private' }, { install_id: 'device-serial' }, {}, [], { install_id: 42 }])
    assert.equal((await collectUsage(request(payload), env, october)).status, 400);
  assert.equal((await collectUsage(new Request('https://example.com'), env)).status, 405);
  assert.equal((await collectUsage(new Request('https://example.com', { method: 'POST', body: 'plain' }), env)).status, 415);
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(257)); controller.close(); } });
  assert.equal((await collectUsage(new Request('https://example.com', { method: 'POST', headers: { 'content-type': 'application/json' }, body: stream, duplex: 'half' }), env)).status, 400);
  assert.equal(bucket.values.size, 0);
});

test('a partial storage failure can be retried without duplicating either count', async () => {
  const bucket = new Bucket();
  let fail = true;
  const env = { RELEASES: { put: async (...args) => {
    if (fail && args[0].includes('/monthly/')) throw new Error('storage unavailable');
    return bucket.put(...args);
  } } };
  assert.equal((await collectUsage(request(), env, october)).status, 503);
  fail = false;
  const result = await collectUsage(request(), env, october);
  assert.equal(result.status, 204);
  assert.equal(result.headers.get('cache-control'), 'no-store');
  assert.equal(bucket.values.size, 2);
});

test('the public download handler cannot expose telemetry objects', async () => {
  const result = await download({ request: new Request('https://umbra-agent.pages.dev/download/usage-counts/v1/installs/hash'),
    params: { path: ['usage-counts', 'v1', 'installs', 'hash'] }, env: { RELEASES: { get() { throw new Error('must not read'); } } } });
  assert.equal(result.status, 404);
});
