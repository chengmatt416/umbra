import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, access } from 'node:fs/promises';
import test from 'node:test';
import { compareVersions, parseChecksum, releaseStatus, syncReleases, validateFeed } from './publish-r2-release.mjs';

const body = Buffer.from('a signed APK fixture');
const sha256 = createHash('sha256').update(body).digest('hex');
const entry = { version: '0.6.1', file: 'umbra-0.6.1.apk', bytes: body.length, etag: '"apk"', checksumEtag: '"checksum"', sha256 };
const marker = `<!-- umbra-r2-sha256:${sha256} -->`;
const published = { tag_name: 'v0.6.1', draft: false, body: marker, assets: [
  { name: entry.file, size: entry.bytes, digest: `sha256:${sha256}`, state: 'uploaded' },
  { name: `${entry.file}.sha256`, state: 'uploaded' },
] };

function harness({ releases = [], bytes = body, checksum = `${sha256}  ${entry.file}\n`, feed = [entry], failUpload = false } = {}) {
  const commands = [], downloads = [];
  let apkPath;
  return {
    commands, downloads,
    options: {
      feedUrl: 'https://feed.invalid', repo: 'owner/repo', target: 'source-commit',
      fetchImpl: async (url, options) => {
        downloads.push({ path: url.pathname, headers: options.headers });
        if (url.pathname === '/releases.json') return Response.json({ releases: feed });
        return new Response(url.pathname.endsWith('.sha256') ? checksum : bytes);
      },
      gh: async args => {
        commands.push(args);
        if (args[0] === 'api') return JSON.stringify([releases]);
        if (args[1] === 'create') {
          assert(args.includes('--draft'));
          assert((await readFile(args[args.indexOf('--notes-file') + 1], 'utf8')).includes(marker));
        }
        if (args[1] === 'upload') {
          apkPath = args[3];
          assert.deepEqual(await readFile(apkPath), body);
          assert.equal(await readFile(args[4], 'utf8'), `${sha256}  ${entry.file}\n`);
          if (failUpload) throw new Error('upload interrupted');
        }
        return '';
      },
    },
    assertCleaned: async () => assert.rejects(access(apkPath), { code: 'ENOENT' }),
  };
}

test('validates filenames, size, duplicates, numeric ordering and checksum filename binding', () => {
  assert(compareVersions('0.10.0', '0.9.99') > 0);
  assert(compareVersions('0.6.1', '0.6.1') === 0);
  assert.equal(parseChecksum(`${sha256.toUpperCase()}  ${entry.file}\n`, entry.file), sha256);
  assert.throws(() => parseChecksum(`${sha256}  other.apk`, entry.file));
  assert.throws(() => validateFeed({ releases: [{ ...entry, file: '../secret' }] }));
  assert.throws(() => validateFeed({ releases: [{ ...entry, bytes: 2 ** 31 }] }));
  assert.throws(() => validateFeed({ releases: [entry, entry] }));
  assert.throws(() => validateFeed({ releases: [{ ...entry, etag: 'bad\nheader' }] }));
});

test('publishes only after verifying and uploading both assets to a draft', async () => {
  const fixture = harness();
  await syncReleases(fixture.options);
  assert.deepEqual(fixture.commands.slice(1).map(args => args.slice(0, 3)), [
    ['release', 'create', 'v0.6.1'], ['release', 'upload', 'v0.6.1'], ['release', 'edit', 'v0.6.1'],
  ]);
  assert(fixture.commands.at(-1).includes('--draft=false'));
  assert(fixture.commands.at(-1).includes('--latest=true'));
  assert.equal(fixture.downloads[1].headers['If-Match'], entry.checksumEtag);
  assert.equal(fixture.downloads[2].headers['If-Match'], entry.etag);
  await fixture.assertCleaned();
});

test('reruns skip an identical published APK without downloading or mutating the release', async () => {
  const fixture = harness({ releases: [published] });
  await syncReleases(fixture.options);
  assert.equal(fixture.commands.length, 1);
  assert.equal(fixture.downloads.length, 2);
});

test('corrupt, truncated and wrong-checksum downloads cannot create a release', async () => {
  for (const options of [{ bytes: Buffer.alloc(body.length) }, { bytes: body.subarray(1) }, { checksum: `${sha256}  other.apk` }]) {
    const fixture = harness(options);
    await assert.rejects(syncReleases(fixture.options));
    assert.equal(fixture.commands.length, 1);
  }
});

test('interrupted uploads stay in draft and can be resumed by the next run', async () => {
  const failed = harness({ failUpload: true });
  await assert.rejects(syncReleases(failed.options), /upload interrupted/);
  assert(!failed.commands.some(args => args[1] === 'edit'));
  await failed.assertCleaned();
  const retry = harness({ releases: [{ ...published, draft: true, assets: [] }] });
  await syncReleases(retry.options);
  assert(!retry.commands.some(args => args[1] === 'create'));
  assert(retry.commands.some(args => args[1] === 'edit'));
});

test('refuses changed published APKs and unrelated drafts', () => {
  assert.throws(() => releaseStatus(published, { ...entry, sha256: '0'.repeat(64) }), /changed/);
  assert.throws(() => releaseStatus({ ...published, draft: true, body: 'manual draft' }, entry), /unrelated/);
  assert.throws(() => releaseStatus({ ...published, assets: [] }, entry), /missing/);
});

test('does not backfill historical releases or replace latest with an older version', async () => {
  const fixture = harness({ feed: [entry, { ...entry, version: '0.4.5', file: 'umbra-0.4.5.apk' }], releases: [{ tag_name: 'v0.7.0', draft: false }] });
  await syncReleases(fixture.options);
  assert(fixture.commands.at(-1).includes('--latest=false'));
  assert(!fixture.commands.some(args => args.includes('v0.4.5')));
  await assert.rejects(syncReleases({ ...fixture.options, version: '0.8.0' }), /No completed/);
});
