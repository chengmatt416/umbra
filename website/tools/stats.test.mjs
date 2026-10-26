import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { getStats, onRequest as stats } from '../functions/api/stats.js';
import { onRequest as download } from '../functions/download/[[path]].js';
import { L } from '../src/content.mjs';

const summaryKey = 'usage-counts/v1/summary.json';
const now = new Date('2026-10-25T12:00:00Z');
const apk = 'umbra-0.6.1.apk';
const statsRequest = (method = 'GET') => new Request('https://example.invalid/api/stats', { method });

class Bucket {
  values = new Map();
  lists = [];
  puts = [];
  async list(options) {
    const { prefix, cursor, limit } = options;
    assert.equal(limit, 1000);
    this.lists.push(options);
    const keys = [...this.values.keys()].filter(key => key.startsWith(prefix)).sort();
    const start = Number(cursor || 0);
    const objects = keys.slice(start, start + limit).map(key => ({ key }));
    const truncated = start + objects.length < keys.length;
    return { objects, truncated, cursor: truncated ? String(start + objects.length) : undefined };
  }
  async get(key, options) {
    if (!this.values.has(key)) return null;
    const value = this.values.get(key);
    const body = new TextEncoder().encode(value);
    const match = /^bytes=(\d+)-(\d*)$/.exec(options?.range?.get('range') || '');
    const offset = match ? Number(match[1]) : 0;
    const end = match?.[2] ? Math.min(Number(match[2]) + 1, body.length) : body.length;
    return {
      size: body.length, httpEtag: '"fixture"', body: body.slice(offset, end),
      range: match ? { offset, length: end - offset } : undefined,
      writeHttpMetadata(headers) { headers.set('content-type', 'application/octet-stream'); },
      async json() { return JSON.parse(value); },
    };
  }
  async head(key) { return this.get(key); }
  async put(key, value, options) {
    this.puts.push({ key, value, options });
    this.values.set(key, value);
    return { key };
  }
}

function downloadContext(bucket, name = apk, method = 'GET', range) {
  return {
    request: new Request(`https://example.invalid/download/${name}`, { method, headers: range === undefined ? {} : { Range: range } }),
    env: { RELEASES: bucket, LATEST_APK: apk }, params: { path: name.split('/') },
  };
}

test('stats paginate every prefix, count all download months and only current monthly membership', async () => {
  const bucket = new Bucket();
  for (let i = 0; i < 1003; i++) bucket.values.set(`download-counts/v1/${i % 2 ? '2026-09' : '2026-10'}/${i}`, '1');
  for (let i = 0; i < 1002; i++) bucket.values.set(`usage-counts/v1/installs/${i}`, '1');
  for (let i = 0; i < 1001; i++) bucket.values.set(`usage-counts/v1/monthly/2026-10/${i}`, '1');
  bucket.values.set('usage-counts/v1/monthly/2026-09/old', '1');
  bucket.values.set(apk, 'apk bytes');
  const response = await getStats(statsRequest(), { RELEASES: bucket }, now);
  const value = await response.json();
  assert.equal(response.status, 200);
  assert.deepEqual(value, { downloads: 1003, installs: 1002, monthlyActive: 1001,
    month: '2026-10', since: '2026-10-25', updated: now.toISOString() });
  assert.equal(response.headers.get('cache-control'), 'public, max-age=300');
  assert.equal(response.headers.get('content-type'), 'application/json; charset=utf-8');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(response.headers.get('access-control-allow-origin'), '*');
  assert.equal(bucket.lists.length, 6);
  for (const prefix of ['download-counts/v1/', 'usage-counts/v1/installs/', 'usage-counts/v1/monthly/2026-10/'])
    assert.deepEqual(bucket.lists.filter(call => call.prefix === prefix).map(call => call.cursor), [undefined, '1000']);
  assert.deepEqual(JSON.parse(bucket.values.get(summaryKey)), value);
});

test('a fresh summary skips listing, HEAD has no body, and summaries expire after ten minutes', async () => {
  const bucket = new Bucket();
  const env = { RELEASES: bucket };
  const first = await (await getStats(statsRequest(), env, now)).json();
  assert.equal(first.downloads, 0);
  assert.equal(first.installs, 0);
  assert.equal(first.monthlyActive, 0);
  bucket.values.set('download-counts/v1/2026-10/new', '1');
  bucket.lists.length = 0;
  const beforeExpiry = new Date(now.getTime() + 599999);
  assert.deepEqual(await (await getStats(statsRequest(), env, beforeExpiry)).json(), first);
  const head = await getStats(statsRequest('HEAD'), env, beforeExpiry);
  assert.equal(head.status, 200);
  assert.equal(await head.text(), '');
  assert.equal(head.headers.get('cache-control'), 'public, max-age=300');
  assert.equal(bucket.lists.length, 0);
  assert.equal(bucket.puts.length, 1);
  const expired = new Date(now.getTime() + 600000);
  const refreshed = await (await getStats(statsRequest(), env, expired)).json();
  assert.equal(refreshed.downloads, 1);
  assert.equal(refreshed.updated, expired.toISOString());
  assert.equal(bucket.lists.length, 3);
});

test('a still-fresh summary refreshes when the UTC month changes', async () => {
  const bucket = new Bucket();
  const env = { RELEASES: bucket };
  bucket.values.set('usage-counts/v1/monthly/2026-10/old', '1');
  bucket.values.set('usage-counts/v1/monthly/2026-11/new', '1');
  bucket.values.set('usage-counts/v1/monthly/2026-11/another', '1');
  await getStats(statsRequest(), env, new Date('2026-10-31T23:59:00Z'));
  const value = await (await getStats(statsRequest(), env, new Date('2026-11-01T00:00:00Z'))).json();
  assert.equal(value.month, '2026-11');
  assert.equal(value.monthlyActive, 2);
});

test('unreadable or invalid summaries recompute and cache write failures still return counts', async () => {
  for (const cached of ['not json', 'null', JSON.stringify({ month: '2026-10', updated: now.toISOString() })]) {
    const bucket = new Bucket();
    bucket.values.set(summaryKey, cached);
    const response = await getStats(statsRequest(), { RELEASES: bucket }, now);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).downloads, 0);
    assert.equal(bucket.lists.length, 3);
  }
  const bucket = new Bucket();
  bucket.get = async () => { throw new Error('cache read failed'); };
  bucket.put = async () => { throw new Error('cache write failed'); };
  assert.equal((await getStats(statsRequest(), { RELEASES: bucket }, now)).status, 200);
});

test('stats reject other methods without storage access and fail cleanly when listing is unavailable', async () => {
  for (const method of ['POST', 'PUT', 'DELETE', 'OPTIONS']) {
    const response = await stats({ request: statsRequest(method), env: {} });
    assert.equal(response.status, 405);
    assert.equal(response.headers.get('allow'), 'GET, HEAD');
  }
  const bucket = new Bucket();
  bucket.list = async () => { throw new Error('listing failed'); };
  for (const env of [{}, { RELEASES: bucket }]) {
    const response = await stats({ request: statsRequest(), env });
    assert.equal(response.status, 503);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(bucket.puts.length, 0);
  }
});

test('successful APK GETs from zero create separate anonymous download objects', async () => {
  const bucket = new Bucket();
  bucket.values.set(apk, 'apk bytes');
  const before = new Date().toISOString().slice(0, 7);
  for (const range of [undefined, 'bytes=0-', 'bytes=0-2']) {
    const response = await download(downloadContext(bucket, apk, 'GET', range));
    assert.equal(response.status, range ? 206 : 200);
    assert.equal(response.headers.get('content-type'), 'application/vnd.android.package-archive');
    assert.equal(await response.text(), range === 'bytes=0-2' ? 'apk' : 'apk bytes');
  }
  const after = new Date().toISOString().slice(0, 7);
  assert.equal(bucket.puts.length, 3);
  assert.equal(new Set(bucket.puts.map(({ key }) => key)).size, 3);
  for (const { key, value, options } of bucket.puts) {
    assert.match(key, /^download-counts\/v1\/\d{4}-\d{2}\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.ok([before, after].includes(key.split('/')[2]));
    assert.equal(value, '1');
    assert.equal(options, undefined);
  }
});

test('HEAD, resumed and suffix ranges, checksums, models, nodes, missing files and redirects do not count', async () => {
  const bucket = new Bucket();
  for (const [name, method, range, status] of [
    [apk, 'HEAD', undefined, 200], [apk, 'HEAD', 'bytes=0-', 200],
    [apk, 'GET', 'bytes=3-', 206], [apk, 'GET', 'bytes=-3', 200],
    [apk, 'GET', '', 200],
    [`${apk}.sha256`, 'GET', undefined, 200],
    ['laya/abcdef123456/model.tflite', 'GET', undefined, 200],
    ['laya/abcdef123456/npu/model.json', 'GET', undefined, 200],
    ['umbra-node-0.6.1-linux-arm64.tar.gz', 'GET', undefined, 200],
  ]) {
    bucket.values.set(name, 'apk bytes');
    const response = await download(downloadContext(bucket, name, method, range));
    assert.equal(response.status, status);
    if (method === 'HEAD') assert.equal(await response.text(), '');
  }
  assert.equal((await download(downloadContext(bucket, 'umbra-9.9.apk'))).status, 404);
  assert.equal((await download(downloadContext(bucket, 'latest'))).status, 302);
  assert.equal((await download(downloadContext(bucket, apk, 'POST'))).status, 405);
  assert.equal(bucket.puts.length, 0);
});

test('waitUntil lets the APK response finish while the count write is pending', { timeout: 1000 }, async () => {
  const bucket = new Bucket();
  bucket.values.set(apk, 'apk bytes');
  let finish;
  bucket.put = () => new Promise(resolve => { finish = resolve; });
  const context = downloadContext(bucket);
  const pending = [];
  context.waitUntil = function (promise) { assert.equal(this, context); pending.push(promise); };
  const response = await download(context);
  assert.equal(await response.text(), 'apk bytes');
  assert.equal(pending.length, 1);
  finish();
  await Promise.all(pending);
});

test('synchronous and asynchronous counter failures never break APK downloads', async () => {
  for (const put of [() => { throw new Error('sync failure'); }, async () => { throw new Error('async failure'); }]) {
    for (const background of [false, true]) {
      const bucket = new Bucket();
      bucket.values.set(apk, 'apk bytes');
      bucket.put = put;
      const context = downloadContext(bucket);
      const pending = [];
      if (background) context.waitUntil = promise => pending.push(promise);
      const response = await download(context);
      assert.equal(response.status, 200);
      assert.equal(await response.text(), 'apk bytes');
      await Promise.all(pending);
    }
  }
});

const client = readFileSync(new URL('../public/stats.js', import.meta.url), 'utf8');
async function renderStats(lang, response) {
  const line = { hidden: true, textContent: '', dataset: { downloads: L[lang].download.statsDownloads, monthlyActive: L[lang].download.statsActive } };
  await runInNewContext(client, {
    document: { querySelector: () => line, documentElement: { lang: L[lang].htmlLang } }, Intl,
    fetch: async (url, options) => {
      assert.equal(url, '/api/stats');
      assert.equal(options.credentials, 'omit');
      if (response instanceof Error) throw response;
      return response;
    },
  });
  return line;
}

test('the client formats real totals in both languages and omits zero metrics', async () => {
  const response = value => ({ ok: true, json: async () => value });
  const en = await renderStats('en', response({ downloads: 1234, monthlyActive: 321 }));
  assert.equal(en.hidden, false);
  assert.equal(en.textContent, '1,234 downloads · 321 people used Umbra this month');
  const zh = await renderStats('zh-tw', response({ downloads: 1234, monthlyActive: 321 }));
  assert.equal(zh.hidden, false);
  assert.equal(zh.textContent, '下載 1,234 次 · 本月 321 人使用');
  assert.equal((await renderStats('en', response({ downloads: 12, monthlyActive: 0 }))).textContent, '12 downloads');
  assert.equal((await renderStats('zh-tw', response({ downloads: 0, monthlyActive: 3 }))).textContent, '本月 3 人使用');
});

test('the client stays hidden for zero, invalid, failed or unavailable stats', async () => {
  for (const response of [
    { ok: true, json: async () => ({ downloads: 0, monthlyActive: 0 }) },
    { ok: true, json: async () => ({ downloads: -1, monthlyActive: 1 }) },
    { ok: true, json: async () => ({ downloads: '12', monthlyActive: 1 }) },
    { ok: true, json: async () => { throw new Error('invalid JSON'); } },
    { ok: false }, new Error('network unavailable'),
  ]) {
    const line = await renderStats('en', response);
    assert.equal(line.hidden, true);
    assert.equal(line.textContent, '');
  }
});
