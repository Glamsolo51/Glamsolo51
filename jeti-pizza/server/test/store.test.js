process.env.TZ = 'Europe/Moscow';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { openStore, normalizePhone, OrderError } from '../store.js';

const menu = JSON.parse(readFileSync(new URL('../menu.json', import.meta.url), 'utf8'));
const pizza = menu.items.find((i) => i.category === 'pizza');
const sauce = menu.items.find((i) => i.name.startsWith('Соус'));
const box = menu.items.find((i) => i.name === 'Коробка');
const latte = menu.items.find((i) => i.name === 'Латте');
const cup400 = menu.items.find((i) => i.id === latte.pack);

function setup(hour = 12, minute = 0) {
  const clock = { t: new Date(2026, 10, 6, hour, minute).getTime() };
  const store = openStore(':memory:', menu, { now: () => clock.t });
  return { store, clock };
}
const order = (extra = {}) => ({
  name: 'Анна', phone: '8 (927) 111-22-33', email: 'Anna@Example.ru', pickupTime: 'asap',
  items: [{ id: pizza.id, qty: 1 }, { id: sauce.id, qty: 2 }, { id: pizza.id, qty: 1 }], ...extra,
});
const rejects = (fn, status) => assert.throws(fn, (e) => e instanceof OrderError && e.status === status);

test('phone numbers are normalized to +7XXXXXXXXXX', () => {
  assert.equal(normalizePhone('8 (927) 667-21-21'), '+79276672121');
  assert.equal(normalizePhone('+7 927 667 21 21'), '+79276672121');
  assert.equal(normalizePhone('9276672121'), '+79276672121');
  assert.equal(normalizePhone('12345'), null);
});

test('order total is computed on the server and duplicate lines merge', () => {
  const { store } = setup();
  const o = store.createOrder({ ...order(), items: [...order().items, { id: pizza.id, qty: 1, price: 1 }] }, 'web');
  assert.equal(o.total, pizza.price * 3 + sauce.price * 2 + box.price * 3);
  assert.deepEqual(o.items.map((i) => [i.name, i.qty]), [[pizza.name, 3], [sauce.name, 2], ['Коробка', 3]]);
  assert.equal(o.status, 'new');
  assert.equal(o.pickupAt, new Date(2026, 10, 6, 12, 20).getTime());
  assert.ok(store.publicOrder(o.id, o.token));
  assert.equal(store.publicOrder(o.id, 'wrong-token'), null);
});

test('cooldown blocks the same phone or the same email, not cancelled orders', () => {
  const { store, clock } = setup();
  const first = store.createOrder(order(), 'web');
  rejects(() => store.createOrder(order({ email: 'other@example.ru' }), 'app'), 429);
  rejects(() => store.createOrder(order({ phone: '+79000000000', email: 'anna@example.ru' }), 'web'), 429);
  store.createOrder(order({ phone: '+79000000000', email: 'other@example.ru' }), 'web'); // different customer

  try {
    store.createOrder(order(), 'web');
  } catch (e) {
    assert.equal(e.extra.retryAfter, 15 * 60);
  }
  clock.t += 15 * 60_000;
  store.createOrder(order(), 'web');

  clock.t += 60_000;
  store.setStatus(store.listOrders()[2].id, 'cancelled');
  assert.equal(store.listOrders().length, 2);
  store.updateSettings({ cooldownMinutes: 0 });
  store.createOrder(order(), 'web');
  assert.ok(first.id);
});

test('orders are taken from 10:00 to 21:30 only', () => {
  const morning = setup(9, 59).store;
  assert.equal(morning.schedule().orderingOpen, false);
  assert.deepEqual(morning.schedule().slots, []);
  rejects(() => morning.createOrder(order(), 'web'), 409);
  rejects(() => morning.createOrder(order({ pickupTime: '10:30' }), 'web'), 409); // no pre-orders before opening

  const opening = setup(10, 0).store;
  assert.equal(opening.schedule().slots[0], '10:30');
  assert.equal(opening.createOrder(order(), 'web').pickupAt, new Date(2026, 10, 6, 10, 20).getTime());

  const lastMinute = setup(21, 30).store;
  assert.equal(lastMinute.schedule().asapAllowed, true);
  assert.deepEqual(lastMinute.schedule().slots, []); // 21:30 + 20 min cooking is past the last 21:45 slot
  assert.equal(lastMinute.createOrder(order(), 'web').pickupAt, new Date(2026, 10, 6, 21, 50).getTime());

  const evening = setup(21, 15).store;
  assert.deepEqual(evening.schedule().slots, ['21:45']);

  const late = setup(21, 31).store;
  assert.equal(late.schedule().orderingOpen, false);
  rejects(() => late.createOrder(order(), 'web'), 409);
});

test('pickup slots and the stop-list', () => {
  const noon = setup(12, 0).store;
  rejects(() => noon.createOrder(order({ pickupTime: '23:00' }), 'web'), 400);
  rejects(() => noon.createOrder(order({ pickupTime: '11:00' }), 'web'), 400); // in the past
  assert.equal(noon.createOrder(order({ pickupTime: '13:00' }), 'web').pickupAt, new Date(2026, 10, 6, 13, 0).getTime());

  const { store } = setup();
  store.updateItem(pizza.id, { available: false });
  rejects(() => store.createOrder(order(), 'web'), 409);
  rejects(() => store.createOrder({ ...order(), items: [] }, 'web'), 400);
  store.updateSettings({ acceptingOrders: false });
  rejects(() => store.createOrder(order({ items: [{ id: sauce.id, qty: 1 }] }), 'web'), 409);
});

test('the board lists every active order with its own lines', () => {
  const { store } = setup();
  store.updateSettings({ cooldownMinutes: 0 });
  const a = store.createOrder(order(), 'web');
  const b = store.createOrder(order({ items: [{ id: sauce.id, qty: 4 }] }), 'app');
  const list = store.listOrders();
  assert.deepEqual(list.map((o) => o.id), [a.id, b.id]);
  assert.deepEqual(list[0].items.map((i) => [i.id, i.qty]), [[pizza.id, 2], [sauce.id, 2], [box.id, 2]]); // the box comes with the pizza
  assert.deepEqual(list[1].items.map((i) => [i.id, i.qty]), [[sauce.id, 4]]);
  assert.equal(list[1].phone, '+79271112233');
  rejects(() => store.listOrders({ date: 'yesterday' }), 400);
});

test('import updates all prices at once or none', () => {
  const { store } = setup();
  const r = store.importMenu([['ID', 'Цена, ₽'], [pizza.id, 399], [sauce.id, 55]]);
  assert.equal(r.updated, 2);
  assert.equal(store.publicMenu().items.find((i) => i.id === pizza.id).price, 399);
  rejects(() => store.importMenu([['ID', 'Цена, ₽'], [pizza.id, 500], [sauce.id, 12.5]]), 400);
  rejects(() => store.importMenu([['ID', 'Цена, ₽'], [pizza.id, 500], ['nope', 100]]), 400);
  assert.equal(store.publicMenu().items.find((i) => i.id === pizza.id).price, 399); // nothing half-applied
  assert.equal(store.createOrder(order({ items: [{ id: pizza.id, qty: 1 }] }), 'web').total, 399 + box.price);
});

test('report sums positions and skips cancelled orders', () => {
  const { store } = setup();
  store.updateSettings({ cooldownMinutes: 0 });
  store.createOrder(order(), 'web');
  store.createOrder(order({ items: [{ id: pizza.id, qty: 1 }] }), 'app');
  const cancelled = store.createOrder(order({ items: [{ id: sauce.id, qty: 5 }] }), 'web');
  store.setStatus(cancelled.id, 'cancelled');

  const r = store.report('2026-11-06', '2026-11-06');
  assert.equal(r.orders, 2);
  assert.equal(r.cancelled, 1);
  assert.equal(r.revenue, pizza.price * 3 + sauce.price * 2 + box.price * 3);
  assert.deepEqual(r.items.map((i) => [i.name, i.qty]), [[pizza.name, 3], [sauce.name, 2], ['Коробка', 3]]);
  assert.equal(Math.round(r.items.reduce((s, i) => s + i.share, 0) * 100), 100);
  assert.equal(store.report('2026-11-05', '2026-11-05').orders, 0);
});

test('staff login, sessions and password change', () => {
  const { store } = setup();
  const generated = store.ensureAdmin('admin', undefined);
  assert.ok(generated.length >= 10);
  assert.equal(store.login('admin', 'nope'), null);
  const token = store.login('Admin', generated);
  assert.ok(store.checkSession(token));
  rejects(() => store.changePassword('wrong', 'new-password-1'), 403);
  rejects(() => store.changePassword(generated, 'short'), 400);
  store.changePassword(generated, 'new-password-1');
  assert.equal(store.checkSession(token), false);
  assert.ok(store.login('admin', 'new-password-1'));
  assert.equal(store.ensureAdmin('admin', undefined), null); // keeps the changed password
});

test('ADMIN_PASSWORD resets the password only when its value changes', () => {
  const { store } = setup();
  store.ensureAdmin('admin', 'from-env-1');
  store.changePassword('from-env-1', 'changed-in-cabinet');
  store.ensureAdmin('admin', 'from-env-1'); // restart with the same env
  assert.ok(store.login('admin', 'changed-in-cabinet'));
  store.ensureAdmin('admin', 'from-env-2'); // new env value: reset
  assert.equal(store.login('admin', 'changed-in-cabinet'), null);
  assert.ok(store.login('admin', 'from-env-2'));
});

test('packaging is added by the server: a box per pizza, a cup per drink, never what the client sent', () => {
  const { store } = setup();
  const o = store.createOrder(order({ items: [{ id: pizza.id, qty: 2 }, { id: latte.id, qty: 1 }, { id: box.id, qty: 9 }, { id: sauce.id, qty: 1 }] }), 'web');
  assert.deepEqual(o.items.map((i) => [i.name, i.qty]), [[pizza.name, 2], ['Латте', 1], [sauce.name, 1], ['Коробка', 2], [cup400.name, 1]]);
  assert.equal(o.total, pizza.price * 2 + latte.price + sauce.price + box.price * 2 + cup400.price);
  store.updateItem(box.id, { available: false }); // out of boxes: still sells the pizza
  store.updateSettings({ cooldownMinutes: 0 });
  assert.deepEqual(store.createOrder(order({ items: [{ id: pizza.id, qty: 1 }] }), 'web').items.map((i) => i.name), [pizza.name]);
  const menuItems = store.publicMenu().items;
  assert.equal(menuItems.find((i) => i.id === pizza.id).pack, box.id);
});

test('composition tags drive the filters', async () => {
  const { tagsFor } = await import('../store.js');
  const byName = (n) => tagsFor(menu.items.find((i) => i.name === n && i.category === 'pizza'));
  assert.deepEqual(byName('Казанская'), ['chicken', 'mushrooms']);
  assert.deepEqual(byName('Амиго'), ['chicken', 'spicy']);
  assert.deepEqual(byName('Пепперони'), ['spicy', 'meat']);
  assert.deepEqual(byName('Маргарита'), ['veggie']);
  assert.ok(byName('Грибная').includes('mushrooms'));
});

test('transfer payment: phone, QR when bank details exist, customer claim, staff confirm', () => {
  const { store } = setup();
  let o = store.createOrder(order({ payment: 'transfer' }), 'web');
  assert.equal(o.paymentStatus, 'awaiting');
  assert.equal(o.pay.phone, '+79279960111');
  assert.equal(o.pay.qr, ''); // no bank details yet
  assert.equal(store.claimPaid(o.id, o.token).paymentStatus, 'claimed');
  assert.throws(() => store.claimPaid(o.id, 'bad'), (e) => e.status === 404);
  assert.equal(store.setPayment(o.id, 'paid').paymentStatus, 'paid');

  store.updateSettings({ cooldownMinutes: 0, payment: { recipient: 'Иванов Иван Иванович', bank: 'ПАО Сбербанк', account: '40817810000000000001', bic: '044525225', corr: '30101810400000000225' } });
  o = store.createOrder(order({ payment: 'transfer' }), 'app');
  assert.match(o.pay.qr, new RegExp(`^ST00012\\|Name=Иванов Иван Иванович\\|PersonalAcc=40817810000000000001\\|.*\\|Sum=${o.total * 100}\\|Purpose=Оплата заказа №${o.id}$`));
  assert.throws(() => store.updateSettings({ payment: { bic: '123' } }), (e) => e.status === 400);

  const cash = store.createOrder(order(), 'web');
  assert.equal(cash.paymentStatus, 'none');
  assert.throws(() => store.claimPaid(cash.id, cash.token), (e) => e.status === 400);
  store.updateSettings({ payment: { transfer: false } });
  assert.throws(() => store.createOrder(order({ payment: 'transfer' }), 'web'), (e) => e.status === 400);
});

test('Excel template round trip: edit prices, add an item, reject a bad file whole', async () => {
  const { writeXlsx, readTable } = await import('../public/admin/xlsx.js');
  const { store } = setup();
  const { rows } = store.exportMenu();
  const header = rows[0];
  const at = (name) => header.indexOf(name);
  const edited = rows.map((r) => [...r]);
  const pepperoni = edited.find((r) => r[at('Название')] === 'Пепперони');
  pepperoni[at('Цена, ₽')] = 355;
  pepperoni[at('Описание (состав)')] = 'пепперони, сыр, томатный соус';
  pepperoni[at('Объём')] = '360 г';
  edited.push(['', 'Пицца', 'Новая с халапеньо', 'курица, халапеньо', '330 г', '310 ₽', 'да', 'Коробка']);
  const table = await readTable(writeXlsx(edited, { sheet: 'Меню' }));
  assert.deepEqual(store.importMenu(table, { dryRun: true }).changes.map((c) => c.name), ['Пепперони', 'Новая с халапеньо']);
  assert.equal(store.publicMenu().items.find((i) => i.name === 'Пепперони').price, menu.items.find((i) => i.name === 'Пепперони').price); // preview saves nothing
  const result = store.importMenu(table);
  assert.deepEqual([result.created, result.updated], [1, 1]); // only rows that differ count
  const items = store.publicMenu().items;
  const p = items.find((i) => i.name === 'Пепперони');
  assert.deepEqual([p.price, p.unit, p.description], [355, '360 г', 'пепперони, сыр, томатный соус']);
  const added = items.find((i) => i.name === 'Новая с халапеньо');
  assert.deepEqual([added.category, added.price, added.pack, added.tags], ['pizza', 310, box.id, ['chicken', 'spicy']]);

  const bad = [header, ['', 'Нет такой', 'X', '', '', '100', '', ''], [pepperoni[0], '', 'Пепперони', '', '', 'дорого', '', '']];
  assert.throws(() => store.importMenu(bad), (e) => e.status === 400 && e.extra.errors.length === 2 && e.extra.errors[0].row === 2);
  assert.equal(store.publicMenu().items.find((i) => i.name === 'Пепперони').price, 355); // nothing applied
});

test('a workbook saved by another program (shared strings) is read', async () => {
  const { readTable } = await import('../public/admin/xlsx.js');
  const rows = await readTable(readFileSync(new URL('./fixtures/saved-by-openpyxl.xlsx', import.meta.url)));
  assert.deepEqual(rows[1], ['1', 'Пицца «Три сыра» & <x>', 345, '350 г']);
  assert.deepEqual(rows[3], ['', 'Добавлена в Excel', 120, '300 мл']);

});

test('site texts and photos are validated', () => {
  const { store } = setup();
  assert.equal(store.publicMenu().site.tagline, '24 лучшие пиццы в городе Канаш');
  store.updateSite({ tagline: 'Новый слоган', hero: '/uploads/abc123.webp', gallery: ['/img/sign.webp'] });
  assert.deepEqual([store.publicMenu().site.tagline, store.publicMenu().site.hero], ['Новый слоган', '/uploads/abc123.webp']);
  assert.throws(() => store.updateSite({ hero: 'https://evil.example/x.png' }), (e) => e.status === 400);
  assert.throws(() => store.updateSite({ hero: '/uploads/../store.js' }), (e) => e.status === 400);
  assert.throws(() => store.updateItem(pizza.id, { photo: 'javascript:alert(1)' }), (e) => e.status === 400);
  assert.equal(store.updateItem(pizza.id, { photo: '/uploads/p1.jpg' }).photo, '/uploads/p1.jpg');
});
