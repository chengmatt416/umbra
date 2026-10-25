const APK = /^umbra-(\d+\.\d+\.\d+)\.apk$/;
const DOWNLOAD = /^umbra-\d+\.\d+\.\d+\.apk(?:\.sha256)?$/;

export default {
  async fetch(request, env) {
    if (!['GET', 'HEAD'].includes(request.method)) {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
    }
    const path = new URL(request.url).pathname;
    if (path === '/releases.json' && request.method === 'GET') {
      const objects = new Map();
      let cursor;
      do {
        const page = await env.RELEASES.list({ prefix: 'umbra-', cursor });
        for (const object of page.objects) objects.set(object.key, object);
        cursor = page.truncated ? page.cursor : undefined;
      } while (cursor);
      const releases = [];
      for (const [file, object] of objects) {
        const match = APK.exec(file);
        const checksum = objects.get(`${file}.sha256`);
        if (!match || !checksum || checksum.size > 1024 || object.size === 0) continue;
        releases.push({ file, version: match[1], bytes: object.size, etag: object.httpEtag, checksumEtag: checksum.httpEtag });
      }
      return Response.json({ releases }, { headers: { 'cache-control': 'no-store' } });
    }
    const file = path.slice('/download/'.length);
    if (!path.startsWith('/download/') || !DOWNLOAD.test(file)) return new Response('Not found', { status: 404 });
    const etag = request.headers.get('if-match');
    const object = request.method === 'HEAD'
      ? await env.RELEASES.head(file)
      : await env.RELEASES.get(file, etag ? { onlyIf: request.headers } : undefined);
    if (!object) return new Response('Not found', { status: 404 });
    if (request.method === 'GET' && !('body' in object)) return new Response('Object changed; retry the release', { status: 412 });
    return new Response(request.method === 'HEAD' ? null : object.body, { headers: {
      'content-type': file.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'text/plain; charset=utf-8',
      'content-length': String(object.size),
      'content-disposition': `attachment; filename="${file}"`,
      'cache-control': 'no-store',
      etag: object.httpEtag,
    } });
  },
};
