// HTTP layer: the website, the staff cabinet and the JSON API used by both and by the Android app.
import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { readFileSync, mkdirSync, createReadStream, statSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { dirname, extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// MENU_PATH: another menu file, e.g. one that takes orders around the clock for automated tests.
const menu = JSON.parse(readFileSync(process.env.MENU_PATH ?? join(here, 'menu.json'), 'utf8'));
process.env.TZ = menu.info.timezone; // opening hours and reports use the pizzeria's local time

const { openStore, OrderError } = await import('./store.js');
const { qrSvg } = await import('./qr.js');

const PORT = Number(process.env.PORT ?? 8080);
const DB_PATH = process.env.DB_PATH ?? join(here, 'data', 'pizza.db');
const PUBLIC = join(here, 'public');
// Photos uploaded in the cabinet live next to the database, so backups and Docker volumes keep them.
const UPLOADS = process.env.UPLOAD_DIR ?? join(dirname(DB_PATH), 'uploads');
const IMAGE_TYPES = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.webp': 'image/webp', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json',
};
const SECURITY_HEADERS = {
  'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'same-origin',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=()',
};
const FINAL = ['done', 'cancelled'];

mkdirSync(dirname(DB_PATH), { recursive: true });
mkdirSync(UPLOADS, { recursive: true });
const store = openStore(DB_PATH, menu);
const generated = store.ensureAdmin(process.env.ADMIN_LOGIN ?? 'admin', process.env.ADMIN_PASSWORD);
if (generated) console.log(`Staff cabinet: login "${process.env.ADMIN_LOGIN ?? 'admin'}", password "${generated}". Change it in Settings.`);

const clientIp = (req) =>
  (process.env.TRUST_PROXY ? req.headers['x-forwarded-for']?.split(',')[0].trim() : null) ?? req.socket.remoteAddress;

// Counts events per address in a sliding window, e.g. failed logins or placed orders.
function limiter(max, windowMs) {
  const hits = new Map();
  return {
    blocked(key) {
      const h = hits.get(key);
      return max > 0 && !!h && h.until > Date.now() && h.count >= max;
    },
    retryAfter(key) {
      return Math.max(1, Math.ceil(((hits.get(key)?.until ?? Date.now()) - Date.now()) / 1000));
    },
    add(key) {
      if (hits.size > 5000) for (const [k, v] of hits) if (v.until < Date.now()) hits.delete(k);
      const h = hits.get(key);
      const fresh = !h || h.until < Date.now();
      hits.set(key, { count: fresh ? 1 : h.count + 1, until: fresh ? Date.now() + windowMs : h.until });
    },
    clear: (key) => hits.delete(key),
  };
}
// Login brute-force guard: 10 failed attempts per address per 15 minutes.
const failedLogins = limiter(10, 15 * 60_000);
// Prank-order flood guard. Generous, because customers of one mobile operator can share an address. 0 turns it off.
const placedOrders = limiter(Number(process.env.ORDERS_PER_IP ?? 30), 10 * 60_000);

/* A customer's status screen waits here until the kitchen changes the order, so the change shows at once. */
const WAIT_MS = 25_000;
const MAX_WAITERS = 5000;
const waiters = new Map(); // order id -> Set of wake-up functions
let waiting = 0;

function waitForChange(id, res) {
  if (waiting >= MAX_WAITERS) return Promise.resolve();
  return new Promise((resolve) => {
    if (!waiters.has(id)) waiters.set(id, new Set());
    const set = waiters.get(id);
    let settled = false;
    const wake = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      res.off('close', wake);
      set.delete(wake);
      if (!set.size && waiters.get(id) === set) waiters.delete(id);
      waiting--;
      resolve();
    };
    const timer = setTimeout(wake, WAIT_MS);
    res.on('close', wake); // the customer closed the screen
    set.add(wake);
    waiting++;
  });
}
const wakeOrder = (id) => [...(waiters.get(id) ?? [])].forEach((wake) => wake());

function send(res, status, body, headers = {}) {
  if (res.destroyed) return;
  res.writeHead(status, { ...SECURITY_HEADERS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
  res.end(JSON.stringify(body));
}

async function readBody(req, limit) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new OrderError(413, `Слишком большой файл или запрос (до ${Math.round(limit / 1048576) || 1} МБ).`);
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJson(req, limit = 32_768) {
  // A cross-site form can post text/plain without asking the browser first; JSON needs CORS, which we never allow.
  if (!/^application\/json\b/i.test(req.headers['content-type'] ?? '')) throw new OrderError(415, 'Запрос должен быть в формате JSON.');
  const body = await readBody(req, limit);
  try {
    return JSON.parse(body.toString('utf8') || '{}');
  } catch {
    throw new OrderError(400, 'Неверный JSON.');
  }
}

// Saves an uploaded photo after checking its first bytes, not just the type the browser claims.
async function saveUpload(req) {
  const ext = IMAGE_TYPES[req.headers['content-type']];
  if (!ext) throw new OrderError(415, 'Загрузите фото в формате JPG, PNG или WebP.');
  const data = await readBody(req, 5 * 1048576);
  const magic = { jpg: [0xff, 0xd8, 0xff], png: [0x89, 0x50, 0x4e, 0x47], webp: [0x52, 0x49, 0x46, 0x46] }[ext];
  if (!magic.every((b, i) => data[i] === b) || (ext === 'webp' && data.toString('latin1', 8, 12) !== 'WEBP')) {
    throw new OrderError(415, 'Файл не похож на фото.');
  }
  const name = `${randomBytes(12).toString('hex')}.${ext}`;
  writeFileSync(join(UPLOADS, name), data);
  return { url: `/uploads/${name}` };
}

function serveStatic(req, res, path) {
  const upload = path.match(/^\/uploads\/([0-9a-f]{24}\.(?:jpg|png|webp))$/);
  if (upload) return sendFile(req, res, join(UPLOADS, upload[1]));
  if (path === '/') path = '/index.html';
  if (path === '/admin' || path === '/admin/') path = '/admin/index.html';
  let file;
  try {
    file = normalize(join(PUBLIC, decodeURIComponent(path)));
  } catch {
    file = '';
  }
  if (!file.startsWith(PUBLIC + sep)) return send(res, 404, { error: 'Не найдено.' });
  return sendFile(req, res, file);
}

function sendFile(req, res, file) {
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
  createReadStream(file).on('error', () => res.destroy()).pipe(res);
}

async function handleAdmin(req, res, path, url) {
  if (path === '/api/admin/login' && req.method === 'POST') {
    const ip = clientIp(req);
    if (failedLogins.blocked(ip)) return send(res, 429, { error: 'Слишком много попыток. Подождите 15 минут.' });
    const body = await readJson(req);
    const token = store.login(body.login, body.password);
    if (!token) {
      failedLogins.add(ip);
      return send(res, 401, { error: 'Неверный логин или пароль.' });
    }
    failedLogins.clear(ip);
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
    case 'POST /api/admin/orders/:id/status': {
      const order = store.setStatus(id, (await readJson(req)).status);
      wakeOrder(order.id);
      return send(res, 200, order);
    }
    case 'GET /api/admin/menu':
      return send(res, 200, store.publicMenu());
    case 'PATCH /api/admin/items/:id':
      return send(res, 200, store.updateItem(id, await readJson(req)));
    case 'POST /api/admin/orders/:id/payment': {
      const order = store.setPayment(id, (await readJson(req)).status);
      wakeOrder(order.id);
      return send(res, 200, order);
    }
    case 'GET /api/admin/menu-template':
      return send(res, 200, store.exportMenu());
    case 'POST /api/admin/menu-import': {
      // Rows read from Excel or CSV in the browser (public/admin/xlsx.js); { dryRun: true } only previews.
      const body = await readJson(req, 2 * 1048576);
      return send(res, 200, store.importMenu(body.rows, { dryRun: !!body.dryRun }));
    }
    case 'POST /api/admin/uploads':
      return send(res, 201, await saveUpload(req));
    case 'PUT /api/admin/site':
      return send(res, 200, store.updateSite(await readJson(req)));
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
    const ip = clientIp(req);
    if (placedOrders.blocked(ip)) {
      return send(res, 429, { error: `С этого подключения слишком много заказов подряд. Попробуйте через ${Math.ceil(placedOrders.retryAfter(ip) / 60)} мин или позвоните нам.` });
    }
    const body = await readJson(req);
    const order = store.createOrder(body, body.source);
    placedOrders.add(ip);
    return send(res, 201, order);
  }
  const m = path.match(/^\/api\/orders\/(\d+)$/);
  if (m && req.method === 'GET') {
    const token = url.searchParams.get('token');
    let order = store.publicOrder(m[1], token);
    if (!order) return send(res, 404, { error: 'Заказ не найден.' });
    // ?since=<updatedAt the screen shows>: answer when the order changes, or after 25 seconds with no change.
    const since = Number(url.searchParams.get('since'));
    if (url.searchParams.has('since') && order.updatedAt <= since && !FINAL.includes(order.status)) {
      await waitForChange(order.id, res);
      order = store.publicOrder(m[1], token) ?? order;
    }
    return send(res, 200, order);
  }
  const qr = path.match(/^\/api\/orders\/(\d+)\/qr\.svg$/);
  if (qr && req.method === 'GET') {
    const value = store.publicOrder(qr[1], url.searchParams.get('token'))?.pay?.qr;
    if (!value) return send(res, 404, { error: 'QR для этого заказа нет.' });
    res.writeHead(200, { ...SECURITY_HEADERS, 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' });
    return res.end(qrSvg(value));
  }
  const paid = path.match(/^\/api\/orders\/(\d+)\/paid$/);
  if (paid && req.method === 'POST') {
    const order = store.claimPaid(paid[1], url.searchParams.get('token'));
    wakeOrder(order.id);
    return send(res, 200, order);
  }
  if (path.startsWith('/api/')) return send(res, 404, { error: 'Не найдено.' });
  if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(req, res, path);
  return send(res, 405, { error: 'Метод не поддерживается.' });
}

const server = createServer((req, res) => {
  handle(req, res).catch((e) => {
    if (res.headersSent) return res.destroy();
    if (e instanceof OrderError) {
      const headers = e.extra.retryAfter ? { 'Retry-After': String(e.extra.retryAfter) } : {};
      return send(res, e.status, { error: e.message, ...e.extra }, headers);
    }
    if (e instanceof URIError) return send(res, 400, { error: 'Неверный адрес.' });
    console.error(e);
    send(res, 500, { error: 'Ошибка сервера. Попробуйте ещё раз или позвоните нам.' });
  });
});
// A client that sends its request slowly (or never finishes it) is dropped instead of holding a connection.
server.headersTimeout = 15_000;
server.requestTimeout = 30_000;
server.listen(PORT, () => {
  // The address a phone on the same Wi-Fi types into the test app.
  const lan = Object.values(networkInterfaces()).flat().filter((a) => a.family === 'IPv4' && !a.internal);
  console.log(`Йети Пицца: http://localhost:${PORT}  (кабинет: /admin)`);
  for (const a of lan) console.log(`С телефона в той же Wi-Fi сети: ${a.address}:${PORT}`);
});
