// Price list import and export of the cabinet (public/admin/xlsx.js and prices.js run in the browser).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { deflateRawSync, crc32 } from 'node:zlib';
import { readTable, writeXlsx } from '../public/admin/xlsx.js';
import { priceListRows, matchPrices, parsePrice, WIDTHS } from '../public/admin/prices.js';

const menu = JSON.parse(readFileSync(new URL('../menu.json', import.meta.url), 'utf8'));
const pizza = menu.items.find((i) => i.name === 'Пепперони');
const syrups = menu.items.filter((i) => i.name === 'Сироп');

// A book the way Excel writes it: shared strings, deflate, an "x:" prefix, cells without r="".
function excelLikeBook(rows) {
  const strings = [];
  const si = (s) => (strings.includes(s) ? strings : strings.push(s) && strings).indexOf(s);
  const sheet = `<?xml version="1.0"?><x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:sheetData>${
    rows.map((r, i) => `<x:row r="${i + 3}">${r.map((v) => typeof v === 'number' ? `<x:c><x:v>${v}</x:v></x:c>` : `<x:c t="s"><x:v>${si(v)}</x:v></x:c>`).join('')}</x:row>`).join('')
  }</x:sheetData></x:worksheet>`;
  const files = {
    'xl/workbook.xml': '<workbook xmlns:r="r"><sheets><sheet name="Прайс" sheetId="7" r:id="rId3"/></sheets></workbook>',
    'xl/_rels/workbook.xml.rels': '<Relationships><Relationship Target="/xl/worksheets/sheet7.xml" Id="rId3" Type="worksheet"/></Relationships>',
    'xl/sharedStrings.xml': `<sst>${strings.map((s) => s === 'Цена, ₽' ? '<si><r><t>Цена</t></r><r><t xml:space="preserve">, ₽</t></r><rPh><t>ignored</t></rPh></si>' : `<si><t>${s.replace(/&/g, '&amp;')}</t></si>`).join('')}</sst>`,
    'xl/worksheets/sheet7.xml': sheet,
  };
  const parts = [];
  const central = [];
  let offset = 0;
  for (const [name, text] of Object.entries(files)) {
    const raw = Buffer.from(text);
    const data = deflateRawSync(raw);
    const n = Buffer.from(name);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(8, 8); local.writeUInt32LE(crc32(raw), 14);
    local.writeUInt32LE(data.length, 18); local.writeUInt32LE(raw.length, 22); local.writeUInt16LE(n.length, 26);
    parts.push(local, n, data);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(8, 10); c.writeUInt32LE(crc32(raw), 16);
    c.writeUInt32LE(data.length, 20); c.writeUInt32LE(raw.length, 24); c.writeUInt16LE(n.length, 28); c.writeUInt32LE(offset, 42);
    central.push(c, n);
    offset += 30 + n.length + data.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(central.length / 2, 8); end.writeUInt16LE(central.length / 2, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, cd, end]);
}

test('the downloaded price list reads back exactly', async () => {
  const rows = priceListRows(menu);
  const back = await readTable(writeXlsx(rows, { sheet: 'Прайс', widths: WIDTHS }));
  assert.deepEqual(back, rows.map((r) => r.map((v) => v ?? '')));
  assert.equal(matchPrices(back, menu.items).changes.length, 0);
});

test('a book saved by Excel: shared strings, compression, rich text', async () => {
  const rows = await readTable(excelLikeBook([
    ['Прайс «Йети Пицца» & кофе'],
    ['Позиция', 'Цена, ₽'],
    ['Пепперони', 390],
    ['Несуществующая', 100],
  ]));
  assert.deepEqual(rows.slice(2), [['Прайс «Йети Пицца» & кофе'], ['Позиция', 'Цена, ₽'], ['Пепперони', 390], ['Несуществующая', 100]]);
  const m = matchPrices(rows, menu.items);
  assert.deepEqual(m.changes.map((c) => [c.item.id, c.price]), [[pizza.id, 390]]);
  assert.deepEqual(m.notFound.map((x) => x.name), ['Несуществующая']);
});

test('CSV from Excel in Russian (Windows-1251, semicolons) and in UTF-8', async () => {
  const text = 'Позиция;Объём;Цена\r\nПепперони;330 г;"395,00"\r\nСироп;;25\r\n';
  const cp1251 = Buffer.from([...text].map((ch) => {
    const code = ch.charCodeAt(0);
    return code >= 0x410 && code <= 0x44f ? code - 0x350 : code === 0x451 ? 0xb8 : code;
  }));
  for (const bytes of [cp1251, Buffer.from('﻿' + text)]) {
    const m = matchPrices(await readTable(bytes), menu.items);
    assert.deepEqual(m.changes.map((c) => [c.item.name, c.price]), [['Пепперони', 395]]);
    assert.equal(m.ambiguous.length, syrups.length > 1 ? 1 : 0); // three syrups of one name: only a code tells them apart
  }
});

test('prices are matched by code first and checked', () => {
  const rows = [
    ['Код', 'Позиция', 'Цена'],
    [syrups[1].id, 'Сироп', 30],
    [pizza.id, 'Пепперони', '340,5'],
    ['', 'пепперони ', ''],
    ['', '', ''],
    [menu.items[0].id, 'другое название', '1 200 ₽'],
  ];
  const m = matchPrices(rows, menu.items);
  assert.deepEqual(m.changes.map((c) => [c.item.id, c.price]), [[syrups[1].id, 30], [menu.items[0].id, 1200]]);
  assert.deepEqual(m.invalid.map((x) => x.value), ['340,5']);
  assert.throws(() => matchPrices([['Позиция', 'Количество']], menu.items), /Цена/);
  assert.equal(parsePrice('1 200 руб.'), 1200);
  assert.ok(Number.isNaN(parsePrice('дорого')));
});
