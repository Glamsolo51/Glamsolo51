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
// Pictures the site may show: bundled ones and those uploaded through the cabinet.
export const isImagePath = (p) => typeof p === 'string' && /^\/(uploads|img)\/[\w./-]+\.(webp|jpe?g|png)$/.test(p) && !p.includes('..');

// Filters for the menu, read from the composition so they follow the menu when it changes.
export const TAGS = [
  { id: 'chicken', name: 'Курица', re: /кур(иц|ин|ой)|наггетс|стрипс/ },
  { id: 'mushrooms', name: 'Грибы', re: /гриб|шампиньон/ },
  { id: 'spicy', name: 'Острое', re: /халапен|остр|чили|пепперони/ },
  { id: 'meat', name: 'Мясное', re: /колбас|ветчин|бекон|пепперони|салями|мяс/ },
  { id: 'veggie', name: 'Без мяса', re: null },
];
export function tagsFor(item) {
  const s = `${item.name} ${item.description}`.toLowerCase();
  const tags = TAGS.filter((t) => t.re?.test(s)).map((t) => t.id);
  if (['pizza', 'sweet', 'salads'].includes(item.category) && !tags.includes('chicken') && !tags.includes('meat')) tags.push('veggie');
  return tags;
}

const SITE_DEFAULTS = {
  tagline: '24 лучшие пиццы в городе Канаш',
  lead: 'Закажите онлайн и заберите горячую пиццу без очереди.',
  about: 'Кафе, быстрое питание и кофейня. Пицца, закуски, салаты, кофе и молочные коктейли — в зале или с собой.',
  hero: '/img/products/pizza-pepperoni.webp',
  gallery: ['/img/pizza-yeti.webp', '/img/coffee-break.webp', '/img/pizza-coffee.webp'],
};
// Columns of the Excel template, in order. Import finds them by header, so the order may change.
export const MENU_COLUMNS = ['ID', 'Раздел', 'Название', 'Описание (состав)', 'Объём', 'Цена, ₽', 'В наличии', 'Упаковка'];

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
  // Columns added after the first release; ALTER keeps the orders of an existing database.
  const addColumn = (table, column, type) => {
    if (!db.prepare(`PRAGMA table_info(${table})`).all().some((c) => c.name === column)) db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`);
  };
  addColumn('items', 'photo', 'TEXT');
  addColumn('items', 'pack', 'TEXT');
  addColumn('orders', 'payment', "TEXT NOT NULL DEFAULT 'cash'");
  addColumn('orders', 'payment_status', "TEXT NOT NULL DEFAULT 'none'");

  // menu.json only seeds: it adds items the database doesn't have yet. After that the cabinet and the
  // Excel import own the menu. Photo and packaging fill in once ('' means the admin removed them).
  const seed = db.prepare(`INSERT INTO items (id, category, name, description, unit, price, sort, photo, pack)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET
    photo = COALESCE(items.photo, excluded.photo), pack = COALESCE(items.pack, excluded.pack)`);
  menu.items.forEach((it, i) => seed.run(it.id, it.category, it.name, it.description, it.unit, it.price, i, it.photo ?? null, it.pack ?? null));

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

  function payment() {
    return {
      transfer: getSetting('pay_transfer', '1') === '1',
      phone: getSetting('pay_phone', '+79279960111'),
      recipient: getSetting('pay_recipient', ''),
      bank: getSetting('pay_bank', ''),
      // Bank details for a payment QR (ГОСТ Р 56042, "ST00012"); without them there is no QR, only the phone.
      account: getSetting('pay_account', ''),
      bic: getSetting('pay_bic', ''),
      corr: getSetting('pay_corr', ''),
    };
  }

  function settings() {
    return {
      acceptingOrders: getSetting('accepting', '1') === '1',
      cooldownMinutes: Number(getSetting('cooldown', '15')),
      payment: payment(),
    };
  }

  const site = () => ({ ...SITE_DEFAULTS, ...JSON.parse(getSetting('site', '{}')) });

  // ST00012 payment QR that bank apps scan into a transfer with the sum and order number filled in.
  function paymentQr(order, p = payment()) {
    if (![p.recipient, p.bank, p.account, p.bic, p.corr].every(Boolean)) return '';
    const clean = (v) => String(v).replace(/\|/g, ' ');
    return ['ST00012', `Name=${clean(p.recipient)}`, `PersonalAcc=${p.account}`, `BankName=${clean(p.bank)}`, `BIC=${p.bic}`,
      `CorrespAcc=${p.corr}`, `Sum=${order.total * 100}`, `Purpose=Оплата заказа №${order.id}`].join('|');
  }

  // Orders are taken from `open` until `lastOrder` (that whole minute included). Pickup is "as soon as possible"
  // or a slot every 15 minutes from "now + cooking time" until 15 minutes before `close`.
  function schedule(t = now()) {
    const { open, close, prepMinutes } = menu.info;
    const lastOrder = menu.info.lastOrder ?? close;
    const orderingOpen = t >= atToday(t, open) && t < atToday(t, lastOrder) + 60_000;
    const lastSlot = atToday(t, close) - SLOT_MIN * 60_000;
    const step = SLOT_MIN * 60_000;
    const slots = [];
    if (orderingOpen) for (let s = Math.ceil((t + prepMinutes * 60_000) / step) * step; s <= lastSlot; s += step) slots.push(hhmm(s));
    // isOpen stays for app versions that still read it.
    return { slots, asapAllowed: orderingOpen, orderingOpen, isOpen: orderingOpen, lastOrder };
  }

  const closedMessage = () => `Онлайн-заказы принимаем с ${menu.info.open} до ${menu.info.lastOrder ?? menu.info.close}.`;

  const itemRow = (r) => ({
    id: r.id, category: r.category, name: r.name, description: r.description,
    unit: r.unit, price: r.price, available: r.available === 1,
    photo: r.photo || '', pack: r.pack || '', tags: tagsFor(r),
  });
  // Items that other items name as their packaging: the cart adds them by itself.
  const packIds = () => new Set(db.prepare("SELECT DISTINCT pack FROM items WHERE pack IS NOT NULL AND pack != ''").all().map((r) => r.pack));

  function publicMenu() {
    const s = settings();
    const sch = schedule();
    const { transfer, phone, recipient, bank } = s.payment;
    return {
      info: { ...menu.info, ...sch, acceptingOrders: s.acceptingOrders, cooldownMinutes: s.cooldownMinutes, payment: { transfer, phone, recipient, bank } },
      categories: menu.categories,
      tags: TAGS.map(({ id, name }) => ({ id, name })),
      site: site(),
      items: db.prepare('SELECT * FROM items ORDER BY sort').all().map(itemRow),
    };
  }

  // Lines of many orders in one query: the staff board asks for every active order every few seconds.
  function linesOf(ids) {
    const byOrder = new Map(ids.map((id) => [id, []]));
    for (let i = 0; i < ids.length; i += 500) {
      const chunk = ids.slice(i, i + 500);
      const rows = db.prepare(`SELECT order_id, item_id, name, unit, price, qty FROM order_items
        WHERE order_id IN (${chunk.map(() => '?').join(',')}) ORDER BY rowid`).all(...chunk);
      for (const r of rows) byOrder.get(r.order_id).push({ id: r.item_id, name: r.name, unit: r.unit, price: r.price, qty: r.qty });
    }
    return byOrder;
  }

  function orderView(o, { full, items = linesOf([o.id]).get(o.id) }) {
    const view = {
      id: o.id, status: o.status, createdAt: o.created_at, updatedAt: o.updated_at,
      pickupAt: o.pickup_at, asap: o.asap === 1, name: o.name, total: o.total, items,
      payment: o.payment, paymentStatus: o.payment_status,
    };
    if (o.payment === 'transfer') {
      const p = payment();
      view.pay = { phone: p.phone, recipient: p.recipient, bank: p.bank, qr: paymentQr(o, p) };
    }
    return full ? { ...view, phone: o.phone, email: o.email, comment: o.comment, source: o.source } : view;
  }

  function createOrder(input, source) {
    const t = now();
    const s = settings();
    if (!s.acceptingOrders) throw new OrderError(409, 'Сейчас пиццерия не принимает онлайн-заказы. Позвоните нам, пожалуйста.');
    if (!schedule(t).orderingOpen) throw new OrderError(409, closedMessage(), { field: 'pickupTime' });

    const name = text(input?.name, 60);
    const phone = normalizePhone(input?.phone);
    const email = normalizeEmail(input?.email);
    const comment = text(input?.comment, 300);
    if (!name) throw new OrderError(400, 'Укажите имя.', { field: 'name' });
    if (!phone) throw new OrderError(400, 'Укажите телефон в формате +7 900 000-00-00.', { field: 'phone' });
    if (!email) throw new OrderError(400, 'Укажите корректную почту.', { field: 'email' });
    if (!['web', 'app'].includes(source)) throw new OrderError(400, 'Неизвестный источник заказа.');
    const pay = input?.payment === 'transfer' ? 'transfer' : 'cash';
    if (pay === 'transfer' && !s.payment.transfer) throw new OrderError(400, 'Оплата переводом сейчас недоступна, оплатите при получении.', { field: 'payment' });

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

    const { close, prepMinutes } = menu.info;
    let pickupAt;
    const asap = !input?.pickupTime || input.pickupTime === 'asap';
    if (asap) {
      pickupAt = t + prepMinutes * 60_000;
    } else {
      const at = /^([01]\d|2[0-3]):[0-5]\d$/.test(input.pickupTime) ? atToday(t, input.pickupTime) : NaN;
      // 10 minutes of grace: a slot can slip into the past while the customer fills in the form.
      const earliest = t + (prepMinutes - 10) * 60_000;
      if (!(at >= earliest && at <= atToday(t, close) - SLOT_MIN * 60_000))
        throw new OrderError(400, 'Это время самовывоза уже недоступно. Выберите другое.', { field: 'pickupTime' });
      pickupAt = at;
    }

    const packs = packIds();
    const wanted = new Map();
    for (const line of Array.isArray(input?.items) ? input.items : []) {
      const qty = Number(line?.qty);
      if (!Number.isInteger(qty) || qty < 1 || qty > 20) throw new OrderError(400, 'Неверное количество в корзине.');
      if (packs.has(String(line?.id))) continue; // packaging is counted below, whatever the client sent
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
    // Pickup: one box or cup per pizza, salad, snack or drink, as each item's packaging says.
    const needed = new Map();
    for (const { item, qty } of lines) if (item.pack) needed.set(item.pack, (needed.get(item.pack) ?? 0) + qty);
    for (const [id, qty] of needed) {
      const pack = db.prepare('SELECT * FROM items WHERE id = ?').get(id);
      if (pack?.available) lines.push({ item: pack, qty }); // out of boxes: the order still goes through
    }
    const total = lines.reduce((sum, l) => sum + l.item.price * l.qty, 0);
    const token = randomBytes(16).toString('hex');

    return transaction(() => {
      const { lastInsertRowid } = db.prepare(`INSERT INTO orders
        (token, created_at, updated_at, status, name, phone, email, pickup_at, asap, comment, source, total, payment, payment_status)
        VALUES (?, ?, ?, 'new', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
        .run(token, t, t, name, phone, email, pickupAt, asap ? 1 : 0, comment, source, total, pay, pay === 'transfer' ? 'awaiting' : 'none');
      const addLine = db.prepare('INSERT INTO order_items (order_id, item_id, name, unit, price, qty) VALUES (?, ?, ?, ?, ?, ?)');
      for (const { item, qty } of lines) addLine.run(lastInsertRowid, item.id, item.name, item.unit, item.price, qty);
      const o = db.prepare('SELECT * FROM orders WHERE id = ?').get(lastInsertRowid);
      return { ...orderView(o, { full: false }), token };
    });
  }

  const ownOrder = (id, token) => {
    const o = db.prepare('SELECT * FROM orders WHERE id = ?').get(Number(id));
    return o && typeof token === 'string' && sameText(o.token, token) ? o : null;
  };

  function publicOrder(id, token) {
    const o = ownOrder(id, token);
    return o ? orderView(o, { full: false }) : null;
  }

  // The customer says the transfer is sent; staff then check the bank and confirm.
  function claimPaid(id, token) {
    const o = ownOrder(id, token);
    if (!o) throw new OrderError(404, 'Заказ не найден.');
    if (o.payment !== 'transfer') throw new OrderError(400, 'Этот заказ оплачивается при получении.');
    if (o.payment_status === 'awaiting') db.prepare("UPDATE orders SET payment_status = 'claimed', updated_at = ? WHERE id = ?").run(now(), o.id);
    return publicOrder(id, token);
  }

  function setPayment(id, status) {
    if (!['awaiting', 'claimed', 'paid'].includes(status)) throw new OrderError(400, 'Неизвестный статус оплаты.');
    const r = db.prepare("UPDATE orders SET payment_status = ?, updated_at = ? WHERE id = ? AND payment = 'transfer'").run(status, now(), Number(id));
    if (r.changes === 0) throw new OrderError(404, 'Заказ с оплатой переводом не найден.');
    return orderView(db.prepare('SELECT * FROM orders WHERE id = ?').get(Number(id)), { full: true });
  }

  // Active orders of any day, or every order created on the given local date.
  function listOrders({ date } = {}) {
    if (date !== undefined && !/^\d{4}-\d\d-\d\d$/.test(date)) throw new OrderError(400, 'Укажите день в формате ГГГГ-ММ-ДД.');
    const rows = date
      ? db.prepare('SELECT * FROM orders WHERE created_at >= ? AND created_at < ? ORDER BY id DESC')
        .all(dayStart(date), dayStart(date) + 86_400_000)
      : db.prepare(`SELECT * FROM orders WHERE status IN ('new', 'cooking', 'ready') ORDER BY pickup_at, id`).all();
    const lines = linesOf(rows.map((o) => o.id));
    return rows.map((o) => orderView(o, { full: true, items: lines.get(o.id) }));
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
    if (patch.photo !== undefined) {
      if (patch.photo !== '' && !isImagePath(patch.photo)) throw new OrderError(400, 'Неверный адрес фото.');
      item.photo = patch.photo;
    }
    db.prepare('UPDATE items SET available = ?, price = ?, photo = ? WHERE id = ?').run(item.available, item.price, item.photo, item.id);
    return itemRow(item);
  }

  function updateSite(patch) {
    const next = site();
    for (const [key, max] of [['tagline', 80], ['lead', 200], ['about', 600]]) {
      if (patch[key] !== undefined) next[key] = text(patch[key], max);
    }
    if (patch.hero !== undefined) {
      if (!isImagePath(patch.hero)) throw new OrderError(400, 'Неверный адрес главного фото.');
      next.hero = patch.hero;
    }
    if (patch.gallery !== undefined) {
      if (!Array.isArray(patch.gallery) || patch.gallery.length > 6 || !patch.gallery.every(isImagePath)) throw new OrderError(400, 'В галерее до 6 фото.');
      next.gallery = patch.gallery;
    }
    putSetting('site', JSON.stringify(next));
    return next;
  }

  function updateSettings(patch) {
    if (patch.acceptingOrders !== undefined) putSetting('accepting', patch.acceptingOrders ? '1' : '0');
    if (patch.cooldownMinutes !== undefined) {
      const m = patch.cooldownMinutes;
      if (!Number.isInteger(m) || m < 0 || m > 1440) throw new OrderError(400, 'Охлаждение: от 0 до 1440 минут.');
      putSetting('cooldown', m);
    }
    const p = patch.payment;
    if (p) {
      const digits = (v, n, label) => {
        const d = String(v ?? '').replace(/\s/g, '');
        if (d && !new RegExp(`^\\d{${n}}$`).test(d)) throw new OrderError(400, `${label}: ${n} цифр.`);
        return d;
      };
      const phone = p.phone === undefined ? undefined : normalizePhone(p.phone);
      if (phone === null) throw new OrderError(400, 'Телефон для переводов в формате +7 900 000-00-00.');
      const values = {
        pay_phone: phone,
        pay_recipient: p.recipient === undefined ? undefined : text(p.recipient, 100),
        pay_bank: p.bank === undefined ? undefined : text(p.bank, 100),
        pay_account: p.account === undefined ? undefined : digits(p.account, 20, 'Расчётный счёт'),
        pay_bic: p.bic === undefined ? undefined : digits(p.bic, 9, 'БИК'),
        pay_corr: p.corr === undefined ? undefined : digits(p.corr, 20, 'Корреспондентский счёт'),
        pay_transfer: p.transfer === undefined ? undefined : (p.transfer ? '1' : '0'),
      };
      for (const [k, v] of Object.entries(values)) if (v !== undefined) putSetting(k, v);
    }
    return settings();
  }

  /* ---------- Excel template and import ---------- */
  const packLabel = (id) => {
    const p = id && db.prepare('SELECT name, unit FROM items WHERE id = ?').get(id);
    return p ? `${p.name} ${p.unit}`.trim() : '';
  };

  // The template the cabinet downloads: the whole menu, one row per position.
  function exportMenu() {
    const catName = (id) => menu.categories.find((c) => c.id === id)?.name ?? id;
    const rows = db.prepare('SELECT * FROM items ORDER BY sort').all().map((r) => [
      r.id, catName(r.category), r.name, r.description, r.unit, r.price, r.available ? 'да' : 'нет', packLabel(r.pack),
    ]);
    const packOptions = [...new Set([...packIds(), ...db.prepare("SELECT id FROM items WHERE category = 'other'").all().map((r) => r.id)])].map(packLabel);
    return { rows: [MENU_COLUMNS, ...rows], categories: menu.categories.map((c) => c.name), packOptions };
  }

  /**
   * Applies a table from Excel or CSV. Columns are found by their headers, so our template and a price list of
   * one's own both work. A row finds its position by code, else by name (and volume, when names repeat); a row
   * with a section and a name not in the menu becomes a new position. All or nothing: one bad row and nothing
   * changes. dryRun answers what would change without saving, for the preview in the cabinet.
   */
  function importMenu(table, { dryRun = false } = {}) {
    if (!Array.isArray(table) || table.length > 3000) throw new OrderError(400, 'Слишком большая таблица.');
    const norm = (v) => String(v ?? '').toLowerCase().replace(/ё/g, 'е').replace(/\s+/g, ' ').trim();
    const COLS = {
      id: /^(id|код|артикул)/, category: /раздел|категор/, name: /^(название|позиц|наименов|товар|блюд)/,
      description: /описан|состав/, unit: /объ[её]м|вес|выход|ед\.? ?изм/, price: /цен|стоимост|price/,
      available: /налич|стоп/, pack: /упаков/,
    };
    let headerRow = -1;
    let col = {};
    for (let r = 0; r < Math.min(table.length, 15) && headerRow < 0; r++) {
      const cells = (table[r] ?? []).map(norm);
      if (!cells.some((c) => COLS.price.test(c))) continue;
      headerRow = r;
      col = Object.fromEntries(Object.entries(COLS).map(([k, re]) => [k, cells.findIndex((c) => re.test(c))]));
    }
    if (headerRow < 0) throw new OrderError(400, 'В файле не нашли столбец «Цена». Скачайте шаблон в кабинете и заполните его.');
    if (col.id < 0 && col.name < 0) throw new OrderError(400, 'В файле нет столбца «ID» или «Название»: непонятно, к чему относятся строки.');
    const cell = (row, key) => (col[key] < 0 ? '' : String(row[col[key]] ?? '').trim());
    const has = (key) => col[key] >= 0;

    const items = db.prepare('SELECT * FROM items').all();
    const byId = new Map(items.map((i) => [i.id, i]));
    const byName = new Map();
    for (const i of items) byName.set(norm(i.name), [...(byName.get(norm(i.name)) ?? []), i]);
    // Packaging by its label from the template ("Стакан бумажный 400 мл") or by a name that is unique ("Коробка").
    const packBy = new Map(items.map((r) => [norm(`${r.name} ${r.unit}`), r.id]));
    for (const [n, list] of byName) if (list.length === 1) packBy.set(n, list[0].id);
    const category = (v) => menu.categories.find((c) => norm(c.name) === norm(v) || c.id === v)?.id;

    const errors = [];
    const changes = new Map();
    for (let r = headerRow + 1; r < table.length; r++) {
      const row = table[r] ?? [];
      if (row.every((v) => String(v ?? '').trim() === '')) continue;
      const line = r + 1;
      const fail = (message) => errors.push({ row: line, message });
      const id = cell(row, 'id');
      const name = cell(row, 'name');
      let existing = id ? byId.get(id) : undefined;
      if (id && !existing) { fail(`нет позиции с ID ${id.slice(0, 40)}. Для новой позиции оставьте ID пустым.`); continue; }
      if (!existing && name) {
        let found = byName.get(norm(name)) ?? [];
        if (found.length > 1 && has('unit')) found = found.filter((i) => norm(i.unit) === norm(cell(row, 'unit')));
        if (found.length > 1) { fail(`«${name}» в меню несколько раз — укажите ID или объём.`); continue; }
        existing = found[0];
      }
      const catText = cell(row, 'category');
      const cat = catText ? category(catText) : existing?.category;
      if (catText && !cat) { fail(`нет раздела «${catText}». Разделы перечислены в кабинете.`); continue; }
      if (!existing && !cat) { fail(name ? `«${name}» нет в меню. Для новой позиции укажите раздел.` : 'пустое название.'); continue; }
      const finalName = name || existing.name;
      if (finalName.length > 80) { fail('название длиннее 80 символов.'); continue; }
      const priceText = cell(row, 'price').replace(/руб\.?|р\.|₽|\s/gi, '').replace(',', '.');
      let price = existing?.price;
      if (priceText) {
        price = Number(priceText);
        if (!Number.isInteger(price) || price < 1 || price > 100_000) { fail(`цена «${cell(row, 'price')}» — нужно целое число рублей от 1 до 100 000.`); continue; }
      } else if (!existing) { fail(`«${finalName}»: укажите цену.`); continue; }
      const availableText = norm(cell(row, 'available'));
      if (availableText && !['да', 'нет', '1', '0', 'yes', 'no'].includes(availableText)) { fail('«В наличии»: да или нет.'); continue; }
      const packText = cell(row, 'pack');
      const pack = !has('pack') ? existing?.pack ?? '' : !packText || norm(packText) === 'нет' ? '' : packBy.get(norm(packText)) ?? byId.get(packText)?.id;
      if (pack === undefined) { fail(`нет упаковки «${packText}». Варианты перечислены в кабинете.`); continue; }
      const next = {
        id: existing?.id ?? `m${randomBytes(5).toString('hex')}`,
        isNew: !existing, category: cat ?? existing.category, name: finalName,
        description: has('description') ? cell(row, 'description').slice(0, 300) : existing?.description ?? '',
        unit: has('unit') ? cell(row, 'unit').slice(0, 40) : existing?.unit ?? '',
        price,
        available: availableText ? (['да', '1', 'yes'].includes(availableText) ? 1 : 0) : existing?.available ?? 1,
        pack,
      };
      const fields = existing
        ? ['category', 'name', 'description', 'unit', 'price', 'available', 'pack'].filter((k) => String(next[k] ?? '') !== String(existing[k] ?? ''))
        : ['new'];
      if (fields.length) changes.set(next.id, { ...next, fields, before: existing ?? null });
    }
    if (errors.length) throw new OrderError(400, 'В файле есть ошибки — ничего не изменено. Исправьте строки и загрузите снова.', { errors: errors.slice(0, 50) });
    const list = [...changes.values()];
    const summary = {
      created: list.filter((c) => c.isNew).length,
      updated: list.filter((c) => !c.isNew).length,
      changes: list.slice(0, 300).map((c) => ({
        name: c.name, unit: c.unit, isNew: c.isNew, fields: c.fields,
        price: c.price, oldPrice: c.before?.price ?? null,
      })),
    };
    if (dryRun || !list.length) return summary;
    transaction(() => {
      let sort = db.prepare('SELECT COALESCE(MAX(sort), 0) AS s FROM items').get().s;
      for (const c of list) {
        if (c.isNew) {
          db.prepare(`INSERT INTO items (id, category, name, description, unit, price, available, sort, pack)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(c.id, c.category, c.name, c.description, c.unit, c.price, c.available, ++sort, c.pack);
        } else {
          db.prepare(`UPDATE items SET category = ?, name = ?, description = ?, unit = ?, price = ?, available = ?, pack = ?
            WHERE id = ?`).run(c.category, c.name, c.description, c.unit, c.price, c.available, c.pack, c.id);
        }
      }
    });
    return summary;
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
    db, settings, schedule, publicMenu, createOrder, publicOrder, claimPaid, setPayment, listOrders, setStatus,
    updateItem, updateSettings, updateSite, report, exportMenu, importMenu,
    ensureAdmin, login, checkSession, logout, changePassword,
  };
}
