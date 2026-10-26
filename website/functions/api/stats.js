const SUMMARY = 'usage-counts/v1/summary.json';
const SINCE = '2026-10-25';
const MAX_AGE = 10 * 60 * 1000;
const headers = {
  'cache-control': 'public, max-age=300',
  'content-type': 'application/json; charset=utf-8',
  'x-content-type-options': 'nosniff',
  'access-control-allow-origin': '*',
};

async function count(bucket, prefix) {
  let total = 0;
  let cursor;
  do {
    const page = await bucket.list({ prefix, cursor, limit: 1000 });
    total += page.objects.length;
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return total;
}

export async function getStats(request, env, now = new Date()) {
  if (request.method !== 'GET' && request.method !== 'HEAD')
    return new Response(null, { status: 405, headers: { ...headers, Allow: 'GET, HEAD' } });
  const month = now.toISOString().slice(0, 7);
  try {
    let summary;
    // Bucket summaries avoid listing on every page view; refresh at UTC month rollover too.
    try {
      const cached = await env.RELEASES.get(SUMMARY);
      if (cached) {
        const value = await cached.json();
        const age = now.getTime() - Date.parse(value.updated);
        if (value.month === month && value.since === SINCE && age >= 0 && age < MAX_AGE &&
          ['downloads', 'installs', 'monthlyActive'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0)) summary = value;
      }
    } catch {}
    if (!summary) {
      const [downloads, installs, monthlyActive] = await Promise.all([
        count(env.RELEASES, 'download-counts/v1/'),
        count(env.RELEASES, 'usage-counts/v1/installs/'),
        count(env.RELEASES, `usage-counts/v1/monthly/${month}/`),
      ]);
      summary = { downloads, installs, monthlyActive, month, since: SINCE, updated: now.toISOString() };
      try {
        await env.RELEASES.put(SUMMARY, JSON.stringify(summary), { httpMetadata: { contentType: 'application/json' } });
      } catch {}
    }
    return new Response(request.method === 'HEAD' ? null : JSON.stringify(summary), { headers });
  } catch {
    return new Response(null, { status: 503, headers: { ...headers, 'cache-control': 'no-store' } });
  }
}

export function onRequest(context) {
  return getStats(context.request, context.env);
}
