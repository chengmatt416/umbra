#!/usr/bin/env node
// Mirror completed R2 APK/checksum pairs to GitHub without rebuilding Android or storing a GitHub token in R2.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const github = async (args) => (await exec('gh', args, { maxBuffer: 16 * 1024 * 1024 })).stdout;
const VERSION = /^\d+\.\d+\.\d+$/;

export function compareVersions(a, b) {
  assert(VERSION.test(a) && VERSION.test(b), 'Expected a numeric major.minor.patch version');
  const left = a.split('.').map(BigInt), right = b.split('.').map(BigInt);
  for (let i = 0; i < 3; i++) if (left[i] !== right[i]) return left[i] > right[i] ? 1 : -1;
  return 0;
}

export function validateFeed(feed) {
  assert(Array.isArray(feed.releases), 'Missing releases array');
  const seen = new Set();
  for (const entry of feed.releases) {
    assert(VERSION.test(entry.version) && entry.file === `umbra-${entry.version}.apk`, 'Invalid APK filename/version');
    assert(Number.isSafeInteger(entry.bytes) && entry.bytes > 0 && entry.bytes < 2 ** 31, 'APK must be smaller than 2 GiB');
    for (const key of ['etag', 'checksumEtag']) assert(/^"[^"\r\n]+"$/.test(entry[key]), `Invalid ${key}`);
    assert(!seen.has(entry.version), 'Duplicate release version');
    seen.add(entry.version);
  }
  return feed.releases;
}

export function parseChecksum(text, file) {
  const match = /^([a-f0-9]{64})  (\S+)\s*$/i.exec(text);
  assert(match && match[2] === file, `Invalid checksum for ${file}`);
  return match[1].toLowerCase();
}

const marker = (sha256) => `<!-- umbra-r2-sha256:${sha256} -->`;

export function releaseStatus(release, entry) {
  if (!release) return 'create';
  if (release.draft) {
    assert(release.body?.includes(marker(entry.sha256)), `Refusing to change an unrelated or changed draft: ${release.tag_name}`);
    return 'resume';
  }
  const apk = release.assets.find(asset => asset.name === entry.file);
  const checksum = release.assets.find(asset => asset.name === `${entry.file}.sha256`);
  assert(apk?.state === 'uploaded' && apk.size === entry.bytes && checksum?.state === 'uploaded', `Published release has missing/mismatched assets: ${release.tag_name}`);
  assert(apk.digest ? apk.digest === `sha256:${entry.sha256}` : release.body?.includes(marker(entry.sha256)),
    `Published APK changed: ${release.tag_name}; upload a new version instead`);
  return 'skip';
}

async function get(url, etag, fetchImpl) {
  const response = await fetchImpl(url, { headers: etag ? { 'If-Match': etag } : {}, signal: AbortSignal.timeout(900_000) });
  assert(response.ok, `${url}: HTTP ${response.status}`);
  return response;
}

export async function syncReleases({ feedUrl, repo, target, minVersion = '0.6.1', version = '', gh = github, fetchImpl = fetch }) {
  assert(feedUrl && repo && target, 'R2_RELEASE_FEED_URL, GH_REPO and RELEASE_TARGET are required');
  if (version) assert(VERSION.test(version), 'Invalid requested version');
  const base = new URL(feedUrl);
  const feed = validateFeed(await (await get(new URL('/releases.json', base), null, fetchImpl)).json());
  const entries = feed.filter(entry => compareVersions(entry.version, minVersion) >= 0 && (!version || entry.version === version))
    .sort((a, b) => compareVersions(a.version, b.version));
  if (version) assert(entries.length === 1, `No completed R2 APK/checksum pair for ${version}`);
  const releases = JSON.parse(await gh(['api', '--paginate', '--slurp', `repos/${repo}/releases?per_page=100`])).flat();
  const versions = [...entries.map(entry => entry.version), ...releases.filter(r => !r.draft && !r.prerelease && /^v\d+\.\d+\.\d+$/.test(r.tag_name)).map(r => r.tag_name.slice(1))];
  const latest = versions.sort(compareVersions).at(-1);
  for (const entry of entries) {
    const checksumResponse = await get(new URL(`/download/${entry.file}.sha256`, base), entry.checksumEtag, fetchImpl);
    const checksumBytes = await checksumResponse.arrayBuffer();
    assert(checksumBytes.byteLength <= 1024, 'Checksum file is too large');
    entry.sha256 = parseChecksum(Buffer.from(checksumBytes).toString('utf8'), entry.file);
    const tag = `v${entry.version}`;
    const status = releaseStatus(releases.find(release => release.tag_name === tag), entry);
    if (status === 'skip') { console.log(`${tag}: already published and matches R2`); continue; }
    const directory = await mkdtemp(join(tmpdir(), 'umbra-r2-release-'));
    try {
      const apk = join(directory, entry.file);
      const checksum = `${apk}.sha256`;
      const response = await get(new URL(`/download/${entry.file}`, base), entry.etag, fetchImpl);
      const hash = createHash('sha256');
      let bytes = 0;
      await pipeline(response.body, new Transform({ transform(chunk, encoding, callback) {
        bytes += chunk.length;
        if (bytes > entry.bytes) return callback(new Error('APK exceeds the expected size'));
        hash.update(chunk);
        callback(null, chunk);
      } }), createWriteStream(apk));
      assert.equal(bytes, entry.bytes, 'APK size mismatch');
      assert.equal(hash.digest('hex'), entry.sha256, 'APK SHA-256 mismatch');
      await writeFile(checksum, `${entry.sha256}  ${entry.file}\n`);
      const notes = join(directory, 'notes.md');
      let changes = '';
      try {
        const document = await readFile(new URL(`../docs/RELEASE_${entry.version}.md`, import.meta.url), 'utf8');
        changes = document.split('## What changes for the user\n')[1]?.split('## Verification')[0]?.trim() || '';
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
      await writeFile(notes, `${changes ? `${changes}\n\n` : ''}APK mirrored from Cloudflare R2; SHA-256 and size verified before publication.\n\nSHA-256: \`${entry.sha256}\`\n\n${marker(entry.sha256)}\n`);
      if (status === 'create') await gh(['release', 'create', tag, '--repo', repo, '--target', target, '--draft', '--title', `Umbra ${entry.version}`, '--notes-file', notes]);
      await gh(['release', 'upload', tag, apk, checksum, '--repo', repo, '--clobber']);
      await gh(['release', 'edit', tag, '--repo', repo, '--draft=false', `--latest=${entry.version === latest}`]);
      console.log(`${tag}: published ${entry.file} (${bytes} bytes), SHA-256 ${entry.sha256}`);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await syncReleases({ feedUrl: process.env.R2_RELEASE_FEED_URL, repo: process.env.GH_REPO,
    target: process.env.RELEASE_TARGET, minVersion: process.env.R2_MIN_VERSION || '0.6.1', version: process.env.RELEASE_VERSION || '' });
}
