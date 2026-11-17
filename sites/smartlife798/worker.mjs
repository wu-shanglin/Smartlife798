import { renderDashboard } from './dashboard.mjs';

const dayMillis = 86_400_000;
const reportDays = 30;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const encoder = new TextEncoder();

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

async function authorized(request, password) {
  const credentials = request.headers.get('Authorization');
  if (!credentials || !credentials.startsWith('Basic ') || credentials.length > 1024) return false;
  let provided;
  try {
    provided = atob(credentials.slice(6));
  } catch {
    return false;
  }
  const [expectedHash, providedHash] = await Promise.all([`admin:${password}`, provided].map(
    value => crypto.subtle.digest('SHA-256', encoder.encode(value)),
  ));
  return crypto.subtle.timingSafeEqual(expectedHash, providedHash);
}

async function dashboard(request, env, now) {
  if (request.method !== 'GET') return reply(405, null, { Allow: 'GET' });
  if (!env.USAGE_DB || !env.STATS_PASSWORD) return reply(503, '统计服务尚未配置');
  if (!await authorized(request, env.STATS_PASSWORD)) {
    const { success } = await env.USAGE_RATE_LIMIT.limit({
      key: `dashboard:${request.headers.get('CF-Connecting-IP') || 'unknown'}`,
    });
    return success ? reply(401, null, { 'WWW-Authenticate': 'Basic realm="SmartLife798 stats"' }) : reply(429);
  }

  const from = reportingDay(now, reportDays - 1);
  const through = reportingDay(now);
  const [active, starts] = await env.USAGE_DB.batch([
    env.USAGE_DB.prepare('SELECT day, COUNT(*) AS total FROM daily_installations WHERE day BETWEEN ? AND ? GROUP BY day')
      .bind(from, through),
    env.USAGE_DB.prepare('SELECT day, COUNT(*) AS total FROM accepted_starts WHERE day BETWEEN ? AND ? GROUP BY day')
      .bind(from, through),
  ]);
  const activeByDay = new Map(active.results.map(row => [row.day, row.total]));
  const startsByDay = new Map(starts.results.map(row => [row.day, row.total]));
  const days = Array.from({ length: reportDays }, (_, offset) => {
    const day = reportingDay(now, offset);
    return { day, active: activeByDay.get(day) ?? 0, starts: startsByDay.get(day) ?? 0 };
  });
  return reply(200, renderDashboard(days), {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'",
    'Referrer-Policy': 'no-referrer',
    'X-Robots-Tag': 'noindex',
  });
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path !== '/stats' && path !== '/stats/' && path !== '/api/usage/v1') return env.ASSETS.fetch(request);
    const now = Date.now();
    try {
      return path === '/api/usage/v1' ? await ingest(request, env, reportingDay(now)) : await dashboard(request, env, now);
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
