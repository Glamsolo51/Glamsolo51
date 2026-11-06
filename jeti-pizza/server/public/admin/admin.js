import { api as request, h, rub, time, store, phoneText, plural } from '/common.js';
import { readTable, writeXlsx } from '/admin/xlsx.js';

const $ = (id) => document.getElementById(id);
const POLL_MS = 5000;
const NEXT = { new: ['cooking', 'В работу'], cooking: ['ready', 'Готов'], ready: ['done', 'Выдан'] };
const COLUMNS = [['new', 'Новые'], ['cooking', 'Готовятся'], ['ready', 'Готовы к выдаче']];
const STATUS_ADMIN = { new: 'Новый', cooking: 'Готовится', ready: 'Готов', done: 'Выдан', cancelled: 'Отменён' };
const PAYMENT_LABEL = { awaiting: 'Перевод: ещё не оплачен', claimed: 'Перевод: покупатель оплатил — проверьте банк', paid: 'Оплачено переводом ✓' };

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
  const tab = ['orders', 'menu', 'reports', 'site', 'settings'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'orders';
  for (const s of document.querySelectorAll('.tab-view')) s.hidden = s.id !== tab;
  for (const a of document.querySelectorAll('.tabs a')) {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
  if (tab === 'menu') loadMenu();
  if (tab === 'reports') loadReport();
  if (tab === 'settings') loadSettings();
  if (tab === 'site') loadSite();
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
    o.payment === 'transfer'
      ? h('p', { class: `pay-state ${o.paymentStatus}` }, PAYMENT_LABEL[o.paymentStatus],
        o.paymentStatus !== 'paid' && h('button', { class: 'btn small', type: 'button', onclick: () => confirmPayment(o) }, 'Оплата пришла'))
      : h('p', { class: 'pay-state cash' }, 'Оплата при получении'),
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

async function confirmPayment(o) {
  if (!confirm(`Перевод ${rub(o.total)} по заказу № ${o.id} пришёл на счёт?`)) return;
  try {
    await api(`/api/admin/orders/${o.id}/payment`, { method: 'POST', body: { status: 'paid' } });
    ordersMode === 'history' ? loadHistory() : poll();
  } catch (e) {
    alert(e.message);
  }
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

/* ---------- Menu, prices and stop-list: one section at a time ---------- */
let menuCat = store.get('jeti.admin.cat', null); // a category id, 'all' or 'stop'

async function loadMenu() {
  try {
    menuCache = await api('/api/admin/menu');
    renderMenu();
  } catch { /* handled by api() */ }
}

const menuCategories = () => menuCache.categories.filter((c) => menuCache.items.some((i) => i.category === c.id));

function renderMenu() {
  if (!menuCache) return;
  const cats = menuCategories();
  if (!['all', 'stop'].includes(menuCat) && !cats.some((c) => c.id === menuCat)) menuCat = cats[0]?.id ?? 'all';
  renderMenuCats();
  renderMenuList();
}

function renderMenuCats() {
  const { items } = menuCache;
  const searching = !!$('menu-filter').value.trim();
  const stopped = items.filter((i) => !i.available).length;
  const chip = (id, label, count, extra = '') => h('button', {
    type: 'button', class: `menu-cat${extra}`, 'aria-pressed': String(!searching && menuCat === id),
    onclick: () => {
      menuCat = id;
      store.set('jeti.admin.cat', id);
      $('menu-filter').value = '';
      renderMenu();
    },
  }, h('span', {}, label), h('b', {}, String(count)));
  $('menu-cats').replaceChildren(
    chip('all', 'Все позиции', items.length),
    chip('stop', 'Стоп-лист', stopped, stopped ? ' warn' : ''),
    h('hr'),
    ...menuCategories().map((c) => chip(c.id, c.name, items.filter((i) => i.category === c.id).length)));
}

function renderMenuList() {
  const q = $('menu-filter').value.trim().toLowerCase();
  const { items } = menuCache;
  const shown = q ? items.filter((i) => i.name.toLowerCase().includes(q))
    : menuCat === 'all' ? items
      : menuCat === 'stop' ? items.filter((i) => !i.available)
        : items.filter((i) => i.category === menuCat);
  // A single section needs no subheadings; search, "all" and the stop-list are grouped by section.
  const groups = q || menuCat === 'all' || menuCat === 'stop'
    ? menuCategories().map((c) => [c, shown.filter((i) => i.category === c.id)]).filter(([, list]) => list.length)
    : [[null, shown]];
  $('menu-admin').replaceChildren(
    h('div', { class: 'menu-row head', 'aria-hidden': 'true' }, h('span', {}, 'Фото'), h('span', {}, 'Позиция'), h('span', {}, 'Цена, ₽'), h('span', {}, 'Наличие')),
    ...(shown.length
      ? groups.flatMap(([c, list]) => [...(c ? [h('h2', { class: 'menu-group' }, c.name)] : []), ...list.map(menuRow)])
      : [h('p', { class: 'muted empty' }, q ? 'Ничего не нашли.' : 'В стоп-листе пусто — всё в наличии.')]));
}

function menuRow(it) {
  return h('div', { class: `menu-row${it.available ? '' : ' off'}` },
    h('div', { class: 'thumb' },
      it.photo ? h('img', { src: it.photo, alt: '', width: 40, height: 40 }) : h('span', { 'aria-hidden': 'true' }, '📷'),
      h('label', { class: 'thumb-btn', title: it.photo ? 'Заменить фото' : 'Добавить фото' },
        h('span', { class: 'sr' }, `Фото: ${it.name}`),
        h('input', { type: 'file', accept: 'image/*', onchange: (e) => uploadItemPhoto(it, e.target) })),
      it.photo && h('button', { class: 'thumb-del', type: 'button', title: 'Убрать фото', 'aria-label': `Убрать фото: ${it.name}`, onclick: () => setItemPhoto(it, '') }, '✕')),
    h('div', { class: 'name' },
      h('b', {}, it.name),
      (it.unit || it.description) && h('small', { title: it.description }, [it.unit, it.description].filter(Boolean).join(' · '))),
    h('input', {
      type: 'number', class: 'price', min: 1, max: 100000, step: 1, value: it.price, 'aria-label': `Цена: ${it.name}`,
      onchange: (e) => patchItem(it, { price: Number(e.target.value) }, e.target),
      onkeydown: (e) => { if (e.key === 'Enter') e.target.blur(); },
    }),
    h('label', { class: 'switch' },
      h('input', { type: 'checkbox', role: 'switch', checked: it.available, 'aria-label': `В наличии: ${it.name}`, onchange: (e) => patchItem(it, { available: e.target.checked }, e.target) }),
      h('span', {}, it.available ? 'В наличии' : 'Нет')));
}

// Updates the row in place, so Tab still moves on to the next price.
async function patchItem(item, patch, input) {
  const row = input.closest('.menu-row');
  try {
    Object.assign(item, await api(`/api/admin/items/${item.id}`, { method: 'PATCH', body: patch }));
    toast('price' in patch ? `Сохранено: ${item.name} — ${rub(item.price)}` : `${item.name}: ${item.available ? 'в наличии' : 'в стоп-листе'}`);
  } catch (e) {
    if (e.status !== 401) alert(e.message);
    if ('price' in patch) input.value = item.price;
    else input.checked = item.available;
  }
  row.classList.toggle('off', !item.available);
  row.querySelector('.switch span').textContent = item.available ? 'В наличии' : 'Нет';
  renderMenuCats();
}
$('menu-filter').addEventListener('input', () => menuCache && renderMenu());

function download(blob, name) {
  const a = h('a', { href: URL.createObjectURL(blob), download: name });
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

let toastTimer = 0;
function toast(text) {
  $('toast').textContent = text;
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3000);
}

/* ---------- Menu from Excel: the template, a preview, then all or nothing ---------- */
$('price-export').addEventListener('click', async () => {
  try {
    const { rows } = await api('/api/admin/menu-template');
    const book = writeXlsx(rows, { sheet: 'Меню', widths: [14, 18, 26, 50, 12, 10, 11, 24] });
    download(new Blob([book], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `yeti-menu_${today()}.xlsx`);
  } catch (e) {
    if (e.status !== 401) alert(e.message);
  }
});

$('price-import').addEventListener('click', () => $('price-file').click());
let pendingRows = null;
$('price-file').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  e.target.value = ''; // the same file can be chosen again after editing it
  if (!file) return;
  pendingRows = null;
  try {
    if (file.size > 5 * 1024 * 1024) throw new Error('Файл больше 5 МБ. Скачайте шаблон из кабинета и заполните его.');
    const rows = await readTable(await file.arrayBuffer());
    showImport(file.name, await api('/api/admin/menu-import', { method: 'POST', body: { rows, dryRun: true } }));
    pendingRows = rows;
  } catch (err) {
    if (err.status !== 401) showImport(file.name, null, err);
  }
});

const FIELD = { category: 'раздел', name: 'название', description: 'описание', unit: 'объём', price: 'цена', available: 'наличие', pack: 'упаковка' };
function showImport(fileName, result, error) {
  $('import-title').textContent = `Меню из «${fileName}»`;
  const count = (result?.created ?? 0) + (result?.updated ?? 0);
  $('import-body').replaceChildren(...(error
    ? [h('p', { class: 'form-error' }, error.message),
      error.data?.errors?.length > 0 && h('ul', { class: 'problems' }, error.data.errors.map((x) => h('li', {}, `Строка ${x.row}: ${x.message}`)))]
    : [
      h('p', {}, count ? `Изменится ${result.updated}, добавится ${result.created}.` : 'Изменений нет: файл совпадает с меню.'),
      count > 0 && h('div', { class: 'table-wrap' }, h('table', { class: 'report changes' },
        h('thead', {}, h('tr', {}, h('th', {}, 'Позиция'), h('th', {}, 'Что меняется'), h('th', { class: 'num' }, 'Цена'))),
        h('tbody', {}, result.changes.map((c) => h('tr', {},
          h('td', {}, c.name, c.unit && h('small', {}, ` · ${c.unit}`)),
          h('td', { class: 'muted' }, c.isNew ? 'новая позиция' : c.fields.map((f) => FIELD[f] ?? f).join(', ')),
          h('td', { class: `num ${c.oldPrice != null && c.price !== c.oldPrice ? (c.price > c.oldPrice ? 'up' : 'down') : ''}` },
            c.oldPrice != null && c.price !== c.oldPrice ? `${rub(c.oldPrice)} → ${rub(c.price)}` : rub(c.price))))))),
    ]).filter(Boolean));
  $('import-apply').hidden = !count;
  $('import-apply').disabled = false;
  $('import-apply').textContent = `Сохранить ${count} ${plural(count, ['изменение', 'изменения', 'изменений'])}`;
  $('import-cancel').textContent = count ? 'Отмена' : 'Закрыть';
  if (!$('import-dialog').open) $('import-dialog').showModal();
}

$('import-apply').addEventListener('click', async () => {
  $('import-apply').disabled = true;
  try {
    const r = await api('/api/admin/menu-import', { method: 'POST', body: { rows: pendingRows, dryRun: false } });
    $('import-dialog').close();
    toast(`Готово: обновлено ${r.updated}, добавлено ${r.created}. Сайт и приложение уже показывают новое меню.`);
    loadMenu();
  } catch (e) {
    $('import-apply').disabled = false;
    if (e.status !== 401) $('import-body').prepend(h('p', { class: 'form-error' }, e.message));
  }
});
for (const id of ['import-close', 'import-cancel']) $(id).addEventListener('click', () => $('import-dialog').close());

/* ---------- Photos ---------- */
// Shrinks a photo in the browser before upload: a phone picture of 5–10 MB becomes ~200 KB and loses its EXIF (location).
async function uploadPhoto(file, maxSide = 1600) {
  const img = await createImageBitmap(file);
  const k = Math.min(1, maxSide / Math.max(img.width, img.height));
  const canvas = h('canvas', { width: Math.round(img.width * k), height: Math.round(img.height * k) });
  canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
  let blob = await new Promise((done) => canvas.toBlob(done, 'image/webp', 0.85));
  if (blob?.type !== 'image/webp') blob = await new Promise((done) => canvas.toBlob(done, 'image/jpeg', 0.88));
  const res = await fetch('/api/admin/uploads', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': blob.type }, body: blob });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) showLogin();
  if (!res.ok) throw new Error(data.error ?? 'Не удалось загрузить фото.');
  return data.url;
}

async function setItemPhoto(item, photo) {
  try {
    Object.assign(item, await api(`/api/admin/items/${item.id}`, { method: 'PATCH', body: { photo } }));
    renderMenuList();
    toast(photo ? `Фото сохранено: ${item.name}` : `Фото убрано: ${item.name}`);
  } catch (e) {
    if (e.status !== 401) alert(e.message);
  }
}

async function uploadItemPhoto(item, input) {
  const file = input.files?.[0];
  if (!file) return;
  try {
    await setItemPhoto(item, await uploadPhoto(file, 1200));
  } catch (e) {
    alert(e.message);
  }
}

/* ---------- Site texts and photos ---------- */
let siteDraft = null;
async function loadSite() {
  try {
    siteDraft = (await api('/api/admin/menu')).site;
    const f = $('site-form').elements;
    for (const k of ['tagline', 'lead', 'about']) f[k].value = siteDraft[k];
    renderSitePhotos();
  } catch { /* handled by api() */ }
}

function renderSitePhotos() {
  $('hero-preview').src = siteDraft.hero;
  $('gallery-edit').replaceChildren(
    ...siteDraft.gallery.map((src, i) => h('figure', {},
      h('img', { src, alt: `Фото ${i + 1}`, width: 120, height: 160 }),
      h('button', { class: 'btn small danger', type: 'button', onclick: () => { siteDraft.gallery.splice(i, 1); renderSitePhotos(); } }, 'Убрать'))),
    siteDraft.gallery.length < 6 && h('label', { class: 'btn small file-btn add-photo' }, '+ Добавить фото',
      h('input', { type: 'file', accept: 'image/*', onchange: async (e) => {
        try {
          siteDraft.gallery.push(await uploadPhoto(e.target.files[0]));
          renderSitePhotos();
        } catch (err) { alert(err.message); }
      } })));
}

$('hero-file').addEventListener('change', async (e) => {
  try {
    siteDraft.hero = await uploadPhoto(e.target.files[0]);
    renderSitePhotos();
  } catch (err) {
    alert(err.message);
  }
});

$('site-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target.elements;
  try {
    siteDraft = await api('/api/admin/site', { method: 'PUT', body: { ...siteDraft, tagline: f.tagline.value, lead: f.lead.value, about: f.about.value } });
    $('site-ok').hidden = false;
    setTimeout(() => { $('site-ok').hidden = true; }, 3000);
  } catch (err) {
    alert(err.message);
  }
});

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
  download(new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }), `yeti-report_${report.from}_${report.to}.csv`);
});

/* ---------- Settings ---------- */
async function loadSettings() {
  try {
    const s = await api('/api/admin/settings');
    const f = $('settings-form').elements;
    f.acceptingOrders.checked = s.acceptingOrders;
    f.cooldownMinutes.value = s.cooldownMinutes;
    $('paused-banner').hidden = s.acceptingOrders;
    const pf = $('payment-form').elements;
    pf.transfer.checked = s.payment.transfer;
    for (const k of ['phone', 'recipient', 'bank', 'account', 'bic', 'corr']) pf[k].value = k === 'phone' ? phoneText(s.payment.phone) : s.payment[k];
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

$('payment-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target.elements;
  try {
    await api('/api/admin/settings', {
      method: 'PUT',
      body: { payment: { transfer: f.transfer.checked, ...Object.fromEntries(['phone', 'recipient', 'bank', 'account', 'bic', 'corr'].map((k) => [k, f[k].value])) } },
    });
    $('payment-ok').hidden = false;
    setTimeout(() => { $('payment-ok').hidden = true; }, 2500);
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
