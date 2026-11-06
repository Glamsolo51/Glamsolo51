import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { qrMatrix, qrSvg } from '../qr.js';

// The Android tests decode these same matrices with ZXing, an independent QR reader.
const FIXTURES = new URL('../../android/src/test/resources/qr/', import.meta.url);
export const SAMPLES = {
  short: 'Йети',
  payment: 'ST00012|Name=Иванов Иван Иванович|PersonalAcc=40817810000000000001|BankName=ПАО Сбербанк|BIC=044525225|CorrespAcc=30101810400000000225|Sum=104000|Purpose=Оплата заказа №12',
  long: `ST00012|Name=Индивидуальный предприниматель Александров Александр Александрович|Purpose=${'Оплата заказа №123456, пицца и напитки. '.repeat(4)}`,
};
const asText = (m) => m.map((row) => row.map((d) => (d ? '1' : '0')).join('')).join('\n');

test('QR matrices match the fixtures the Android test decodes', () => {
  mkdirSync(FIXTURES, { recursive: true });
  for (const [name, value] of Object.entries(SAMPLES)) {
    const file = new URL(`${name}.txt`, FIXTURES);
    const content = `${value}\n${asText(qrMatrix(value))}\n`;
    if (process.env.UPDATE_QR_FIXTURES) writeFileSync(file, content);
    assert.equal(readFileSync(file, 'utf8'), content, `${name}: run with UPDATE_QR_FIXTURES=1 and re-run the Android ApiTest`);
  }
});

test('QR picks a version that fits and keeps the finder patterns', () => {
  const sizes = Object.values(SAMPLES).map((v) => qrMatrix(v).length);
  assert.ok(sizes[0] === 21 && sizes[1] > 41 && sizes[2] >= 45, `sizes ${sizes}`); // version 1, ≥ 7 for the long ones
  const m = qrMatrix(SAMPLES.payment);
  const finder = m.slice(0, 7).map((r) => r.slice(0, 7).map((d) => (d ? 1 : 0)).join('')).join('');
  assert.equal(finder, '1111111100000110111011011101101110110000011111111');
  assert.match(qrSvg('x'), /^<svg [^>]*viewBox="0 0 29 29"/);
  assert.throws(() => qrMatrix('я'.repeat(400)), /Too much/);
});
