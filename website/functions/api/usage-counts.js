// Only install and UTC monthly-active membership. No request headers, IPs or task data are recorded.
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const MAX_BODY = 256;
const headers = { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' };

async function body(request) {
  const size = request.headers.get('content-length');
  if (size !== null && (!/^\d+$/.test(size) || Number(size) > MAX_BODY)) return null;
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY) return null;
      chunks.push(value);
    }
    const bytes = new Uint8Array(length);
    let at = 0;
    for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.byteLength; }
    const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    return value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 1 &&
      typeof value.install_id === 'string' && ID.test(value.install_id) ? value.install_id : null;
  } catch { return null; }
  finally { try { await reader.cancel(); } catch {} reader.releaseLock(); }
}

async function digest(value) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}

export async function collectUsage(request, env, now = new Date()) {
  if (request.method !== 'POST') return new Response(null, { status: 405, headers: { ...headers, Allow: 'POST' } });
  if (new URL(request.url).search || request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json')
    return new Response(null, { status: 415, headers });
  const id = await body(request);
  if (!id) return new Response(null, { status: 400, headers });
  if (!env.RELEASES) return new Response(null, { status: 503, headers });
  try {
    const month = now.toISOString().slice(0, 7);
    const install = await digest(`umbra-install-v1:${id}`);
    const active = await digest(`umbra-active-v1:${month}:${id}`);
    const options = { onlyIf: { etagDoesNotMatch: '*' }, httpMetadata: { contentType: 'text/plain' } };
    await env.RELEASES.put(`usage-counts/v1/installs/${install}`, '1', options);
    await env.RELEASES.put(`usage-counts/v1/monthly/${month}/${active}`, '1', options);
    return new Response(null, { status: 204, headers });
  } catch { return new Response(null, { status: 503, headers }); }
}

export function onRequest(context) {
  return collectUsage(context.request, context.env);
}
