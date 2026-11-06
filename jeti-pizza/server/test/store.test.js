process.env.TZ = 'Europe/Moscow';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { openStore, normalizePhone, OrderError } from '../store.js';

const menu = JSON.parse(readFileSync(new URL('../menu.json', import.meta.url), 'utf8'));
const pizza = menu.items.find((i) => i.category === 'pizza');
const sauce = menu.items.find((i) => i.name.startsWith('Соус'));

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
  assert.equal(o.total, pizza.price * 3 + sauce.price * 2);
  assert.deepEqual(o.items.map((i) => i.qty), [3, 2]);
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
  assert.deepEqual(list[0].items.map((i) => [i.id, i.qty]), [[pizza.id, 2], [sauce.id, 2]]);
  assert.deepEqual(list[1].items.map((i) => [i.id, i.qty]), [[sauce.id, 4]]);
  assert.equal(list[1].phone, '+79271112233');
  rejects(() => store.listOrders({ date: 'yesterday' }), 400);
});

test('price list import updates all prices at once or none', () => {
  const { store } = setup();
  const r = store.setPrices([{ id: pizza.id, price: 399 }, { id: sauce.id, price: 55 }]);
  assert.equal(r.updated, 2);
  assert.equal(store.publicMenu().items.find((i) => i.id === pizza.id).price, 399);
  rejects(() => store.setPrices([{ id: pizza.id, price: 500 }, { id: sauce.id, price: 12.5 }]), 400);
  rejects(() => store.setPrices([{ id: pizza.id, price: 500 }, { id: 'nope', price: 100 }]), 400);
  rejects(() => store.setPrices([]), 400);
  assert.equal(store.publicMenu().items.find((i) => i.id === pizza.id).price, 399); // nothing half-applied
  assert.equal(store.createOrder(order({ items: [{ id: pizza.id, qty: 1 }] }), 'web').total, 399);
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
  assert.equal(r.revenue, pizza.price * 3 + sauce.price * 2);
  assert.deepEqual(r.items.map((i) => [i.name, i.qty]), [[pizza.name, 3], [sauce.name, 2]]);
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
