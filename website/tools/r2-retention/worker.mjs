// Keeps the newest KEEP Umbra APK versions in the releases bucket and deletes older APK and checksum objects.
// Only umbra-X.Y.Z.apk and umbra-X.Y.Z.apk.sha256 are considered; Laya files, Linux images, node downloads and
// usage counts use other names and are never listed for deletion.
const KEEP = 2;
const APK = /^umbra-(\d+)\.(\d+)\.(\d+)\.apk(\.sha256)?$/;

export function planDeletes(keys, keep = KEEP) {
  const versions = new Map();
  for (const key of keys) {
    const match = APK.exec(key);
    if (!match) continue;
    const id = `${match[1]}.${match[2]}.${match[3]}`;
    const entry = versions.get(id) || { parts: [match[1], match[2], match[3]].map(Number), keys: [] };
    entry.keys.push(key);
    versions.set(id, entry);
  }
  const newestFirst = [...versions.values()].sort((a, b) => b.parts[0] - a.parts[0] || b.parts[1] - a.parts[1] || b.parts[2] - a.parts[2]);
  return newestFirst.slice(keep).flatMap(entry => entry.keys).sort();
}

export async function prune(env, { dryRun = env.DRY_RUN !== 'false' } = {}) {
  const keys = [];
  let cursor;
  do {
    const page = await env.RELEASES.list({ prefix: 'umbra-', cursor });
    for (const object of page.objects) keys.push(object.key);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  const doomed = planDeletes(keys);
  console.log(JSON.stringify({ dryRun, listed: keys.length, deleting: doomed }));
  if (!dryRun) {
    for (let i = 0; i < doomed.length; i += 1000) await env.RELEASES.delete(doomed.slice(i, i + 1000));
  }
  return doomed;
}

export default {
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(prune(env));
  },
  async fetch() {
    return new Response('Not found', { status: 404 });
  },
};
