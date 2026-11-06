// The HTTP layer against a real server process: a temporary database and a menu that takes orders around the clock.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = mkdtempSync(join(tmpdir(), 'pizza-test-'));
const menu = JSON.parse(readFileSync(new URL('../menu.json', import.meta.url), 'utf8'));
Object.assign(menu.info, { open: '00:00', lastOrder: '23:59', close: '23:59' });
writeFileSync(join(dir, 'menu.json'), JSON.stringify(menu));
const PORT = 20000 + Math.floor(Math.random() * 20000);
const base = `http://127.0.0.1:${PORT}`;
const pizza = menu.items.find((i) => i.category === 'pizza');
let server;

before(async () => {
  server = spawn(process.execPath, ['--disable-warning=ExperimentalWarning', fileURLToPath(new URL('../server.js', import.meta.url))], {
    env: { ...process.env, PORT: String(PORT), DB_PATH: join(dir, 'pizza.db'), MENU_PATH: join(dir, 'menu.json'), ADMIN_PASSWORD: 'test-password', ORDERS_PER_IP: '5' },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await new Promise((resolve, reject) => {
    server.stdout.on('data', (d) => String(d).includes(String(PORT)) && resolve());
    server.on('exit', (code) => reject(new Error(`server exited with ${code}`)));
  });
});
after(async () => {
  const exited = new Promise((resolve) => server.once('exit', resolve));
  server.kill();
  await exited; // Windows keeps the database file locked until the process is gone
  rmSync(dir, { recursive: true, force: true, maxRetries: 5 });
});

const json = (body, token) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  body: JSON.stringify(body),
});
let n = 0;
const newOrder = () => fetch(`${base}/api/orders`, json({
  name: 'Тест', phone: `+7900${String(1_000_000 + n++).padStart(7, '0')}`, email: `t${n}@test.ru`,
  pickupTime: 'asap', source: 'web', items: [{ id: pizza.id, qty: 1 }],
}));
const staffToken = async () => (await (await fetch(`${base}/api/admin/login`, json({ login: 'admin', password: 'test-password' }))).json()).token;

test('a form posted from another site is refused: JSON only', async () => {
  const res = await fetch(`${base}/api/orders`, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '{}' });
  assert.equal(res.status, 415);
});

test('the customer sees a status change at once, not on the next poll', async () => {
  const order = await (await newOrder()).json();
  const waiting = fetch(`${base}/api/orders/${order.id}?token=${order.token}&since=${order.updatedAt}`).then((r) => r.json());
  await new Promise((r) => setTimeout(r, 300));
  const token = await staffToken();
  const changedAt = Date.now();
  await fetch(`${base}/api/admin/orders/${order.id}/status`, json({ status: 'cooking' }, token));
  const seen = await waiting;
  assert.equal(seen.status, 'cooking');
  assert.ok(Date.now() - changedAt < 1000);
  // Without "since" the answer is immediate, as older app versions expect.
  assert.equal((await (await fetch(`${base}/api/orders/${order.id}?token=${order.token}`)).json()).status, 'cooking');
  assert.equal((await fetch(`${base}/api/orders/${order.id}?token=wrong&since=0`)).status, 404);
});

test('menu import needs the staff session and previews before saving', async () => {
  const body = { rows: [['ID', 'Цена, ₽'], [pizza.id, 777]], dryRun: true };
  assert.equal((await fetch(`${base}/api/admin/menu-import`, json(body))).status, 401);
  const token = await staffToken();
  const preview = await (await fetch(`${base}/api/admin/menu-import`, json(body, token))).json();
  assert.deepEqual(preview.changes.map((c) => c.price), [777]);
  const price = async () => (await (await fetch(`${base}/api/menu`)).json()).items.find((i) => i.id === pizza.id).price;
  assert.equal(await price(), pizza.price); // the preview saved nothing
  assert.equal((await fetch(`${base}/api/admin/menu-import`, json({ ...body, dryRun: false }, token))).status, 200);
  assert.equal(await price(), 777);
});

test('uploads take only real pictures', async () => {
  const token = await staffToken();
  const png = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');
  const up = (type, body, t = token) => fetch(`${base}/api/admin/uploads`, { method: 'POST', headers: { 'Content-Type': type, ...(t ? { Authorization: `Bearer ${t}` } : {}) }, body });
  assert.equal((await up('image/png', png, null)).status, 401);
  assert.equal((await up('image/png', Buffer.from('not a picture'))).status, 415);
  const { url } = await (await up('image/png', png)).json();
  assert.match(url, /^\/uploads\/[0-9a-f]{24}\.png$/);
  assert.equal((await fetch(base + url)).headers.get('content-type'), 'image/png');
});

test('files outside public/ are never served', async () => {
  for (const path of ['/..%2fserver.js', '/%2e%2e/server.js', '/..%5cserver.js', '/admin/..%2f..%2fstore.js', '/%E0%A4%A']) {
    assert.ok([400, 404].includes((await fetch(base + path)).status), path);
  }
  assert.equal((await fetch(`${base}/admin`)).status, 200);
});

test('one address cannot flood the kitchen with orders', async () => {
  const statuses = [];
  for (let i = 0; i < 6; i++) statuses.push((await newOrder()).status);
  assert.equal(statuses.at(-1), 429);
  assert.ok(statuses.slice(0, -2).every((s) => s === 201));
});
