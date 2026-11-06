import { api, h, rub, time, store, plural, sleep, STATUS } from './common.js';

const $ = (id) => document.getElementById(id);
const NS = 'http://www.w3.org/2000/svg';
const FINAL = new Set(['done', 'cancelled']);
// Line icons in the spirit of the icons over the pizzeria's door.
const ICONS = {
  pizza: 'M24 6 42 40Q24 47 6 40ZM13 31a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M25 33a3 3 0 1 0 6 0a3 3 0 1 0-6 0M21 21a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0',
  sweet: 'M24 10 40 40Q24 46 8 40ZM20 30a3 3 0 1 0 6 0a3 3 0 1 0-6 0M24 10q3-6 9-6',
  snacks: 'M12 20h24l-4 22H16ZM16 20V8M21 20V5M26 20V7M31 20V10M12 28h24',
  salads: 'M6 22h36a18 16 0 0 1-36 0ZM13 22q3-10 11-6 6-8 12 6M20 14q2-6 7-6',
  hot: 'M8 18h26v12a10 10 0 0 1-10 10h-6A10 10 0 0 1 8 30ZM34 21h3a5 5 0 0 1 0 10h-3M16 5q-3 4 0 8M22 4q-3 4 0 8M28 5q-3 4 0 8',
  shakes: 'M12 14h24l-3 28H15ZM26 14l6-10h5M13 23h22',
  drinks: 'M12 16h24M14 16l3 26h14l3-26M13 16v-4h22v4M26 12l2-8h6M18 28h12',
  beer: 'M10 13h22v28H10ZM32 18h4a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4h-4M16 19v16M22 19v16M10 13q1-6 8-4 4-4 9 0 5-1 5 4',
  icecream: 'M14 22l10 22 10-22M14 22a10 10 0 0 1 20 0ZM21 7a3 3 0 1 0 6 0a3 3 0 1 0-6 0M17 28l12 6',
  chips: 'M12 8h24l-2 6 2 26-2 4H14l-2-4 2-26ZM18 26a6 4 0 1 0 12 0a6 4 0 1 0-12 0',
  other: 'M6 16l18-8 18 8v20l-18 8-18-8ZM6 16l18 8 18-8M24 24v20',
};
function icon(name) {
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 48 48');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(NS, 'path');
  path.setAttribute('d', ICONS[name] ?? ICONS.other);
  svg.append(path);
  return svg;
}

let menu = null;
const cart = new Map(Object.entries(store.get('jeti.cart', {})));
const itemById = (id) => menu?.items.find((i) => i.id === id);
const saveCart = () => store.set('jeti.cart', Object.fromEntries(cart));
const cartCount = () => [...cart.values()].reduce((a, b) => a + b, 0);
const cartTotal = () => [...cart].reduce((sum, [id, qty]) => sum + (itemById(id)?.price ?? 0) * qty, 0);
const closedText = (info) => `Онлайн-заказы принимаем с ${info.open} до ${info.lastOrder ?? info.close}. Ждём вас!`;

function setQty(id, qty) {
  if (qty <= 0) cart.delete(id);
  else cart.set(id, Math.min(qty, 20));
  saveCart();
  renderCartSummary();
  renderMenu();
  if ($('cart').open) renderCart();
}

function stepper(id, qty, label) {
  return h('div', { class: 'stepper', role: 'group', 'aria-label': label },
    h('button', { type: 'button', 'aria-label': 'Убрать одну', onclick: () => setQty(id, qty - 1) }, '−'),
    h('output', { 'aria-live': 'polite' }, String(qty)),
    h('button', { type: 'button', 'aria-label': 'Добавить ещё', onclick: () => setQty(id, qty + 1), disabled: qty >= 20 }, '+'));
}

async function loadMenu() {
  menu = await api('/api/menu');
  // Forget cart lines that left the menu.
  for (const id of cart.keys()) if (!itemById(id)) cart.delete(id);
  saveCart();
  const { info } = menu;
  const note = !info.acceptingOrders ? 'Онлайн-заказы сейчас на паузе — позвоните нам, примем по телефону.'
    : !info.asapAllowed && info.slots.length === 0 ? closedText(info) : '';
  $('closed-note').textContent = note;
  $('closed-note').hidden = !note;
  renderMenu();
  renderCartSummary();
}

/* ---------- Menu: section cards, and one section at a time ---------- */

// "#menu/pizza" opens a section; the browser's Back button returns to the cards.
let openCat = null;
const catFromHash = () => location.hash.match(/^#menu\/([\w-]+)$/)?.[1] ?? null;
const shownCategories = () => menu.categories.filter((c) => menu.items.some((i) => i.category === c.id));

function renderMenu() {
  if (!menu) return;
  const cats = shownCategories();
  const cat = cats.find((c) => c.id === openCat);
  $('menu-list').replaceChildren(cat ? categoryView(cat, cats) : categoryCards(cats));
}

function categoryCards(cats) {
  return h('ul', { class: 'cat-grid', 'aria-label': 'Разделы меню' }, cats.map((c) => {
    const items = menu.items.filter((i) => i.category === c.id);
    const inCart = items.reduce((n, i) => n + (cart.get(i.id) ?? 0), 0);
    return h('li', {},
      h('a', { class: 'cat-card', href: `#menu/${c.id}` },
        icon(c.id),
        h('b', {}, c.name),
        h('span', {}, `${items.length} ${plural(items.length, ['позиция', 'позиции', 'позиций'])}`),
        inCart > 0 && h('i', { class: 'in-cart-count', 'aria-label': `В корзине: ${inCart}` }, String(inCart))));
  }));
}

function categoryView(c, cats) {
  const items = menu.items.filter((i) => i.category === c.id);
  const next = cats[(cats.indexOf(c) + 1) % cats.length];
  return h('section', { class: 'cat', 'aria-labelledby': 'cat-title' },
    h('div', { class: 'cat-head' },
      h('a', { class: 'btn ghost small back', href: '#menu' }, '← Все разделы'),
      h('h3', { id: 'cat-title', tabindex: '-1' }, icon(c.id), c.name)),
    h('ul', { class: 'items' }, items.map((it) => {
      const qty = cart.get(it.id) ?? 0;
      return h('li', { class: `item${it.available ? '' : ' sold-out'}${qty ? ' in-cart' : ''}` },
        h('div', { class: 'item-text' },
          h('h4', {}, it.name),
          it.description && h('p', { class: 'muted' }, it.description)),
        h('div', { class: 'item-buy' },
          h('span', { class: 'price' }, rub(it.price), it.unit && h('small', {}, ` · ${it.unit}`)),
          !it.available ? h('span', { class: 'tag' }, 'Закончилось')
            : qty ? stepper(it.id, qty, it.name)
              : h('button', { class: 'btn add', type: 'button', 'aria-label': `В корзину: ${it.name}`, onclick: () => setQty(it.id, 1) }, '+ В корзину')));
    })),
    h('div', { class: 'cat-foot' },
      h('a', { class: 'btn ghost', href: '#menu' }, '← Все разделы'),
      next !== c && h('a', { class: 'btn ghost', href: `#menu/${next.id}` }, `${next.name} →`)));
}

function onHash() {
  if (!/^#menu(\/|$)/.test(location.hash)) return; // "О нас", "Контакты": leave the menu as it is
  openCat = catFromHash();
  renderMenu();
  $('menu').scrollIntoView({ block: 'start' });
  if (openCat) document.getElementById('cat-title')?.focus({ preventScroll: true });
}

/* ---------- Cart and checkout ---------- */

function renderCartSummary() {
  const count = cartCount();
  $('cart-count').hidden = !count;
  $('cart-count').textContent = count;
  $('cart-total').textContent = count ? rub(cartTotal()) : 'Корзина';
  $('cart-fab').hidden = !count;
  $('cart-fab-count').textContent = count;
  $('cart-fab').setAttribute('aria-label', `Корзина: ${count} шт. на ${rub(cartTotal())}`);
}

function renderCart() {
  const lines = [...cart].map(([id, qty]) => ({ it: itemById(id), qty })).filter((l) => l.it);
  $('cart-lines').replaceChildren(...(lines.length
    ? lines.map(({ it, qty }) => h('div', { class: `cart-line${it.available ? '' : ' sold-out'}` },
      h('div', {}, h('b', {}, it.name), h('span', { class: 'muted' }, it.available ? `${rub(it.price)}${it.unit ? ` · ${it.unit}` : ''}` : 'Закончилось — уберите из заказа')),
      stepper(it.id, qty, it.name),
      h('b', { class: 'line-sum' }, rub(it.price * qty))))
    : [h('p', { class: 'empty' }, 'Корзина пуста. Добавьте что-нибудь вкусное из меню.')]));
  $('checkout').hidden = !lines.length;
  $('checkout-total').textContent = rub(cartTotal());

  const { info } = menu;
  const options = [];
  if (info.asapAllowed) options.push(h('option', { value: 'asap' }, `Как можно скорее (≈ ${info.prepMinutes} мин)`));
  options.push(...info.slots.map((s) => h('option', { value: s }, `Сегодня к ${s}`)));
  const previous = $('pickup').value;
  $('pickup').replaceChildren(...options);
  if (options.some((o) => o.value === previous)) $('pickup').value = previous;

  const blocked = !info.acceptingOrders || options.length === 0;
  $('submit').disabled = blocked || cooldownUntil > Date.now();
  if (blocked) showError(info.acceptingOrders ? closedText(info) : 'Онлайн-заказы сейчас на паузе. Позвоните: +7 927 667-21-21.');
  else if (cooldownUntil <= Date.now()) showError('');
}

function showError(message) {
  $('form-error').textContent = message;
  $('form-error').hidden = !message;
}

let cooldownUntil = 0;
function startCooldown(seconds) {
  cooldownUntil = Date.now() + seconds * 1000;
  $('submit').disabled = true;
  const tick = () => {
    const left = Math.ceil((cooldownUntil - Date.now()) / 1000);
    if (left <= 0) {
      showError('');
      $('submit').disabled = false;
      return;
    }
    showError(`С этого телефона или почты недавно уже был заказ. Новый можно оформить через ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}.`);
    setTimeout(tick, 1000);
  };
  tick();
}

async function openCart() {
  $('cart').showModal();
  renderCart();
  try {
    await loadMenu(); // fresh pickup slots and stop-list
    renderCart();
  } catch { /* keep what we have */ }
}

async function submit(e) {
  e.preventDefault();
  const form = e.target;
  const data = Object.fromEntries(new FormData(form));
  for (const field of ['name', 'phone', 'email']) {
    const input = form.elements[field];
    if (!input.value.trim() || !input.checkValidity()) {
      input.focus();
      return showError({ name: 'Укажите имя.', phone: 'Укажите телефон.', email: 'Укажите почту.' }[field]);
    }
  }
  $('submit').disabled = true;
  showError('');
  try {
    const order = await api('/api/orders', {
      method: 'POST',
      body: { ...data, source: 'web', items: [...cart].map(([id, qty]) => ({ id, qty })) },
    });
    store.set('jeti.contact', { name: data.name, phone: data.phone, email: data.email });
    store.set('jeti.orders', [{ id: order.id, token: order.token }, ...store.get('jeti.orders', [])].slice(0, 5));
    cart.clear();
    saveCart();
    renderCartSummary();
    renderMenu();
    form.elements.comment.value = '';
    $('cart').close();
    openStatus(order.id, order.token);
  } catch (err) {
    if (err.status === 429 && err.data?.retryAfter) return startCooldown(err.data.retryAfter);
    showError(err.message);
    if (err.data?.field) form.elements[err.data.field]?.focus();
    if (err.data?.itemId || err.status === 409) await loadMenu().then(renderCart).catch(() => {});
  } finally {
    if (cooldownUntil <= Date.now()) $('submit').disabled = false;
  }
}

/* ---------- The customer's order: its status follows the kitchen at once ---------- */

// The server holds "?since=" requests until the kitchen changes the order (or 25 s pass), so a change shows
// within a moment, while the page sends only about two requests a minute.
let watched = null; // { id, token, ctrl, order }

function watchOrder(id, token) {
  if (watched?.id === Number(id) && !watched.ctrl.signal.aborted) return;
  watched?.ctrl.abort();
  const w = { id: Number(id), token, ctrl: new AbortController(), order: null };
  watched = w;
  follow(w);
}

async function follow(w) {
  let since = null;
  let failures = 0;
  while (!w.ctrl.signal.aborted) {
    const started = Date.now();
    try {
      const o = await api(`/api/orders/${w.id}?token=${encodeURIComponent(w.token)}${since == null ? '' : `&since=${since}`}`, { signal: w.ctrl.signal });
      failures = 0;
      const changed = o.updatedAt !== since;
      since = o.updatedAt;
      w.order = o;
      showOrder(o);
      if (FINAL.has(o.status)) return;
      // A busy server answers at once without a change: then wait before asking again.
      if (!changed && Date.now() - started < 2000) await sleep(3000, w.ctrl.signal);
    } catch (err) {
      if (w.ctrl.signal.aborted) return;
      if (err.status === 404) {
        if ($('status').open) $('status-body').replaceChildren(h('p', { class: 'form-error' }, err.message));
        $('my-order').hidden = true;
        return;
      }
      await sleep(Math.min(30_000, 2000 * 2 ** failures++), w.ctrl.signal); // no connection: retry, slower each time
    }
  }
}

function showOrder(o) {
  $('my-order').hidden = false;
  $('my-order').classList.toggle('done', FINAL.has(o.status));
  $('my-order-text').textContent = `№ ${o.id} · ${STATUS[o.status]}`;
  if ($('status').open) renderStatus(o);
}

function openStatus(id, token) {
  history.replaceState(null, '', `?order=${id}&t=${encodeURIComponent(token)}${location.hash}`); // the address bar is the order link
  if (!$('status').open) $('status').showModal();
  if (watched?.id === Number(id) && watched.order) renderStatus(watched.order);
  else $('status-body').replaceChildren(h('p', { class: 'muted' }, 'Загружаем заказ…'));
  watchOrder(id, token);
}

function renderStatus(o) {
  $('status-title').textContent = `Заказ № ${o.id}`;
  const steps = ['new', 'cooking', 'ready', 'done'];
  const at = steps.indexOf(o.status);
  $('status-body').replaceChildren(
    o.status === 'cancelled'
      ? h('p', { class: 'form-error' }, 'Заказ отменён. Если это ошибка — позвоните нам: ', h('a', { href: 'tel:+79276672121' }, '+7 927 667-21-21'))
      : h('ol', { class: 'steps' }, steps.map((s, i) =>
        h('li', { class: i < at ? 'past' : i === at ? 'now' : '', 'aria-current': i === at ? 'step' : null }, STATUS[s]))),
    h('p', { class: 'pickup' }, o.status === 'done' ? 'Приятного аппетита!' : `Заберите ${o.asap ? 'примерно ' : ''}к ${time(o.pickupAt)} · пр. Ленина, 22`),
    h('ul', { class: 'status-lines' }, o.items.map((i) => h('li', {}, h('span', {}, `${i.name} × ${i.qty}`), h('span', {}, rub(i.price * i.qty))))),
    h('p', { class: 'status-total' }, h('span', {}, 'Итого при получении'), h('b', {}, rub(o.total))),
    h('p', { class: 'muted' }, 'Статус обновляется сам. Адрес этой страницы — ссылка на ваш заказ.'));
}

const lastOrder = () => store.get('jeti.orders', [])[0];

function init() {
  const contact = store.get('jeti.contact', {});
  for (const [k, v] of Object.entries(contact)) if ($('checkout').elements[k]) $('checkout').elements[k].value = v;

  $('cart-open').addEventListener('click', openCart);
  $('cart-fab').addEventListener('click', openCart);
  $('checkout').addEventListener('submit', submit);
  $('my-order').addEventListener('click', () => {
    if (watched) openStatus(watched.id, watched.token);
  });
  addEventListener('hashchange', onHash);
  // A phone may pause a page in the background; ask again as soon as it is back on screen.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden || !watched || FINAL.has(watched.order?.status)) return;
    const { id, token } = watched;
    watched.ctrl.abort();
    watchOrder(id, token);
  });

  openCat = catFromHash();
  loadMenu()
    .then(() => { if (openCat) $('menu').scrollIntoView({ block: 'start' }); })
    .catch(() => {
      $('menu-list').replaceChildren(h('p', { class: 'form-error' }, 'Не удалось загрузить меню. Обновите страницу или позвоните нам.'));
    });

  const params = new URLSearchParams(location.search);
  if (params.get('order') && params.get('t')) {
    const saved = store.get('jeti.orders', []).filter((o) => String(o.id) !== params.get('order'));
    store.set('jeti.orders', [{ id: Number(params.get('order')), token: params.get('t') }, ...saved].slice(0, 5));
    openStatus(params.get('order'), params.get('t'));
  } else if (lastOrder()) {
    // The header shows the last order only while it is still in progress.
    const o = lastOrder();
    api(`/api/orders/${o.id}?token=${encodeURIComponent(o.token)}`)
      .then((order) => { if (!FINAL.has(order.status)) watchOrder(o.id, o.token); })
      .catch(() => {});
  }
}

init();
