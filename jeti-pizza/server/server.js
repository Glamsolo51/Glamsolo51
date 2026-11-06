// HTTP layer: the website, the staff cabinet and the JSON API used by both and by the Android app.
import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { readFileSync, mkdirSync, createReadStream, statSync } from 'node:fs';
import { dirname, extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const menu = JSON.parse(readFileSync(join(here, 'menu.json'), 'utf8'));
process.env.TZ = menu.info.timezone; // opening hours and reports use the pizzeria's local time

const { openStore, OrderError } = await import('./store.js');

const PORT = Number(process.env.PORT ?? 8080);
const DB_PATH = process.env.DB_PATH ?? join(here, 'data', 'pizza.db');
const PUBLIC = join(here, 'public');
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.webp': 'image/webp', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json',
};
const SECURITY_HEADERS = {
  'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'same-origin',
};

mkdirSync(dirname(DB_PATH), { recursive: true });
const store = openStore(DB_PATH, menu);
const generated = store.ensureAdmin(process.env.ADMIN_LOGIN ?? 'admin', process.env.ADMIN_PASSWORD);
if (generated) console.log(`Staff cabinet: login "${process.env.ADMIN_LOGIN ?? 'admin'}", password "${generated}". Change it in Settings.`);

// Login brute-force guard: 10 failed attempts per address per 15 minutes.
const failures = new Map();
const clientIp = (req) =>
  (process.env.TRUST_PROXY ? req.headers['x-forwarded-for']?.split(',')[0].trim() : null) ?? req.socket.remoteAddress;

function send(res, status, body, headers = {}) {
  res.writeHead(status, { ...SECURITY_HEADERS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
  res.end(JSON.stringify(body));
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 32_768) throw new OrderError(413, 'Слишком большой запрос.');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
  } catch {
    throw new OrderError(400, 'Неверный JSON.');
  }
}

function serveStatic(req, res, path) {
  if (path === '/') path = '/index.html';
  if (path === '/admin' || path === '/admin/') path = '/admin/index.html';
  let file;
  try {
    file = normalize(join(PUBLIC, decodeURIComponent(path)));
  } catch {
    file = '';
  }
  if (!file.startsWith(PUBLIC + sep)) return send(res, 404, { error: 'Не найдено.' });
  let stat;
  try {
    stat = statSync(file);
  } catch {
    stat = null;
  }
  if (!stat?.isFile()) return send(res, 404, { error: 'Не найдено.' });
  const ext = extname(file);
  res.writeHead(200, {
    ...SECURITY_HEADERS,
    'Content-Type': TYPES[ext] ?? 'application/octet-stream',
    'Content-Length': stat.size,
    // Pages and scripts change with each deploy; photos and fonts don't.
    'Cache-Control': ['.html', '.js', '.css', '.json', '.webmanifest'].includes(ext) ? 'no-cache' : 'public, max-age=604800',
  });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).pipe(res);
}

async function handleAdmin(req, res, path, url) {
  if (path === '/api/admin/login' && req.method === 'POST') {
    const ip = clientIp(req);
    const f = failures.get(ip);
    if (f && f.until > Date.now() && f.count >= 10) return send(res, 429, { error: 'Слишком много попыток. Подождите 15 минут.' });
    const body = await readJson(req);
    const token = store.login(body.login, body.password);
    if (!token) {
      if (failures.size > 1000) for (const [key, v] of failures) if (v.until < Date.now()) failures.delete(key);
      const fresh = !f || f.until < Date.now();
      failures.set(ip, { count: fresh ? 1 : f.count + 1, until: fresh ? Date.now() + 15 * 60_000 : f.until });
      return send(res, 401, { error: 'Неверный логин или пароль.' });
    }
    failures.delete(ip);
    return send(res, 200, { token });
  }

  const token = req.headers.authorization?.replace(/^Bearer /, '');
  if (!store.checkSession(token)) return send(res, 401, { error: 'Войдите заново.' });

  // Android's HttpURLConnection cannot send PATCH, so the app tunnels it through POST.
  const method = req.method === 'POST' && req.headers['x-http-method-override'] === 'PATCH' ? 'PATCH' : req.method;
  const route = `${method} ${path.replace(/\/\d+(?=\/|$)/, '/:id').replace(/\/items\/[^/]+$/, '/items/:id')}`;
  const id = path.match(/\/(\d+)(?:\/|$)/)?.[1] ?? decodeURIComponent(path.split('/').pop());
  switch (route) {
    case 'POST /api/admin/logout':
      store.logout(token);
      return send(res, 200, { ok: true });
    case 'GET /api/admin/orders':
      return send(res, 200, { orders: store.listOrders({ date: url.searchParams.get('date') ?? undefined }), serverTime: Date.now() });
    case 'POST /api/admin/orders/:id/status':
      return send(res, 200, store.setStatus(id, (await readJson(req)).status));
    case 'GET /api/admin/menu':
      return send(res, 200, store.publicMenu());
    case 'PATCH /api/admin/items/:id':
      return send(res, 200, store.updateItem(id, await readJson(req)));
    case 'GET /api/admin/report':
      return send(res, 200, store.report(url.searchParams.get('from'), url.searchParams.get('to')));
    case 'GET /api/admin/settings':
      return send(res, 200, store.settings());
    case 'PUT /api/admin/settings':
      return send(res, 200, store.updateSettings(await readJson(req)));
    case 'POST /api/admin/password': {
      const body = await readJson(req);
      store.changePassword(body.current, body.next);
      return send(res, 200, { ok: true });
    }
  }
  return send(res, 404, { error: 'Не найдено.' });
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;
  if (path.startsWith('/api/admin/')) return handleAdmin(req, res, path, url);
  if (path === '/api/menu' && req.method === 'GET') return send(res, 200, store.publicMenu());
  if (path === '/api/orders' && req.method === 'POST') {
    const body = await readJson(req);
    return send(res, 201, store.createOrder(body, body.source));
  }
  const m = path.match(/^\/api\/orders\/(\d+)$/);
  if (m && req.method === 'GET') {
    const order = store.publicOrder(m[1], url.searchParams.get('token'));
    return order ? send(res, 200, order) : send(res, 404, { error: 'Заказ не найден.' });
  }
  if (path.startsWith('/api/')) return send(res, 404, { error: 'Не найдено.' });
  if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(req, res, path);
  return send(res, 405, { error: 'Метод не поддерживается.' });
}

createServer((req, res) => {
  handle(req, res).catch((e) => {
    if (e instanceof OrderError) {
      const headers = e.extra.retryAfter ? { 'Retry-After': String(e.extra.retryAfter) } : {};
      return send(res, e.status, { error: e.message, ...e.extra }, headers);
    }
    console.error(e);
    send(res, 500, { error: 'Ошибка сервера. Попробуйте ещё раз или позвоните нам.' });
  });
}).listen(PORT, () => {
  // The address a phone on the same Wi-Fi types into the test app.
  const lan = Object.values(networkInterfaces()).flat().filter((a) => a.family === 'IPv4' && !a.internal);
  console.log(`Йети Пицца: http://localhost:${PORT}  (кабинет: /admin)`);
  for (const a of lan) console.log(`С телефона в той же Wi-Fi сети: ${a.address}:${PORT}`);
});
