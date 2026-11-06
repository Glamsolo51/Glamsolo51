// QR code generator (ISO/IEC 18004): byte mode, error correction level M, versions 1–20.
// Enough for a bank payment string (ГОСТ Р 56042 "ST00012…", up to ~660 bytes of UTF-8).

// Level M, per version: [EC codewords per block, [data codewords of each block]].
const BLOCKS_M = [null,
  [10, [16]], [16, [28]], [26, [44]], [18, [32, 32]], [24, [43, 43]], [16, [27, 27, 27, 27]], [18, [31, 31, 31, 31]],
  [22, [38, 38, 39, 39]], [22, [36, 36, 36, 37, 37]], [26, [43, 43, 43, 43, 44]], [30, [50, 51, 51, 51, 51]],
  [22, [36, 36, 36, 36, 36, 36, 37, 37]], [22, [37, 37, 37, 37, 37, 37, 37, 37, 38]],
  [24, [40, 40, 40, 40, 41, 41, 41, 41, 41]], [24, [41, 41, 41, 41, 41, 42, 42, 42, 42, 42]],
  [28, [45, 45, 45, 45, 45, 45, 45, 46, 46, 46]], [28, [46, 46, 46, 46, 46, 46, 46, 46, 46, 46, 47]],
  [26, [43, 43, 43, 43, 43, 43, 43, 43, 43, 44, 44, 44, 44]], [26, [44, 44, 44, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45]],
  [26, [41, 41, 41, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42]],
];

// Modules left for data and EC after the function patterns; checks the table above.
function rawCodewords(v) {
  let bits = (16 * v + 128) * v + 64;
  if (v >= 2) {
    const n = Math.floor(v / 7) + 2;
    bits -= (25 * n - 10) * n - 55;
    if (v >= 7) bits -= 36;
  }
  return Math.floor(bits / 8);
}
for (let v = 1; v < BLOCKS_M.length; v++) {
  const [ec, data] = BLOCKS_M[v];
  if (data.reduce((a, b) => a + b, 0) + ec * data.length !== rawCodewords(v)) throw new Error(`QR table broken at version ${v}`);
}

const gfMul = (x, y) => {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
};

function rsDivisor(degree) {
  const result = new Array(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = gfMul(result[j], root);
      if (j + 1 < degree) result[j] ^= result[j + 1];
    }
    root = gfMul(root, 2);
  }
  return result;
}

function rsRemainder(data, divisor) {
  const result = divisor.map(() => 0);
  for (const b of data) {
    const factor = b ^ result.shift();
    result.push(0);
    divisor.forEach((c, i) => { result[i] ^= gfMul(c, factor); });
  }
  return result;
}

function alignmentPositions(v) {
  if (v === 1) return [];
  const n = Math.floor(v / 7) + 2;
  const step = Math.ceil((v * 4 + 4) / (n * 2 - 2)) * 2;
  const result = [6];
  for (let pos = v * 4 + 10; result.length < n; pos -= step) result.splice(1, 0, pos);
  return result;
}

const MASKS = [
  (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
];

/** Square matrix of booleans (true = dark), without the quiet zone. */
export function qrMatrix(textValue) {
  const bytes = [...Buffer.from(textValue, 'utf8')];
  let version = 1;
  const countBits = (v) => (v <= 9 ? 8 : 16);
  const capacity = (v) => BLOCKS_M[v][1].reduce((a, b) => a + b, 0);
  while (version < BLOCKS_M.length && 4 + countBits(version) + bytes.length * 8 > capacity(version) * 8) version++;
  if (version >= BLOCKS_M.length) throw new Error('Too much data for a QR code');

  // Bit stream: byte mode, length, data, terminator, padding.
  const bits = [];
  const put = (value, n) => { for (let i = n - 1; i >= 0; i--) bits.push((value >>> i) & 1); };
  put(0b0100, 4);
  put(bytes.length, countBits(version));
  for (const b of bytes) put(b, 8);
  const cap = capacity(version) * 8;
  put(0, Math.min(4, cap - bits.length));
  put(0, (8 - (bits.length % 8)) % 8);
  for (let pad = 0xec; bits.length < cap; pad ^= 0xec ^ 0x11) put(pad, 8);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));

  // Split into blocks, add Reed-Solomon codewords, interleave.
  const [ecLen, blockLens] = BLOCKS_M[version];
  const divisor = rsDivisor(ecLen);
  const blocks = [];
  let k = 0;
  for (const len of blockLens) {
    const d = data.slice(k, k + len);
    k += len;
    blocks.push({ d, e: rsRemainder(d, divisor) });
  }
  const codewords = [];
  for (let i = 0; i < Math.max(...blockLens); i++) for (const b of blocks) if (i < b.d.length) codewords.push(b.d[i]);
  for (let i = 0; i < ecLen; i++) for (const b of blocks) codewords.push(b.e[i]);

  // Function patterns.
  const size = version * 4 + 17;
  const modules = Array.from({ length: size }, () => new Array(size).fill(false));
  const isFunction = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (x, y, dark) => { modules[y][x] = dark; isFunction[y][x] = true; };
  for (let i = 0; i < size; i++) {
    set(6, i, i % 2 === 0);
    set(i, 6, i % 2 === 0);
  }
  for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx;
        const y = cy + dy;
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist !== 2 && dist !== 4);
      }
    }
  }
  const align = alignmentPositions(version);
  align.forEach((ax, i) => align.forEach((ay, j) => {
    if ((i === 0 && j === 0) || (i === 0 && j === align.length - 1) || (i === align.length - 1 && j === 0)) return;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }));
  const drawFormat = (mask) => {
    const d = mask; // level M is 00
    let rem = d;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const f = ((d << 10) | rem) ^ 0x5412;
    const bit = (i) => ((f >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6));
    set(8, 8, bit(7));
    set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
    set(8, size - 8, true);
  };
  drawFormat(0); // reserves the area; redrawn with the chosen mask
  if (version >= 7) {
    let rem = version;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const v = (version << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const dark = ((v >>> i) & 1) === 1;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      set(a, b, dark);
      set(b, a, dark);
    }
  }

  // Data in the two-column zigzag, bottom-right upwards.
  let i = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const y = ((right + 1) & 2) === 0 ? size - 1 - vert : vert;
        if (!isFunction[y][x] && i < codewords.length * 8) {
          modules[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) === 1;
          i++;
        }
      }
    }
  }

  // Try all eight masks and keep the one scanners read best (lowest penalty).
  const applyMask = (m) => {
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!isFunction[y][x] && MASKS[m](x, y)) modules[y][x] = !modules[y][x];
  };
  let best = 0;
  let bestScore = Infinity;
  for (let m = 0; m < 8; m++) {
    applyMask(m);
    drawFormat(m);
    const score = penalty(modules);
    if (score < bestScore) [best, bestScore] = [m, score];
    applyMask(m); // undo
  }
  applyMask(best);
  drawFormat(best);
  return modules;
}

function penalty(m) {
  const size = m.length;
  const lines = [];
  for (let y = 0; y < size; y++) lines.push(m[y].map((d) => (d ? '1' : '0')).join(''));
  for (let x = 0; x < size; x++) lines.push(m.map((row) => (row[x] ? '1' : '0')).join(''));
  let score = 0;
  for (const line of lines) {
    for (const run of line.match(/0{5,}|1{5,}/g) ?? []) score += run.length - 2; // N1: runs of five or more
    score += 40 * ((line.match(/(?=10111010000|00001011101)/g) ?? []).length); // N3: finder look-alikes
  }
  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const c = m[y][x];
      if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) score += 3; // N2: 2×2 blocks
    }
  }
  const dark = m.flat().filter(Boolean).length;
  score += Math.floor(Math.abs(dark * 20 - size * size * 10) / (size * size)) * 10; // N4: balance
  return score;
}

/** SVG with a 4-module quiet zone, scaled by the page. */
export function qrSvg(textValue) {
  const m = qrMatrix(textValue);
  const n = m.length + 8;
  let d = '';
  m.forEach((row, y) => row.forEach((dark, x) => { if (dark) d += `M${x + 4} ${y + 4}h1v1h-1z`; }));
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n} ${n}" shape-rendering="crispEdges"><rect width="${n}" height="${n}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
