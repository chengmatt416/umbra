// Temporary multipart uploader for the R2 bucket (wrangler's own `r2 object put` stops at 300 MiB; the APK is bigger).
// Deployed only for the length of an upload by ../upload-release.mjs, guarded by a random token, then deleted.
export default {
  async fetch(req, env) {
    if (req.headers.get('x-token') !== env.TOKEN) return new Response('forbidden', { status: 403 });
    const u = new URL(req.url);
    const key = u.searchParams.get('key');
    if (!key || !/^umbra-[\w.\-]+$/.test(key)) return new Response('bad key', { status: 400 });
    const type = key.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'text/plain; charset=utf-8';
    if (u.pathname === '/put') {
      const o = await env.BUCKET.put(key, req.body, { httpMetadata: { contentType: type } });
      return Response.json({ etag: o.httpEtag, size: o.size });
    }
    if (u.pathname === '/init') {
      const m = await env.BUCKET.createMultipartUpload(key, { httpMetadata: { contentType: type } });
      return Response.json({ uploadId: m.uploadId });
    }
    const m = env.BUCKET.resumeMultipartUpload(key, u.searchParams.get('uploadId'));
    if (u.pathname === '/part') return Response.json(await m.uploadPart(Number(u.searchParams.get('n')), req.body));
    if (u.pathname === '/complete') {
      const o = await m.complete(await req.json());
      return Response.json({ etag: o.httpEtag, size: o.size });
    }
    return new Response('not found', { status: 404 });
  },
};
