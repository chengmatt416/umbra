#!/usr/bin/env node
// node upload-release.mjs <apk path> <object name>
// Uploads a big file to the umbra-releases R2 bucket in 50 MiB parts through a temporary Worker, verifies the size,
// stores the local SHA-256 next to it as <name>.sha256, writes src/apk.json, then deletes the Worker.
// The version and versionCode come from Gradle's output-metadata.json next to the APK; the site's update.json (which
// the app checks for updates) needs the versionCode.
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, readFileSync, statSync, writeFileSync, openSync, readSync, closeSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const [file, name] = process.argv.slice(2);
if (!file || !name) { console.error('usage: upload-release.mjs <file> <object name>'); process.exit(2); }
const dir = join(here, 'r2-uploader');
const token = randomBytes(24).toString('hex');
const size = statSync(file).size;
const PART = 50 * 1024 * 1024;
const metaFile = join(dirname(file), 'output-metadata.json');
const meta = existsSync(metaFile) ? JSON.parse(readFileSync(metaFile, 'utf8')).elements?.[0] ?? {} : {};
if (!meta.versionCode) console.warn('no output-metadata.json next to the APK: set version and versionCode in src/apk.json by hand');

const sha = createHash('sha256');
{ const fd = openSync(file, 'r'); const buf = Buffer.alloc(8 << 20); let n; let pos = 0;
  while ((n = readSync(fd, buf, 0, buf.length, pos)) > 0) { sha.update(buf.subarray(0, n)); pos += n; } closeSync(fd); }
const digest = sha.digest('hex');
console.log('sha256', digest);

let base;
const call = async (path, init = {}) => {
  for (let attempt = 1; ; attempt++) {
    try {
      const r = await fetch(`${base}${path}`, { ...init, headers: { 'x-token': token, ...(init.headers || {}) } });
      if (!r.ok) throw new Error(`${path} -> ${r.status} ${await r.text()}`);
      return r.json();
    } catch (e) { if (attempt >= 5) throw e; console.log('retry', attempt, String(e).slice(0, 120)); await new Promise((s) => setTimeout(s, 2000 * attempt)); }
  }
};
try {
  const out = execFileSync('npx', ['wrangler', 'deploy'], { cwd: dir, encoding: 'utf8' });
  base = out.match(/https:\/\/[^\s]+workers\.dev/)?.[0];
  if (!base) throw new Error('no workers.dev url in: ' + out);
  // Keep the upload credential out of process arguments and Wrangler's deployment output.
  execFileSync('npx', ['wrangler', 'secret', 'bulk'], { cwd: dir, input: JSON.stringify({ TOKEN: token }), stdio: ['pipe', 'inherit', 'inherit'] });
  console.log('uploader at', base);
  // Worker may take a few seconds to be reachable.
  for (let i = 0; i < 20; i++) { try { await fetch(base, { headers: { 'x-token': token } }); break; } catch { await new Promise((s) => setTimeout(s, 1500)); } }
  const key = encodeURIComponent(name);
  const { uploadId } = await call(`/init?key=${key}`, { method: 'POST' });
  const parts = [];
  const fd = openSync(file, 'r');
  for (let n = 1, pos = 0; pos < size; n++, pos += PART) {
    const len = Math.min(PART, size - pos);
    const buf = Buffer.alloc(len); readSync(fd, buf, 0, len, pos);
    parts.push(await call(`/part?key=${key}&uploadId=${uploadId}&n=${n}`, { method: 'PUT', body: buf }));
    process.stdout.write(`\rpart ${n}/${Math.ceil(size / PART)}  ${(100 * (pos + len) / size).toFixed(0)}%`);
  }
  closeSync(fd);
  const done = await call(`/complete?key=${key}&uploadId=${uploadId}`, { method: 'POST', body: JSON.stringify(parts) });
  console.log('\ncompleted', done);
  if (done.size !== size) throw new Error(`size mismatch: ${done.size} != ${size}`);
  await call(`/put?key=${encodeURIComponent(name + '.sha256')}`, { method: 'PUT', body: `${digest}  ${name}\n` });
  const mib = (size / 1048576).toFixed(0) + ' MiB';
  writeFileSync(join(here, '..', 'src', 'apk.json'), JSON.stringify({ file: name, size: mib, sha256: digest, bytes: size, ...(meta.versionCode ? { version: meta.versionName, versionCode: meta.versionCode } : {}) }, null, 2));
  console.log('uploaded', name, mib);
} finally {
  spawnSync('npx', ['wrangler', 'delete', '--force'], { cwd: dir, stdio: 'inherit' });
}
