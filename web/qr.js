/* Byte-mode QR, ECC level M, versions 1–12. Enough for a lochatter-web login code (version 9). */
const QR = (() => {
  const EXP = new Uint8Array(512);
  const LOG = new Uint8Array(256);
  (() => {
    let x = 1;
    for (let i = 0; i < 255; i++) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if (x & 0x100) x ^= 0x11d;
    }
    for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  })();

  function mul(a, b) {
    if (a === 0 || b === 0) return 0;
    return EXP[LOG[a] + LOG[b]];
  }

  // ECC M: [version, ec per block, groups of [count, data codewords], alignment centers]
  const VERSIONS = [
    [1, 10, [[1, 16]], []],
    [2, 16, [[1, 28]], [6, 18]],
    [3, 26, [[1, 44]], [6, 22]],
    [4, 18, [[2, 32]], [6, 26]],
    [5, 24, [[2, 43]], [6, 30]],
    [6, 16, [[4, 27]], [6, 34]],
    [7, 18, [[4, 31]], [6, 22, 38]],
    [8, 22, [[2, 38], [2, 39]], [6, 24, 42]],
    [9, 22, [[3, 36], [2, 37]], [6, 26, 46]],
    [10, 26, [[4, 43], [1, 44]], [6, 28, 50]],
    [11, 30, [[1, 50], [4, 51]], [6, 30, 54]],
    [12, 22, [[6, 36], [2, 37]], [6, 32, 58]],
  ];

  function dataBytes(groups) {
    let n = 0;
    for (const [count, len] of groups) n += count * len;
    return n;
  }

  function msb(v) {
    let n = 0;
    while (v > 0) { n++; v >>>= 1; }
    return n;
  }
  function bch(value, poly) {
    const polyBits = msb(poly);
    let v = value << (polyBits - 1);
    while (msb(v) >= polyBits) v ^= poly << (msb(v) - polyBits);
    return v;
  }

  function rs(data, ecLen) {
    let gen = [1];
    for (let i = 0; i < ecLen; i++) {
      const next = new Array(gen.length + 1).fill(0);
      for (let j = 0; j < gen.length; j++) {
        next[j] ^= gen[j];
        next[j + 1] ^= mul(gen[j], EXP[i]);
      }
      gen = next;
    }
    const msg = new Array(data.length + ecLen).fill(0);
    for (let i = 0; i < data.length; i++) msg[i] = data[i];
    for (let i = 0; i < data.length; i++) {
      const coef = msg[i];
      if (coef === 0) continue;
      for (let j = 0; j < gen.length; j++) msg[i + j] ^= mul(gen[j], coef);
    }
    return msg.slice(data.length);
  }

  function pushBits(bits, value, n) {
    for (let i = n - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  }

  function encodeBytes(payload, version, groups) {
    const ccBits = version <= 9 ? 8 : 16;
    const cap = dataBytes(groups) * 8;
    const bits = [];
    pushBits(bits, 0b0100, 4);
    pushBits(bits, payload.length, ccBits);
    for (const b of payload) pushBits(bits, b, 8);
    const term = Math.min(4, cap - bits.length);
    for (let i = 0; i < term; i++) bits.push(0);
    while (bits.length % 8) bits.push(0);
    const pad = [0xec, 0x11];
    let p = 0;
    while (bits.length + 8 <= cap) {
      pushBits(bits, pad[p], 8);
      p ^= 1;
    }
    if (bits.length !== cap) throw new Error("qr pad");
    const data = [];
    for (let i = 0; i < bits.length; i += 8) {
      let v = 0;
      for (let k = 0; k < 8; k++) v = (v << 1) | bits[i + k];
      data.push(v);
    }
    return data;
  }

  function interleave(data, ecPer, groups) {
    const blocks = [];
    let off = 0;
    for (const [count, len] of groups) {
      for (let i = 0; i < count; i++) {
        const db = data.slice(off, off + len);
        off += len;
        blocks.push({ data: db, ec: rs(db, ecPer) });
      }
    }
    const out = [];
    const maxD = Math.max(...blocks.map((b) => b.data.length));
    for (let i = 0; i < maxD; i++) for (const b of blocks) if (i < b.data.length) out.push(b.data[i]);
    for (let i = 0; i < ecPer; i++) for (const b of blocks) out.push(b.ec[i]);
    return out;
  }

  function blank(n) {
    return Array.from({ length: n }, () => new Array(n).fill(null));
  }
  function clone(m) {
    return m.map((row) => row.slice());
  }

  function finder(m, x, y) {
    for (let dy = -1; dy <= 7; dy++) {
      for (let dx = -1; dx <= 7; dx++) {
        const xx = x + dx;
        const yy = y + dy;
        if (xx < 0 || yy < 0 || xx >= m.length || yy >= m.length) continue;
        const edge = dx === 0 || dx === 6 || dy === 0 || dy === 6;
        const core = dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4;
        const inside = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
        m[yy][xx] = inside && (edge || core) ? 1 : 0;
      }
    }
  }

  function align(m, cx, cy) {
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++) {
        m[cy + dy][cx + dx] = Math.max(Math.abs(dx), Math.abs(dy)) !== 1 ? 1 : 0;
      }
    }
  }

  function functions(version, alignPos) {
    const n = 17 + 4 * version;
    const m = blank(n);
    finder(m, 0, 0);
    finder(m, n - 7, 0);
    finder(m, 0, n - 7);
    for (const r of alignPos) {
      for (const c of alignPos) {
        if (m[r][c] !== null) continue;
        align(m, c, r);
      }
    }
    for (let i = 8; i < n - 8; i++) {
      const bit = i % 2 === 0 ? 1 : 0;
      if (m[6][i] === null) m[6][i] = bit;
      if (m[i][6] === null) m[i][6] = bit;
    }
    m[n - 8][8] = 1;
    const reserve = (x, y) => { if (m[y][x] === null) m[y][x] = 0; };
    const fmt = [[8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5], [8, 7], [8, 8], [7, 8], [5, 8], [4, 8], [3, 8], [2, 8], [1, 8], [0, 8]];
    for (let i = 0; i < 15; i++) {
      reserve(fmt[i][0], fmt[i][1]);
      if (i < 8) reserve(n - 1 - i, 8);
      else reserve(8, n - 15 + i);
    }
    if (version >= 7) {
      for (let i = 0; i < 18; i++) {
        const a = Math.floor(i / 3);
        const b = i % 3;
        m[n - 11 + b][a] = 0;
        m[a][n - 11 + b] = 0;
      }
    }
    return m;
  }

  function maskBit(mask, x, y) {
    switch (mask) {
      case 0: return ((x + y) & 1) === 0;
      case 1: return (y & 1) === 0;
      case 2: return x % 3 === 0;
      case 3: return (x + y) % 3 === 0;
      case 4: return (((y >> 1) + Math.floor(x / 3)) & 1) === 0;
      case 5: return ((x * y) % 2 + (x * y) % 3) === 0;
      case 6: return ((((x * y) % 2) + ((x * y) % 3)) & 1) === 0;
      default: return ((((x + y) % 2) + ((x * y) % 3)) & 1) === 0;
    }
  }

  function placeData(m, codewords, mask) {
    const bits = [];
    for (const by of codewords) pushBits(bits, by, 8);
    const n = m.length;
    let i = 0;
    let dir = -1;
    let x = n - 1;
    let y = n - 1;
    while (x > 0) {
      if (x === 6) x--;
      while (y >= 0 && y < n) {
        for (let k = 0; k < 2; k++) {
          const xx = x - k;
          if (m[y][xx] !== null) continue;
          let bit = i < bits.length ? bits[i++] : 0;
          if (maskBit(mask, xx, y)) bit ^= 1;
          m[y][xx] = bit;
        }
        y += dir;
      }
      dir = -dir;
      y += dir;
      x -= 2;
    }
  }

  function writeFormat(m, mask) {
    const bits = (bch(mask, 0x537) | (mask << 10)) ^ 0x5412;
    const n = m.length;
    const fmt = [[8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5], [8, 7], [8, 8], [7, 8], [5, 8], [4, 8], [3, 8], [2, 8], [1, 8], [0, 8]];
    for (let i = 0; i < 15; i++) {
      const bit = (bits >> i) & 1;
      m[fmt[i][1]][fmt[i][0]] = bit;
      if (i < 8) m[8][n - 1 - i] = bit;
      else m[n - 15 + i][8] = bit;
    }
  }

  function writeVersion(m, version) {
    if (version < 7) return;
    const bits = (version << 12) | bch(version, 0x1f25);
    const n = m.length;
    for (let i = 0; i < 18; i++) {
      const bit = (bits >> i) & 1;
      const a = Math.floor(i / 3);
      const b = i % 3;
      m[n - 11 + b][a] = bit;
      m[a][n - 11 + b] = bit;
    }
  }

  function penalty(m) {
    const n = m.length;
    let p = 0;
    const runScore = (run) => { if (run >= 5) p += 3 + (run - 5); };
    for (let y = 0; y < n; y++) {
      let run = 1;
      for (let x = 1; x < n; x++) {
        if (m[y][x] === m[y][x - 1]) run++;
        else { runScore(run); run = 1; }
      }
      runScore(run);
    }
    for (let x = 0; x < n; x++) {
      let run = 1;
      for (let y = 1; y < n; y++) {
        if (m[y][x] === m[y - 1][x]) run++;
        else { runScore(run); run = 1; }
      }
      runScore(run);
    }
    for (let y = 0; y < n - 1; y++) {
      for (let x = 0; x < n - 1; x++) {
        const v = m[y][x];
        if (v === m[y][x + 1] && v === m[y + 1][x] && v === m[y + 1][x + 1]) p += 3;
      }
    }
    const finderLike = (get) => {
      for (let i = 0; i + 6 < n; i++) {
        if (get(i) === 1 && get(i + 1) === 0 && get(i + 2) === 1 && get(i + 3) === 1 &&
            get(i + 4) === 1 && get(i + 5) === 0 && get(i + 6) === 1) {
          const white = (from, to) => {
            if (to - from < 4) return false;
            for (let k = from; k < to; k++) if (k >= 0 && k < n && get(k) === 1) return false;
            return true;
          };
          if (white(i - 4, i) || white(i + 7, i + 11)) p += 40;
        }
      }
    };
    for (let y = 0; y < n; y++) finderLike((i) => m[y][i]);
    for (let x = 0; x < n; x++) finderLike((i) => m[i][x]);
    let dark = 0;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) dark += m[y][x];
    p += Math.floor(Math.abs((dark * 100) / (n * n) - 50) / 5) * 10;
    return p;
  }

  function encode(text) {
    const payload = new TextEncoder().encode(text);
    let chosen = null;
    for (const row of VERSIONS) {
      const [version, , groups] = row;
      const ccBits = version <= 9 ? 8 : 16;
      if (4 + ccBits + payload.length * 8 <= dataBytes(groups) * 8) { chosen = row; break; }
    }
    if (!chosen) throw new Error("qr too long");
    const [version, ecPer, groups, alignPos] = chosen;
    const codewords = interleave(encodeBytes(payload, version, groups), ecPer, groups);
    const base = functions(version, alignPos);
    let best = null;
    let bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      const m = clone(base);
      placeData(m, codewords, mask);
      writeFormat(m, mask);
      writeVersion(m, version);
      for (const row of m) if (row.includes(null)) throw new Error("qr gap");
      const score = penalty(m);
      if (score < bestScore) { bestScore = score; best = m; }
    }
    return best;
  }

  function toSvg(text) {
    const m = encode(text);
    const q = 4;
    const n = m.length + q * 2;
    let d = "";
    for (let y = 0; y < m.length; y++) {
      let x = 0;
      while (x < m.length) {
        if (!m[y][x]) { x++; continue; }
        const x0 = x;
        while (x < m.length && m[y][x]) x++;
        d += "M" + (x0 + q) + " " + (y + q) + "h" + (x - x0) + "v1h-" + (x - x0) + "z";
      }
    }
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + n + " " + n +
      '" shape-rendering="crispEdges" role="img"><title>登录二维码</title><rect width="' + n +
      '" height="' + n + '" fill="#fff"/><path fill="#111" d="' + d + '"/></svg>';
  }

  return { encode, toSvg };
})();
