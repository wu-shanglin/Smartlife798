const dayMillis = 86_400_000;
const reportDays = 30;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const countFormat = new Intl.NumberFormat('zh-CN');

export function reportingDay(now, daysAgo = 0) {
  return new Date(now + 8 * 3_600_000 - daysAgo * dayMillis).toISOString().slice(0, 10);
}

function reply(status, body = null, headers = {}) {
  return new Response(body, {
    status,
    headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers },
  });
}

async function readEvent(request, day) {
  if (request.headers.get('Content-Type')?.split(';')[0].trim() !== 'application/json') return null;
  const reader = request.body?.getReader();
  if (!reader) return null;
  const bytes = new Uint8Array(512);
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (size + value.length > bytes.length) {
        await reader.cancel();
        return null;
      }
      bytes.set(value, size);
      size += value.length;
    }
    const event = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, size)));
    if (!event || typeof event !== 'object' || event.day !== day ||
        typeof event.installation !== 'string' || !uuid.test(event.installation)) return null;
    const fields = Object.keys(event).length;
    if (event.kind === 'active' && fields === 3) return event;
    if (event.kind === 'start_accepted' && fields === 4 &&
        typeof event.eventId === 'string' && uuid.test(event.eventId)) return event;
  } catch {
    return null;
  } finally {
    reader.releaseLock();
  }
  return null;
}

async function ingest(request, env, day) {
  if (request.method !== 'POST') return reply(405, null, { Allow: 'POST' });
  if (!env.USAGE_DB) return reply(503);
  const { success } = await env.USAGE_RATE_LIMIT.limit({
    key: `ingest:${request.headers.get('CF-Connecting-IP') || 'unknown'}`,
  });
  if (!success) return reply(429);
  const event = await readEvent(request, day);
  if (!event) return reply(400);

  const active = env.USAGE_DB.prepare(
    'INSERT INTO daily_installations (day, installation) VALUES (?, ?) ON CONFLICT DO NOTHING',
  ).bind(day, event.installation);
  if (event.kind === 'active') {
    await active.run();
  } else {
    await env.USAGE_DB.batch([
      active,
      env.USAGE_DB.prepare(
        'INSERT INTO accepted_starts (event_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING',
      ).bind(event.eventId, day),
    ]);
  }
  return reply(204);
}

async function todayCounts(url, db, context) {
  const day = reportingDay(Date.now());
  const key = new Request(new URL(`/.usage-cache/${day}`, url));
  const cached = await caches.default.match(key);
  if (cached) return cached.json();
  const counts = await db.prepare(`SELECT
    (SELECT COUNT(*) FROM daily_installations WHERE day = ?1) AS active,
    (SELECT COUNT(*) FROM accepted_starts WHERE day = ?1) AS starts`).bind(day).first();
  context.waitUntil(caches.default.put(key, Response.json(counts, {
    headers: { 'Cache-Control': 'public, max-age=60' },
  })).catch(() => {}));
  return counts;
}

async function homepage(request, env, context) {
  // Fetch the full asset regardless of the client's previously cached HTML.
  const response = await env.ASSETS.fetch(new Request(request.url, { method: request.method }));
  if (!response.ok) return response;
  const page = new Response(response.body, response);
  page.headers.delete('ETag');
  page.headers.delete('Last-Modified');
  page.headers.delete('Content-Length');
  page.headers.set('Cache-Control', 'no-store');
  if (!env.USAGE_DB || request.method === 'HEAD') return page;

  let counts;
  try {
    counts = await todayCounts(request.url, env.USAGE_DB, context);
  } catch {
    return page;
  }
  return new HTMLRewriter()
    .on('[data-usage-active]', { element: el => el.setInnerContent(countFormat.format(counts.active)) })
    .on('[data-usage-starts]', { element: el => el.setInnerContent(countFormat.format(counts.starts)) })
    .transform(page);
}

export default {
  async fetch(request, env, context) {
    const url = new URL(request.url);
    if ((url.pathname === '/' || url.pathname === '/index.html') && ['GET', 'HEAD'].includes(request.method)) {
      return homepage(request, env, context);
    }
    if (url.pathname !== '/api/usage/v1') return env.ASSETS.fetch(request);
    try {
      return await ingest(request, env, reportingDay(Date.now()));
    } catch {
      return reply(503, '统计服务暂不可用');
    }
  },

  async scheduled(controller, env) {
    if (!env.USAGE_DB) return;
    const cutoff = reportingDay(controller.scheduledTime, reportDays - 1);
    await env.USAGE_DB.batch([
      env.USAGE_DB.prepare('DELETE FROM daily_installations WHERE day < ?').bind(cutoff),
      env.USAGE_DB.prepare('DELETE FROM accepted_starts WHERE day < ?').bind(cutoff),
    ]);
  },
};
