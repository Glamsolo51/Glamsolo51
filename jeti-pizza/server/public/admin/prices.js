// Price list in Excel: what the cabinet downloads, and how an edited file is matched back to the menu.

export const HEADER = ['Код', 'Раздел', 'Позиция', 'Объём', 'Цена, ₽'];
export const WIDTHS = [14, 22, 40, 12, 10];

export function priceListRows(menu) {
  const catName = new Map(menu.categories.map((c) => [c.id, c.name]));
  return [HEADER, ...menu.items.map((i) => [i.id, catName.get(i.category) ?? '', i.name, i.unit, i.price])];
}

const norm = (s) => String(s ?? '').toLowerCase().replace(/ё/g, 'е').replace(/\s+/g, ' ').trim();

// The header row: the first of the top rows that has a "Цена" column.
export function findColumns(rows) {
  for (let r = 0; r < Math.min(rows.length, 15); r++) {
    const cells = (rows[r] ?? []).map(norm);
    const price = cells.findIndex((c) => /цен|стоимост|price/.test(c));
    if (price < 0) continue;
    return {
      header: r,
      price,
      code: cells.findIndex((c) => /^(код|id|артикул)/.test(c)),
      name: cells.findIndex((c) => /позиц|наименов|назван|товар|блюд/.test(c)),
      unit: cells.findIndex((c) => /объем|вес|выход|ед\.? ?изм/.test(c)),
    };
  }
  return null;
}

// "340", 340, "340,00", "340 ₽", "1 200 руб." -> rubles; empty -> null; anything else -> NaN.
export function parsePrice(value) {
  if (typeof value === 'number') return Math.round(value * 100) / 100;
  const s = String(value ?? '').replace(/руб\.?|р\.|₽|[\s ]/gi, '').replace(',', '.');
  if (!s) return null;
  return /^\d+(\.\d+)?$/.test(s) ? Number(s) : NaN;
}

/** Compares the file with the menu: which prices change, and which rows could not be used and why. */
export function matchPrices(rows, items) {
  const cols = findColumns(rows);
  if (!cols) throw new Error('В файле не нашли столбец «Цена». Скачайте прайс из кабинета и впишите новые цены в него.');
  if (cols.code < 0 && cols.name < 0) throw new Error('В файле нет столбца «Код» или «Позиция»: непонятно, к чему относятся цены.');
  const byId = new Map(items.map((i) => [i.id, i]));
  const byName = new Map();
  for (const i of items) byName.set(norm(i.name), [...(byName.get(norm(i.name)) ?? []), i]);

  const changes = new Map();
  const result = { changes: [], unchanged: 0, notFound: [], invalid: [], ambiguous: [] };
  for (let r = cols.header + 1; r < rows.length; r++) {
    const row = rows[r] ?? [];
    const code = cols.code >= 0 ? String(row[cols.code] ?? '').trim() : '';
    const name = cols.name >= 0 ? String(row[cols.name] ?? '').trim() : '';
    if (!code && !name) continue; // an empty line
    const label = { row: r + 1, name: name || code };
    let item = code ? byId.get(code) : undefined;
    if (!item && name) {
      let found = byName.get(norm(name)) ?? [];
      if (found.length > 1 && cols.unit >= 0) found = found.filter((i) => norm(i.unit) === norm(row[cols.unit]));
      if (found.length > 1) {
        result.ambiguous.push(label);
        continue;
      }
      item = found[0];
    }
    if (!item) {
      result.notFound.push(label);
      continue;
    }
    const price = parsePrice(row[cols.price]);
    if (price === null) continue; // no price in this row: keep the current one
    if (!Number.isInteger(price) || price < 1 || price > 100_000) {
      result.invalid.push({ ...label, name: item.name, value: String(row[cols.price]) });
      continue;
    }
    changes.set(item.id, { item, price }); // the same position twice: the lower row wins
  }
  for (const c of changes.values()) {
    if (c.price === c.item.price) result.unchanged++;
    else result.changes.push(c);
  }
  return result;
}
