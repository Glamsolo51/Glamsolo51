// All data and business rules. The HTTP layer (server.js) only maps requests to these calls.
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

export const STATUSES = ['new', 'cooking', 'ready', 'done', 'cancelled'];
const SLOT_MIN = 15;
const SESSION_DAYS = 30;

export class OrderError extends Error {
  constructor(status, message, extra = {}) {
    super(message);
    this.status = status;
    this.extra = extra;
  }
}

export function normalizePhone(value) {
  let d = String(value ?? '').replace(/\D/g, '');
  if (d.length === 11 && d[0] === '8') d = '7' + d.slice(1);
  if (d.length === 10 && d[0] === '9') d = '7' + d;
  return /^7\d{10}$/.test(d) ? '+' + d : null;
}

export function normalizeEmail(value) {
  const e = String(value ?? '').trim().toLowerCase();
  return e.length <= 100 && /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(e) ? e : null;
}

const hhmm = (ms) => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};
// Today's local time "HH:MM" as epoch ms (process.env.TZ is the pizzeria's time zone).
const atToday = (now, time) => {
  const [h, m] = time.split(':').map(Number);
  const d = new Date(now);
  d.setHours(h, m, 0, 0);
  return d.getTime();
};
const dayStart = (date) => {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(y, m - 1, d).getTime();
};
const hashPassword = (password, salt = randomBytes(16).toString('hex')) =>
  `${salt}:${scryptSync(password, salt, 32).toString('hex')}`;
const sha256 = (s) => createHash('sha256').update(s).digest('hex');
const sameText = (a, b) => a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const text = (value, max) => String(value ?? '').trim().slice(0, max);

export function openStore(file, menu, { now = Date.now } = {}) {
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS items (
      id TEXT PRIMARY KEY, category TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL,
      unit TEXT NOT NULL, price INTEGER NOT NULL, available INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS orders (
      id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT NOT NULL, created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL, status TEXT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL,
      email TEXT NOT NULL, pickup_at INTEGER NOT NULL, asap INTEGER NOT NULL, comment TEXT NOT NULL,
      source TEXT NOT NULL, total INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS orders_phone ON orders(phone, created_at);
    CREATE INDEX IF NOT EXISTS orders_email ON orders(email, created_at);
    CREATE INDEX IF NOT EXISTS orders_created ON orders(created_at);
    CREATE TABLE IF NOT EXISTS order_items (
      order_id INTEGER NOT NULL REFERENCES orders(id), item_id TEXT NOT NULL, name TEXT NOT NULL,
      unit TEXT NOT NULL, price INTEGER NOT NULL, qty INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS order_items_order ON order_items(order_id);
    CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
  `);

  // New menu.json items are added; price and availability edited by the admin are kept.
  const seed = db.prepare(`INSERT INTO items (id, category, name, description, unit, price, sort)
    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET
    category = excluded.category, name = excluded.name, description = excluded.description,
    unit = excluded.unit, sort = excluded.sort`);
  menu.items.forEach((it, i) => seed.run(it.id, it.category, it.name, it.description, it.unit, it.price, i));

  const getSetting = (key, fallback) => db.prepare('SELECT value FROM settings WHERE key = ?').get(key)?.value ?? fallback;
  const putSetting = (key, value) =>
    db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value')
      .run(key, String(value));

  function transaction(fn) {
    db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      db.exec('COMMIT');
      return result;
    } catch (e) {
      db.exec('ROLLBACK');
      throw e;
    }
  }

  function settings() {
    return {
      acceptingOrders: getSetting('accepting', '1') === '1',
      cooldownMinutes: Number(getSetting('cooldown', '15')),
    };
  }

  // Pickup slots left today, every 15 minutes, from "now + cooking time" until 15 minutes before closing.
  function schedule(t = now()) {
    const { open, close, prepMinutes } = menu.info;
    const openAt = atToday(t, open);
    const lastSlot = atToday(t, close) - SLOT_MIN * 60_000;
    const earliest = Math.max(t, openAt) + prepMinutes * 60_000;
    const step = SLOT_MIN * 60_000;
    const slots = [];
    for (let s = Math.ceil(earliest / step) * step; s <= lastSlot; s += step) slots.push(hhmm(s));
    const asapAllowed = t >= openAt && t + prepMinutes * 60_000 <= lastSlot;
    return { slots, asapAllowed, isOpen: t >= openAt && t < atToday(t, close) };
  }

  const itemRow = (r) => ({
    id: r.id, category: r.category, name: r.name, description: r.description,
    unit: r.unit, price: r.price, available: r.available === 1,
  });

  function publicMenu() {
    const s = settings();
    const sch = schedule();
    return {
      info: { ...menu.info, ...sch, acceptingOrders: s.acceptingOrders, cooldownMinutes: s.cooldownMinutes },
      categories: menu.categories,
      items: db.prepare('SELECT * FROM items ORDER BY sort').all().map(itemRow),
    };
  }

  function orderView(o, { full }) {
    const items = db.prepare('SELECT item_id, name, unit, price, qty FROM order_items WHERE order_id = ?').all(o.id)
      .map((r) => ({ id: r.item_id, name: r.name, unit: r.unit, price: r.price, qty: r.qty }));
    const view = {
      id: o.id, status: o.status, createdAt: o.created_at, updatedAt: o.updated_at,
      pickupAt: o.pickup_at, asap: o.asap === 1, name: o.name, total: o.total, items,
    };
    return full ? { ...view, phone: o.phone, email: o.email, comment: o.comment, source: o.source } : view;
  }

  function createOrder(input, source) {
    const t = now();
    const s = settings();
    if (!s.acceptingOrders) throw new OrderError(409, 'Сейчас пиццерия не принимает онлайн-заказы. Позвоните нам, пожалуйста.');

    const name = text(input?.name, 60);
    const phone = normalizePhone(input?.phone);
    const email = normalizeEmail(input?.email);
    const comment = text(input?.comment, 300);
    if (!name) throw new OrderError(400, 'Укажите имя.', { field: 'name' });
    if (!phone) throw new OrderError(400, 'Укажите телефон в формате +7 900 000-00-00.', { field: 'phone' });
    if (!email) throw new OrderError(400, 'Укажите корректную почту.', { field: 'email' });
    if (!['web', 'app'].includes(source)) throw new OrderError(400, 'Неизвестный источник заказа.');

    if (s.cooldownMinutes > 0) {
      const last = db.prepare(`SELECT MAX(created_at) AS at FROM orders
        WHERE (phone = ? OR email = ?) AND status != 'cancelled'`).get(phone, email).at;
      const waitMs = last == null ? 0 : last + s.cooldownMinutes * 60_000 - t;
      if (waitMs > 0) {
        const retryAfter = Math.ceil(waitMs / 1000);
        throw new OrderError(429,
          `С этого телефона или почты недавно уже был заказ. Новый можно оформить через ${Math.ceil(retryAfter / 60)} мин.`,
          { retryAfter });
      }
    }

    const { open, close, prepMinutes } = menu.info;
    let pickupAt;
    const asap = !input?.pickupTime || input.pickupTime === 'asap';
    if (asap) {
      if (!schedule(t).asapAllowed) throw new OrderError(400, 'Сейчас можно заказать только ко времени. Выберите время самовывоза.', { field: 'pickupTime' });
      pickupAt = t + prepMinutes * 60_000;
    } else {
      const at = /^([01]\d|2[0-3]):[0-5]\d$/.test(input.pickupTime) ? atToday(t, input.pickupTime) : NaN;
      // 10 minutes of grace: a slot can slip into the past while the customer fills in the form.
      const earliest = Math.max(t, atToday(t, open)) + (prepMinutes - 10) * 60_000;
      if (!(at >= earliest && at <= atToday(t, close) - SLOT_MIN * 60_000))
        throw new OrderError(400, 'Это время самовывоза уже недоступно. Выберите другое.', { field: 'pickupTime' });
      pickupAt = at;
    }

    const wanted = new Map();
    for (const line of Array.isArray(input?.items) ? input.items : []) {
      const qty = Number(line?.qty);
      if (!Number.isInteger(qty) || qty < 1 || qty > 20) throw new OrderError(400, 'Неверное количество в корзине.');
      wanted.set(String(line?.id), (wanted.get(String(line?.id)) ?? 0) + qty);
    }
    if (wanted.size === 0) throw new OrderError(400, 'Корзина пуста.');
    if (wanted.size > 40) throw new OrderError(400, 'Слишком много позиций в одном заказе.');
    const lines = [...wanted].map(([id, qty]) => {
      const item = db.prepare('SELECT * FROM items WHERE id = ?').get(id);
      if (!item) throw new OrderError(400, 'В корзине есть позиция, которой нет в меню. Обновите страницу.');
      if (!item.available) throw new OrderError(409, `«${item.name}» закончилось. Уберите из корзины.`, { itemId: id });
      if (qty > 20) throw new OrderError(400, 'Неверное количество в корзине.');
      return { item, qty };
    });
    const total = lines.reduce((sum, l) => sum + l.item.price * l.qty, 0);
    const token = randomBytes(16).toString('hex');

    return transaction(() => {
      const { lastInsertRowid } = db.prepare(`INSERT INTO orders
        (token, created_at, updated_at, status, name, phone, email, pickup_at, asap, comment, source, total)
        VALUES (?, ?, ?, 'new', ?, ?, ?, ?, ?, ?, ?, ?)`)
        .run(token, t, t, name, phone, email, pickupAt, asap ? 1 : 0, comment, source, total);
      const addLine = db.prepare('INSERT INTO order_items (order_id, item_id, name, unit, price, qty) VALUES (?, ?, ?, ?, ?, ?)');
      for (const { item, qty } of lines) addLine.run(lastInsertRowid, item.id, item.name, item.unit, item.price, qty);
      const o = db.prepare('SELECT * FROM orders WHERE id = ?').get(lastInsertRowid);
      return { ...orderView(o, { full: false }), token };
    });
  }

  function publicOrder(id, token) {
    const o = db.prepare('SELECT * FROM orders WHERE id = ?').get(Number(id));
    if (!o || typeof token !== 'string' || !sameText(o.token, token)) return null;
    return orderView(o, { full: false });
  }

  // Active orders of any day, or every order created on the given local date.
  function listOrders({ date } = {}) {
    const rows = date
      ? db.prepare('SELECT * FROM orders WHERE created_at >= ? AND created_at < ? ORDER BY id DESC')
        .all(dayStart(date), dayStart(date) + 86_400_000)
      : db.prepare(`SELECT * FROM orders WHERE status IN ('new', 'cooking', 'ready') ORDER BY pickup_at, id`).all();
    return rows.map((o) => orderView(o, { full: true }));
  }

  function setStatus(id, status) {
    if (!STATUSES.includes(status)) throw new OrderError(400, 'Неизвестный статус.');
    const r = db.prepare('UPDATE orders SET status = ?, updated_at = ? WHERE id = ?').run(status, now(), Number(id));
    if (r.changes === 0) throw new OrderError(404, 'Заказ не найден.');
    return orderView(db.prepare('SELECT * FROM orders WHERE id = ?').get(Number(id)), { full: true });
  }

  function updateItem(id, patch) {
    const item = db.prepare('SELECT * FROM items WHERE id = ?').get(String(id));
    if (!item) throw new OrderError(404, 'Позиция не найдена.');
    if (patch.available !== undefined) item.available = patch.available ? 1 : 0;
    if (patch.price !== undefined) {
      if (!Number.isInteger(patch.price) || patch.price < 1 || patch.price > 100_000) throw new OrderError(400, 'Цена должна быть целым числом рублей.');
      item.price = patch.price;
    }
    db.prepare('UPDATE items SET available = ?, price = ? WHERE id = ?').run(item.available, item.price, item.id);
    return itemRow(item);
  }

  function updateSettings(patch) {
    if (patch.acceptingOrders !== undefined) putSetting('accepting', patch.acceptingOrders ? '1' : '0');
    if (patch.cooldownMinutes !== undefined) {
      const m = patch.cooldownMinutes;
      if (!Number.isInteger(m) || m < 0 || m > 1440) throw new OrderError(400, 'Охлаждение: от 0 до 1440 минут.');
      putSetting('cooldown', m);
    }
    return settings();
  }

  // Sales by menu position for local dates from..to inclusive. Cancelled orders are not counted.
  function report(from, to) {
    if (![from, to].every((d) => /^\d{4}-\d\d-\d\d$/.test(d ?? ''))) throw new OrderError(400, 'Укажите период в формате ГГГГ-ММ-ДД.');
    const a = dayStart(from);
    const b = dayStart(to) + 86_400_000;
    const sum = db.prepare(`SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS revenue,
      COALESCE(SUM(status = 'cancelled'), 0) AS cancelled FROM orders WHERE created_at >= ? AND created_at < ?`).get(a, b);
    const counted = db.prepare(`SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS revenue
      FROM orders WHERE status != 'cancelled' AND created_at >= ? AND created_at < ?`).get(a, b);
    const items = db.prepare(`SELECT oi.item_id AS id, oi.name, oi.unit, i.category,
        SUM(oi.qty) AS qty, SUM(oi.qty * oi.price) AS revenue
      FROM order_items oi JOIN orders o ON o.id = oi.order_id LEFT JOIN items i ON i.id = oi.item_id
      WHERE o.status != 'cancelled' AND o.created_at >= ? AND o.created_at < ?
      GROUP BY oi.item_id, oi.name, oi.unit ORDER BY revenue DESC, qty DESC`).all(a, b)
      .map((r) => ({ ...r, share: counted.revenue ? r.revenue / counted.revenue : 0 }));
    return {
      from, to,
      orders: counted.orders,
      revenue: counted.revenue,
      averageCheck: counted.orders ? Math.round(counted.revenue / counted.orders) : 0,
      cancelled: sum.cancelled,
      items,
    };
  }

  // Staff account: one shared login. The hash lives in settings so the password can be changed in the cabinet.
  // ADMIN_PASSWORD applies only when its value changes, so a password changed in the cabinet survives restarts
  // and putting a new ADMIN_PASSWORD is the way to reset a forgotten one.
  function ensureAdmin(login, password) {
    putSetting('admin_login', login);
    if (password) {
      const [salt, hash] = getSetting('admin_env', ':').split(':');
      if (!salt || !sameText(hashPassword(password, salt).split(':')[1], hash)) {
        putSetting('admin_env', hashPassword(password));
        putSetting('admin_hash', hashPassword(password));
        db.prepare('DELETE FROM sessions').run();
      }
      return null;
    }
    if (getSetting('admin_hash', null)) return null;
    const generated = randomBytes(9).toString('base64url');
    putSetting('admin_hash', hashPassword(generated));
    return generated;
  }

  function checkPassword(login, password) {
    const [salt, hash] = getSetting('admin_hash', ':').split(':');
    if (!salt || typeof password !== 'string' || typeof login !== 'string') return false;
    const ok = sameText(hashPassword(password, salt).split(':')[1], hash);
    return ok && login.trim().toLowerCase() === getSetting('admin_login', '').toLowerCase();
  }

  function login(user, password) {
    if (!checkPassword(user, password)) return null;
    const token = randomBytes(32).toString('base64url');
    db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(now());
    db.prepare('INSERT INTO sessions (hash, expires_at) VALUES (?, ?)').run(sha256(token), now() + SESSION_DAYS * 86_400_000);
    return token;
  }

  const checkSession = (token) =>
    !!token && !!db.prepare('SELECT 1 FROM sessions WHERE hash = ? AND expires_at > ?').get(sha256(token), now());
  const logout = (token) => db.prepare('DELETE FROM sessions WHERE hash = ?').run(sha256(token ?? ''));

  function changePassword(current, next) {
    if (!checkPassword(getSetting('admin_login', ''), current)) throw new OrderError(403, 'Текущий пароль неверный.');
    if (typeof next !== 'string' || next.length < 8) throw new OrderError(400, 'Новый пароль — минимум 8 символов.');
    putSetting('admin_hash', hashPassword(next));
    db.prepare('DELETE FROM sessions').run(); // sign out every device that knew the old password
  }

  return {
    db, settings, schedule, publicMenu, createOrder, publicOrder, listOrders, setStatus,
    updateItem, updateSettings, report,
    ensureAdmin, login, checkSession, logout, changePassword,
  };
}
