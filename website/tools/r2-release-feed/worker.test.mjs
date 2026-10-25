import assert from 'node:assert/strict';
import test from 'node:test';
import worker from './worker.mjs';

const object = (key, size = 100) => ({ key, size, httpEtag: `"${key}"` });
const call = (path, RELEASES, options) => worker.fetch(new Request(`https://feed.invalid${path}`, options), { RELEASES });

test('paginates and lists only complete, nonempty versioned APK/checksum pairs', async () => {
  const pages = [
    { objects: [object('umbra-0.6.1.apk'), object('umbra-0.6.2.apk'), object('umbra-test.apk'), object('usage-counts/private')], truncated: true, cursor: 'next' },
    { objects: [object('umbra-0.6.1.apk.sha256'), object('umbra-0.6.3.apk', 0), object('umbra-0.6.3.apk.sha256'), object('umbra-0.6.4.apk'), object('umbra-0.6.4.apk.sha256', 2048)], truncated: false },
  ];
  const options = [];
  const response = await call('/releases.json', { list: async args => { options.push(args); return pages.shift(); } });
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), { releases: [{ file: 'umbra-0.6.1.apk', version: '0.6.1', bytes: 100, etag: '"umbra-0.6.1.apk"', checksumEtag: '"umbra-0.6.1.apk.sha256"' }] });
  assert.deepEqual(options, [{ prefix: 'umbra-', cursor: undefined }, { prefix: 'umbra-', cursor: 'next' }]);
});

test('private paths, test APKs and writes never access the bucket', async () => {
  for (const path of ['/download/usage-counts/private', '/download/umbra-test.apk', '/download/umbra-0.6.1.apk/extra', '/other']) {
    assert.equal((await call(path, {})).status, 404);
  }
  assert.equal((await call('/download/umbra-0.6.1.apk', {}, { method: 'PUT', body: 'no' })).status, 405);
});

test('pins a streamed download to the listed ETag and rejects overwritten objects', async () => {
  let options;
  const response = await call('/download/umbra-0.6.1.apk', { get: async (key, args) => {
    options = args;
    return { ...object(key, 3), body: new Response('apk').body };
  } }, { headers: { 'If-Match': '"original"' } });
  assert.equal(options.onlyIf.get('if-match'), '"original"');
  assert.equal(await response.text(), 'apk');
  assert.equal(response.headers.get('content-length'), '3');
  assert.equal((await call('/download/umbra-0.6.1.apk', { get: async () => object('changed') }, { headers: { 'If-Match': '"original"' } })).status, 412);
  assert.equal((await call('/download/umbra-0.6.1.apk', { get: async () => null })).status, 404);
});

test('HEAD returns metadata without fetching a body', async () => {
  const response = await call('/download/umbra-0.6.1.apk', { head: async key => object(key) }, { method: 'HEAD' });
  assert.equal(response.status, 200);
  assert.equal(response.body, null);
  assert.equal(response.headers.get('content-length'), '100');
});
