// Upload the signed companion artifacts without changing the Android update feed.
// Run with --check to verify the local release without uploading anything.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const site = fileURLToPath(new URL('..', import.meta.url));
const release = JSON.parse(readFileSync(resolve(site, 'src/node-release.json'), 'utf8'));
const directory = resolve(site, '../node-go/build/release');
const names = Object.keys(release.files);
for (const [name, entry] of Object.entries(release.files)) {
  assert.match(name, /^umbra-node-\d+\.\d+\.\d+-[\w.-]+$/);
  const bytes = readFileSync(resolve(directory, name));
  assert.equal(bytes.length, entry.bytes, `${name}: size`);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256, `${name}: checksum`);
  assert.equal(entry.url, `/download/${name}`);
}
const sums = `umbra-node-${release.version}-SHA256SUMS`;
assert.equal(readFileSync(resolve(directory, sums), 'utf8'), names.map(name => `${release.files[name].sha256}  ${name}\n`).join(''));
names.push(sums);
for (const name of names) {
  execFileSync('minisign', ['-Vm', resolve(directory, name), '-x', resolve(directory, `${name}.minisig`), '-P', release.publicKey, '-q']);
}
console.log(`Verified ${names.length} files and signatures.`);
if (!process.argv.includes('--check')) {
  for (const name of names.flatMap(name => [name, `${name}.minisig`])) {
    const file = resolve(directory, name);
    assert(statSync(file).size < 300 * 1024 * 1024, 'Use multipart upload for files over 300 MiB');
    const type = name.endsWith('.minisig') || name.endsWith('SHA256SUMS') ? 'text/plain; charset=utf-8' : 'application/octet-stream';
    execFileSync('npx', ['--no-install', 'wrangler', 'r2', 'object', 'put', `umbra-releases/${name}`, '--file', file, '--content-type', type, '--remote'], { cwd: site, stdio: 'inherit' });
  }
}
