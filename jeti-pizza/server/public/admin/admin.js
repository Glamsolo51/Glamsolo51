import { api as request, h, rub, time, store, phoneText } from '/common.js';

const $ = (id) => document.getElementById(id);
const POLL_MS = 5000;
const NEXT = { new: ['cooking', 'В работу'], cooking: ['ready', 'Готов'], ready: ['done', 'Выдан'] };
const COLUMNS = [['new', 'Новые'], ['cooking', 'Готовятся'], ['ready', 'Готовы к выдаче']];
const STATUS_ADMIN = { new: 'Новый', cooking: 'Готовится', ready: 'Готов', done: 'Выдан', cancelled: 'Отменён' };

let token = store.get('jeti.admin', null);
let menuCache = null;
let ordersMode = 'active';
let knownIds = null; // ids already seen, so only truly new orders ring
let fresh = new Set();
let pollTimer = 0;

// Moscow has no daylight saving, so day arithmetic in milliseconds is safe.
const isoDay = (ms) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).format(ms);
const today = () => isoDay(Date.now());
const daysAgo = (n) => isoDay(Date.now() - n * 86_400_000);

async function api(path, opts = {}) {
  try {
    return await request(path, { ...opts, token });
  } catch (e) {
    if (e.status === 401) showLogin();
    throw e;
  }
}

function showLogin() {
  token = null;
  store.set('jeti.admin', null);
  clearTimeout(pollTimer);
  $('app').hidden = true;
  $('login-view').hidden = false;
  $('login-form').elements.login.focus();
}

function showApp() {
  $('login-view').hidden = true;
  $('app').hidden = false;
  openTab();
  poll();
  loadSettings();
}

$('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target.elements;
  $('login-error').hidden = true;
  try {
    ({ token } = await request('/api/admin/login', { method: 'POST', body: { login: f.login.value, password: f.password.value } }));
    store.set('jeti.admin', token);
    f.password.value = '';
    showApp();
  } catch (err) {
    $('login-error').textContent = err.message;
    $('login-error').hidden = false;
  }
});

// Tabs live in the URL hash so a reload keeps the section.
function openTab() {
  const tab = ['orders', 'menu', 'reports', 'settings'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'orders';
  for (const s of document.querySelectorAll('.tab-view')) s.hidden = s.id !== tab;
  for (const a of document.querySelectorAll('.tabs a')) {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
  if (tab === 'menu') loadMenu();
  if (tab === 'reports') loadReport();
  if (tab === 'settings') loadSettings();
}
addEventListener('hashchange', () => token && openTab());

/* ---------- New-order alerts ---------- */
let audio = null;
$('sound').addEventListener('click', async () => {
  const on = $('sound').getAttribute('aria-pressed') !== 'true';
  if (on) {
    audio ??= new AudioContext(); // needs this click: browsers block sound until the user interacts
    await audio.resume();
    if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission();
    beep();
  }
  $('sound').setAttribute('aria-pressed', String(on));
  $('sound').textContent = on ? '🔔 Звук вкл.' : '🔕 Звук выкл.';
  store.set('jeti.admin.sound', on);
});

function beep() {
  if (!audio || $('sound').getAttribute('aria-pressed') !== 'true') return;
  [0, 0.25, 0.5].forEach((at, i) => {
    const osc = audio.createOscillator();
    const gain = audio.createGain();
    osc.frequency.value = [880, 1175, 1568][i];
    gain.gain.setValueAtTime(0.25, audio.currentTime + at);
    gain.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + at + 0.22);
    osc.connect(gain).connect(audio.destination);
    osc.start(audio.currentTime + at);
    osc.stop(audio.currentTime + at + 0.23);
  });
}

function announce(orders) {
  beep();
  document.title = `● Новый заказ · Кабинет`;
  if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
    for (const o of orders) new Notification(`Новый заказ № ${o.id}`, { body: `${o.name}, ${rub(o.total)}, к ${time(o.pickupAt)}`, tag: `order-${o.id}` });
  }
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) document.title = 'Кабинет · Йети Пицца'; });

/* ---------- Orders ---------- */
async function poll() {
  clearTimeout(pollTimer);
  if (!token) return;
  try {
    const { orders } = await api('/api/admin/orders');
    const newOnes = orders.filter((o) => o.status === 'new' && knownIds && !knownIds.has(o.id));
    knownIds = new Set([...(knownIds ?? []), ...orders.map((o) => o.id)]);
    newOnes.forEach((o) => fresh.add(o.id));
    if (newOnes.length) announce(newOnes);
    const count = orders.filter((o) => o.status === 'new').length;
    $('new-badge').hidden = !count;
    $('new-badge').textContent = count;
    if (ordersMode === 'active') renderBoard(orders);
    $('orders-updated').textContent = `обновлено в ${time(Date.now())}`;
  } catch (e) {
    if (e.status !== 401) $('orders-updated').textContent = 'нет связи с сервером, пробуем ещё…';
  }
  if (token) pollTimer = setTimeout(poll, POLL_MS);
}

function orderCard(o, { history = false } = {}) {
  const now = Date.now();
  const late = ['new', 'cooking'].includes(o.status) && o.pickupAt < now;
  const minutesAgo = Math.max(0, Math.round((now - o.createdAt) / 60000));
  const next = NEXT[o.status];
  return h('article', { class: `order${fresh.has(o.id) ? ' fresh' : ''}${late ? ' late' : ''}`, 'aria-label': `Заказ ${o.id}` },
    h('div', { class: 'order-head' },
      h('b', {}, `№ ${o.id}`),
      h('span', { class: 'src' }, o.source === 'app' ? 'приложение' : 'сайт'),
      history && h('span', { class: `status-pill ${o.status}` }, STATUS_ADMIN[o.status])),
    h('div', { class: 'when' },
      o.asap ? `Как можно скорее · к ${time(o.pickupAt)}` : `К ${time(o.pickupAt)}`,
      h('small', {}, late ? 'Время выдачи уже прошло' : `Создан в ${time(o.createdAt)}${minutesAgo < 120 ? ` · ${minutesAgo} мин назад` : ''}`)),
    h('div', { class: 'who' },
      h('span', {}, o.name),
      h('a', { href: `tel:${o.phone}` }, phoneText(o.phone)),
      h('span', { class: 'muted' }, o.email)),
    h('ul', {}, o.items.map((i) => h('li', {}, h('span', {}, h('b', {}, `${i.qty} × `), i.name, i.unit ? ` · ${i.unit}` : ''), h('span', { class: 'muted' }, rub(i.price * i.qty))))),
    o.comment && h('p', { class: 'comment' }, `💬 ${o.comment}`),
    h('div', { class: 'order-foot' },
      h('span', { class: 'sum' }, rub(o.total)),
      history
        ? h('select', { 'aria-label': `Статус заказа ${o.id}`, onchange: (e) => changeStatus(o, e.target.value) },
          Object.entries(STATUS_ADMIN).map(([v, label]) => h('option', { value: v, selected: v === o.status }, label)))
        : [
          h('button', { class: 'btn small danger', type: 'button', onclick: () => changeStatus(o, 'cancelled') }, 'Отменить'),
          next && h('button', { class: 'btn small primary', type: 'button', onclick: () => changeStatus(o, next[0]) }, next[1]),
        ]));
}

async function changeStatus(o, status) {
  if (status === 'cancelled' && !confirm(`Отменить заказ № ${o.id} (${o.name})? Покупатель увидит «Заказ отменён».`)) {
    if (ordersMode === 'history') loadHistory();
    return;
  }
  try {
    await api(`/api/admin/orders/${o.id}/status`, { method: 'POST', body: { status } });
    fresh.delete(o.id);
    ordersMode === 'history' ? loadHistory() : poll();
  } catch (e) {
    alert(e.message);
  }
}

function renderBoard(orders) {
  $('board').className = 'board';
  $('board').replaceChildren(...COLUMNS.map(([status, title]) => {
    const list = orders.filter((o) => o.status === status);
    return h('section', { class: 'column', 'aria-label': title },
      h('h2', {}, title, h('span', {}, String(list.length))),
      list.length ? list.map((o) => orderCard(o)) : h('p', { class: 'empty muted' }, status === 'new' ? 'Новых заказов нет. Мы позвоним звуком, когда придёт.' : 'Пусто'));
  }));
}

async function loadHistory() {
  const date = $('history-date').value || today();
  try {
    const { orders } = await api(`/api/admin/orders?date=${date}`);
    $('board').className = 'board list';
    $('board').replaceChildren(...(orders.length ? orders.map((o) => orderCard(o, { history: true })) : [h('p', { class: 'muted' }, 'В этот день заказов не было.')]));
  } catch { /* the poll shows connection problems */ }
}

for (const b of document.querySelectorAll('[data-mode]')) {
  b.addEventListener('click', () => {
    ordersMode = b.dataset.mode;
    for (const x of document.querySelectorAll('[data-mode]')) x.setAttribute('aria-pressed', String(x === b));
    $('history-date').hidden = ordersMode !== 'history';
    if (ordersMode === 'history') {
      $('history-date').value ||= today();
      loadHistory();
    } else poll();
  });
}
$('history-date').addEventListener('change', loadHistory);

/* ---------- Menu and stop-list ---------- */
async function loadMenu() {
  try {
    menuCache = await api('/api/admin/menu');
    renderMenu();
  } catch { /* handled by api() */ }
}

function renderMenu() {
  const q = $('menu-filter').value.trim().toLowerCase();
  const items = menuCache.items.filter((i) => !q || i.name.toLowerCase().includes(q));
  $('stop-count').textContent = `В стоп-листе: ${menuCache.items.filter((i) => !i.available).length}`;
  $('menu-admin').replaceChildren(...menuCache.categories.map((c) => {
    const list = items.filter((i) => i.category === c.id);
    if (!list.length) return null;
    return h('section', { class: 'menu-cat' }, h('h2', {}, c.name), list.map((it) =>
      h('div', { class: `menu-row${it.available ? '' : ' off'}` },
        h('div', { class: 'name' }, h('b', {}, it.name), h('small', {}, [it.unit, it.description].filter(Boolean).join(' · '))),
        h('label', { class: 'price-input' },
          h('input', { type: 'number', min: 1, max: 100000, step: 1, value: it.price, 'aria-label': `Цена: ${it.name}`, onchange: (e) => patchItem(it, { price: Number(e.target.value) }, e.target) }),
          '₽'),
        h('label', { class: 'switch' },
          h('input', { type: 'checkbox', role: 'switch', checked: it.available, onchange: (e) => patchItem(it, { available: e.target.checked }, e.target) }),
          h('span', {}, it.available ? 'В наличии' : 'Нет')))));
  }).filter(Boolean));
}

async function patchItem(item, patch, input) {
  try {
    Object.assign(item, await api(`/api/admin/items/${item.id}`, { method: 'PATCH', body: patch }));
  } catch (e) {
    alert(e.message);
    if ('price' in patch) input.value = item.price;
  }
  renderMenu();
}
$('menu-filter').addEventListener('input', () => menuCache && renderMenu());

/* ---------- Reports ---------- */
let report = null;
let sortBy = 'revenue';
const PRESETS = {
  today: () => [today(), today()],
  yesterday: () => [daysAgo(1), daysAgo(1)],
  week: () => [daysAgo(6), today()],
  month: () => [daysAgo(29), today()],
};

for (const b of document.querySelectorAll('[data-preset]')) {
  b.addEventListener('click', () => {
    [$('from').value, $('to').value] = PRESETS[b.dataset.preset]();
    loadReport(b.dataset.preset);
  });
}
for (const id of ['from', 'to']) $(id).addEventListener('change', () => loadReport(null));
for (const b of document.querySelectorAll('[data-sort]')) {
  b.addEventListener('click', () => {
    sortBy = b.dataset.sort;
    renderReport();
  });
}

async function loadReport(preset = 'today') {
  if (!$('from').value || preset) [$('from').value, $('to').value] = PRESETS[preset ?? 'today']();
  for (const b of document.querySelectorAll('[data-preset]')) b.setAttribute('aria-pressed', String(b.dataset.preset === preset));
  if ($('from').value > $('to').value) [$('from').value, $('to').value] = [$('to').value, $('from').value];
  try {
    report = await api(`/api/admin/report?from=${$('from').value}&to=${$('to').value}`);
    if (!menuCache) menuCache = await api('/api/admin/menu');
    renderReport();
  } catch { /* handled by api() */ }
}

function renderReport() {
  const catName = (id) => menuCache?.categories.find((c) => c.id === id)?.name ?? '—';
  $('tiles').replaceChildren(
    ...[['Выручка', rub(report.revenue)], ['Заказов', report.orders], ['Средний чек', rub(report.averageCheck)], ['Отменено', report.cancelled]]
      .map(([label, value]) => h('div', { class: 'tile' }, h('span', {}, label), h('b', {}, String(value)))));
  for (const b of document.querySelectorAll('[data-sort]')) {
    b.textContent = `${b.dataset.sort === 'qty' ? 'Продано, шт.' : 'Выручка'}${sortBy === b.dataset.sort ? ' ↓' : ''}`;
    b.closest('th').setAttribute('aria-sort', sortBy === b.dataset.sort ? 'descending' : 'none');
  }
  const rows = [...report.items].sort((a, b) => b[sortBy] - a[sortBy]);
  const top = Math.max(...rows.map((r) => r.share), 0.0001);
  $('report-rows').replaceChildren(...(rows.length
    ? [
      ...rows.map((r) => h('tr', {},
        h('td', {}, r.name, r.unit && h('small', {}, ` · ${r.unit}`)),
        h('td', {}, catName(r.category)),
        h('td', { class: 'num' }, String(r.qty)),
        h('td', { class: 'num' }, rub(r.revenue)),
        h('td', {}, h('div', { class: 'bar' }, h('i', { 'aria-hidden': 'true', 'data-w': r.share / top }), h('span', {}, `${(r.share * 100).toFixed(1)}%`))))),
      h('tr', { class: 'total' }, h('td', {}, 'Итого'), h('td', {}), h('td', { class: 'num' }, String(rows.reduce((s, r) => s + r.qty, 0))), h('td', { class: 'num' }, rub(report.revenue)), h('td', {})),
    ]
    : [h('tr', {}, h('td', { colspan: 5, class: 'muted' }, 'За этот период продаж нет.'))]));
  // Width through CSSOM: the page's CSP forbids inline style attributes.
  for (const bar of $('report-rows').querySelectorAll('i[data-w]')) bar.style.width = `${Math.max(2, bar.dataset.w * 120)}px`;
}

$('csv').addEventListener('click', () => {
  if (!report) return;
  const esc = (v) => `"${String(v).replaceAll('"', '""')}"`;
  const lines = [['Позиция', 'Объём', 'Категория', 'Продано, шт.', 'Выручка, ₽', 'Доля, %'].map(esc).join(';'),
    ...report.items.map((r) => [r.name, r.unit, menuCache?.categories.find((c) => c.id === r.category)?.name ?? '', r.qty, r.revenue, (r.share * 100).toFixed(1).replace('.', ',')].map(esc).join(';'))];
  // BOM and semicolons: Excel in Russian locale opens it correctly with a double click.
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const a = h('a', { href: URL.createObjectURL(blob), download: `yeti-report_${report.from}_${report.to}.csv` });
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
});

/* ---------- Settings ---------- */
async function loadSettings() {
  try {
    const s = await api('/api/admin/settings');
    const f = $('settings-form').elements;
    f.acceptingOrders.checked = s.acceptingOrders;
    f.cooldownMinutes.value = s.cooldownMinutes;
    $('paused-banner').hidden = s.acceptingOrders;
  } catch { /* handled by api() */ }
}

$('settings-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target.elements;
  try {
    const s = await api('/api/admin/settings', { method: 'PUT', body: { acceptingOrders: f.acceptingOrders.checked, cooldownMinutes: Number(f.cooldownMinutes.value) } });
    $('paused-banner').hidden = s.acceptingOrders;
    $('settings-ok').hidden = false;
    setTimeout(() => { $('settings-ok').hidden = true; }, 2500);
  } catch (err) {
    alert(err.message);
  }
});

$('password-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target.elements;
  $('password-error').hidden = true;
  try {
    await api('/api/admin/password', { method: 'POST', body: { current: f.current.value, next: f.next.value } });
    alert('Пароль изменён. Войдите с новым паролем.');
    showLogin();
  } catch (err) {
    $('password-error').textContent = err.message;
    $('password-error').hidden = false;
  }
});

$('logout').addEventListener('click', async () => {
  try {
    await api('/api/admin/logout', { method: 'POST' });
  } catch { /* signing out locally is enough */ }
  showLogin();
});

if (store.get('jeti.admin.sound', false)) {
  $('sound').textContent = '🔔 Нажмите, чтобы включить звук';
}
token ? showApp() : showLogin();
