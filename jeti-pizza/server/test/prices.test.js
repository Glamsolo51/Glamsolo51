// Menu import from Excel and CSV: public/admin/xlsx.js reads the file in the browser, store.importMenu applies it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { deflateRawSync, crc32 } from 'node:zlib';
import { readTable, writeXlsx } from '../public/admin/xlsx.js';
import { openStore } from '../store.js';

const menu = JSON.parse(readFileSync(new URL('../menu.json', import.meta.url), 'utf8'));
const pizza = menu.items.find((i) => i.name === 'Пепперони');
const syrups = menu.items.filter((i) => i.name === 'Сироп');
const fresh = () => openStore(':memory:', menu);
const preview = (rows) => fresh().importMenu(rows, { dryRun: true });

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

test('the downloaded template reads back exactly and changes nothing', async () => {
  const store = fresh();
  const { rows } = store.exportMenu();
  const back = await readTable(writeXlsx(rows, { sheet: 'Меню', widths: [14, 18, 26, 50, 12, 10, 11, 24] }));
  assert.deepEqual(back, rows.map((r) => r.map((v) => v ?? '')));
  assert.deepEqual(store.importMenu(back, { dryRun: true }), { created: 0, updated: 0, changes: [] });
});

test('a book saved by Excel: shared strings, compression, rich text; rows found by name', async () => {
  const rows = await readTable(excelLikeBook([
    ['Прайс «Йети Пицца» & кофе'],
    ['Позиция', 'Цена, ₽'],
    ['Пепперони', 390],
  ]));
  assert.deepEqual(rows.slice(2), [['Прайс «Йети Пицца» & кофе'], ['Позиция', 'Цена, ₽'], ['Пепперони', 390]]);
  const p = preview(rows);
  assert.deepEqual(p.changes.map((c) => [c.name, c.oldPrice, c.price, c.fields]), [['Пепперони', pizza.price, 390, ['price']]]);
  // A name that is not in the menu, without a section: refused, so a typo never becomes a new position.
  assert.throws(() => preview([...rows, ['Несуществующая', 100]]), (e) => e.status === 400 && /Несуществующая/.test(e.extra.errors[0].message));
});

test('CSV from Excel in Russian (Windows-1251, semicolons) and in UTF-8', async () => {
  const text = 'Позиция;Объём;Цена\r\nПепперони;330 г;"395,00"\r\nСироп;;25\r\n';
  const cp1251 = Buffer.from([...text].map((ch) => {
    const code = ch.charCodeAt(0);
    return code >= 0x410 && code <= 0x44f ? code - 0x350 : code === 0x451 ? 0xb8 : code;
  }));
  for (const bytes of [cp1251, Buffer.from('\ufeff' + text)]) {
    const rows = await readTable(bytes);
    assert.deepEqual(rows[1], ['Пепперони', '330 г', '395,00']);
    // Three syrups of one name and no volume: only an ID tells them apart, so the file is refused as a whole.
    assert.throws(() => preview(rows), (e) => e.status === 400 && /несколько раз/.test(e.extra.errors[0].message));
    assert.deepEqual(preview(rows.slice(0, 2)).changes.map((c) => [c.name, c.price]), [['Пепперони', 395]]);
  }
});

test('rows are matched by ID first, prices and sections are checked', () => {
  const rows = [
    ['ID', 'Позиция', 'Цена'],
    [syrups[1].id, 'Сироп', 30],
    [menu.items[0].id, menu.items[0].name, '1 200 ₽'],
  ];
  assert.deepEqual(preview(rows).changes.map((c) => [c.name, c.price]), [['Сироп', 30], [menu.items[0].name, 1200]]);
  assert.throws(() => preview([...rows, [pizza.id, 'Пепперони', '340,5']]), (e) => /340,5/.test(e.extra.errors[0].message));
  assert.throws(() => preview([['Позиция', 'Количество']]), /Цена/);
  assert.throws(() => preview([['Раздел', 'Название', 'Цена'], ['Нет такого', 'Новая', 100]]), (e) => /раздела/.test(e.extra.errors[0].message));
});
