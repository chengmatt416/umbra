// Serves release files from the R2 bucket (binding RELEASES) on /download/<file>, with Range support so that
// phones can resume a 600+ MB download. /download/latest redirects to the current release.
const ALLOWED = /^umbra-[0-9][\w.\-]*\.(apk|apk\.sha256)$/;

export async function onRequest({ request, env, params }) {
  if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  const name = (params.path || []).join('/');
  if (name === '' || name === 'latest') {
    return Response.redirect(new URL(`/download/${env.LATEST_APK}`, request.url).toString(), 302);
  }
  if (!ALLOWED.test(name)) return new Response('Not found', { status: 404 });

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
  if (name.endsWith('.apk')) {
    headers.set('content-type', 'application/vnd.android.package-archive');
    headers.set('content-disposition', `attachment; filename="${name}"`);
  } else {
    headers.set('content-type', 'text/plain; charset=utf-8');
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
  return new Response(request.method === 'HEAD' ? null : object.body, { status, headers });
}
