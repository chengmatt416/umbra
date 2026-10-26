// Serves release files from the R2 bucket (binding RELEASES) on /download/<file>, with Range support so that
// phones can resume a 600+ MB download. /download/latest redirects to the current release.
const APK = /^umbra-[0-9][\w.\-]*\.(apk|apk\.sha256)$/;
// Laya's converted model files for the app's native engine: laya/<revision>/<file>, NPU files under npu/. The app
// carries every file's size and SHA-256, so this route only has to serve bytes.
const LAYA = /^laya\/[0-9a-f]{12}\/(?:npu\/)?[A-Za-z0-9][\w.\-]*\.(?:tflite|json)$/;
const NODE = /^umbra-node-\d+\.\d+\.\d+-(?:darwin-universal|macos-universal\.dmg|linux-(?:amd64|arm64)(?:\.tar\.gz|\.AppImage)?|SHA256SUMS)(?:\.minisig)?$/;

export async function onRequest(context) {
  const { request, env, params } = context;
  if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  const name = (params.path || []).join('/');
  if (name === '' || name === 'latest') {
    return new Response(null, { status: 302, headers: {
      Location: new URL(`/download/${env.LATEST_APK}`, request.url).toString(),
      'Cache-Control': 'no-store',
    } });
  }
  if (!APK.test(name) && !NODE.test(name) && !LAYA.test(name)) return new Response('Not found', { status: 404 });

  const range = request.headers.get('range');
  const object = request.method === 'HEAD'
    ? await env.RELEASES.head(name)
    : await env.RELEASES.get(name, range ? { range: request.headers } : undefined);
  if (!object) return new Response('Not found', { status: 404 });

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  headers.set('accept-ranges', 'bytes');
  headers.set('cache-control', 'public, max-age=3600');
  if (LAYA.test(name)) {
    headers.set('content-type', name.endsWith('.json') ? 'application/json' : 'application/octet-stream');
    // A revision's files never change; a new conversion gets a new revision folder.
    headers.set('cache-control', 'public, max-age=31536000, immutable');
  } else if (name.endsWith('.apk')) {
    headers.set('content-type', 'application/vnd.android.package-archive');
    headers.set('content-disposition', `attachment; filename="${name}"`);
  } else if (name.endsWith('.sha256') || name.endsWith('.minisig') || name.endsWith('-SHA256SUMS')) {
    headers.set('content-type', 'text/plain; charset=utf-8');
  } else {
    headers.set('content-type', name.endsWith('.dmg') ? 'application/x-apple-diskimage' : name.endsWith('.tar.gz') ? 'application/gzip' : 'application/octet-stream');
    headers.set('content-disposition', `attachment; filename="${name}"`);
  }
  let status = 200;
  if (range && object.range && request.method === 'GET') {
    const { offset = 0, length = object.size - offset } = object.range;
    headers.set('content-range', `bytes ${offset}-${offset + length - 1}/${object.size}`);
    headers.set('content-length', String(length));
    status = 206;
  } else {
    headers.set('content-length', String(object.size));
  }
  const response = new Response(request.method === 'HEAD' ? null : object.body, { status, headers });
  if (request.method === 'GET' && APK.test(name) && name.endsWith('.apk') && (range === null || /^bytes=0+-\d*$/i.test(range.trim()))) {
    // Count starts without identifying the requester or holding up the download.
    try {
      const count = Promise.resolve().then(() => {
        const month = new Date().toISOString().slice(0, 7);
        return env.RELEASES.put(`download-counts/v1/${month}/${crypto.randomUUID()}`, '1');
      }).catch(() => {});
      if (context.waitUntil) context.waitUntil(count);
      else await count;
    } catch {}
  }
  return response;
}
