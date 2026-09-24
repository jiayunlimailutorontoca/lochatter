/* lochatter e2e, the same shapes as android/.../crypto/E2E.kt (v1 "e2e:" / LCE1 and v2 "e2e2:" / LCE2). */
const E2E = (() => {
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const CHUNK = 1 << 20;

  function b64enc(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }
  function b64dec(text) {
    const clean = text.trim().replace(/-/g, "+").replace(/_/g, "/");
    const pad = clean + "=".repeat((4 - (clean.length % 4)) % 4);
    const bin = atob(pad);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function b64url(bytes) {
    return b64enc(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function concat(...parts) {
    let n = 0;
    for (const p of parts) n += p.length;
    const out = new Uint8Array(n);
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  }
  function u32(v) {
    return new Uint8Array([(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]);
  }
  function u64(v) {
    const hi = Math.floor(v / 4294967296);
    const lo = v >>> 0;
    return concat(u32(hi), u32(lo));
  }
  function readU32(b, o) {
    return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0;
  }
  function readU64(b, o) {
    return readU32(b, o) * 4294967296 + readU32(b, o + 4);
  }
  function cmp(a, b) {
    const n = Math.min(a.length, b.length);
    for (let i = 0; i < n; i++) if (a[i] !== b[i]) return a[i] - b[i];
    return a.length - b.length;
  }
  function sortedPubs(aB64, bB64) {
    const x = b64dec(aB64);
    const y = b64dec(bB64);
    return cmp(x, y) <= 0 ? concat(x, y) : concat(y, x);
  }

  async function sha256(bytes) {
    return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  }
  async function hkdf(ikm, salt, info) {
    const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
    return new Uint8Array(await crypto.subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt, info }, key, 256));
  }
  async function agree(privB64, pubB64) {
    const priv = await crypto.subtle.importKey("pkcs8", b64dec(privB64), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    const pub = await crypto.subtle.importKey("spki", b64dec(pubB64), { name: "ECDH", namedCurve: "P-256" }, false, []);
    return new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pub }, priv, 256));
  }
  async function derive(privB64, peerPubB64, myPubB64, info) {
    const secret = await agree(privB64, peerPubB64);
    const salt = await sha256(sortedPubs(myPubB64, peerPubB64));
    return hkdf(secret, salt, enc.encode(info));
  }
  async function aes(raw) {
    return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
  }
  async function seal(keyBytes, plain, aad) {
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const key = await aes(keyBytes);
    const ct = new Uint8Array(await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce, additionalData: enc.encode(aad) }, key, enc.encode(plain)));
    return concat(nonce, ct);
  }
  async function open(keyBytes, raw, aad) {
    const key = await aes(keyBytes);
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: raw.slice(0, 12), additionalData: enc.encode(aad) }, key, raw.slice(12));
    return dec.decode(pt);
  }

  function header(text) {
    if (text == null) return null;
    if (text.startsWith("e2e2:")) {
      const uidEnd = text.indexOf(":", 5);
      if (uidEnd < 0) return null;
      const end = text.indexOf(":", uidEnd + 1);
      if (end < 0) return null;
      const dot = text.indexOf(".", uidEnd + 1);
      if (dot < 0 || dot > end) return null;
      const uidStr = text.slice(5, uidEnd);
      const sStr = text.slice(uidEnd + 1, dot);
      const rStr = text.slice(dot + 1, end);
      if (!/^\d{1,20}$/.test(uidStr) || !/^\d{1,9}$/.test(sStr) || !/^\d{1,9}$/.test(rStr)) return null;
      return { uid: Number(uidStr), s: Number(sStr), r: Number(rStr), body: text.slice(end + 1), v2: true };
    }
    if (text.startsWith("e2e:")) return { v2: false, body: text.slice(4) };
    return null;
  }
  function isEncrypted(text) {
    return typeof text === "string" && (text.startsWith("e2e:") || text.startsWith("e2e2:"));
  }

  function nonceFor(base, index) {
    const n = base.slice();
    n[8] ^= (index >>> 24) & 255;
    n[9] ^= (index >>> 16) & 255;
    n[10] ^= (index >>> 8) & 255;
    n[11] ^= index & 255;
    return n;
  }
  function aadFor(index, last) {
    return concat(u32(index), new Uint8Array([last ? 1 : 0]));
  }
  async function gcmBytes(keyBytes, nonce, aad, data, encrypting) {
    const key = await aes(keyBytes);
    const op = encrypting ? crypto.subtle.encrypt : crypto.subtle.decrypt;
    return new Uint8Array(await op.call(crypto.subtle, { name: "AES-GCM", iv: nonce, additionalData: aad }, key, data));
  }

  async function encryptBytes(keyBytes, plain, v2) {
    const parts = [];
    if (v2) {
      parts.push(new Uint8Array([0x4c, 0x43, 0x45, 0x32]));
      parts.push(u64(v2.uid), u32(v2.s), u32(v2.r));
    } else {
      parts.push(new Uint8Array([0x4c, 0x43, 0x45, 0x31]));
    }
    const base = crypto.getRandomValues(new Uint8Array(12));
    parts.push(base);
    let index = 0;
    for (let off = 0; ;) {
      const end = Math.min(off + CHUNK, plain.length);
      const last = end >= plain.length;
      const ct = await gcmBytes(keyBytes, nonceFor(base, index), aadFor(index, last), plain.subarray(off, end), true);
      parts.push(u32(ct.length), ct);
      if (last) break;
      off = end;
      index++;
    }
    return concat(...parts);
  }

  async function decryptBytes(blob, keyFor) {
    if (blob.length < 16) throw new Error("short blob");
    const magic = String.fromCharCode(blob[0], blob[1], blob[2], blob[3]);
    let o = 4;
    let key;
    if (magic === "LCE1") key = await keyFor(0, 0, 0);
    else if (magic === "LCE2") {
      if (blob.length < 20) throw new Error("short v2");
      const uid = readU64(blob, 4);
      const s = readU32(blob, 12);
      const r = readU32(blob, 16);
      o = 20;
      key = await keyFor(uid, s, r);
    } else throw new Error("not encrypted");
    if (!key) throw new Error("no key");
    const base = blob.slice(o, o + 12);
    o += 12;
    const out = [];
    let index = 0;
    while (o + 4 <= blob.length) {
      const len = readU32(blob, o);
      o += 4;
      if (len < 16 || len > CHUNK + 16 || o + len > blob.length) throw new Error("bad chunk");
      const ct = blob.slice(o, o + len);
      o += len;
      const short = len - 16 < CHUNK;
      let plain = null;
      let last = short;
      try { plain = await gcmBytes(key, nonceFor(base, index), aadFor(index, short), ct, false); }
      catch { last = !short; plain = await gcmBytes(key, nonceFor(base, index), aadFor(index, last), ct, false); }
      out.push(plain);
      index++;
      if (last) break;
    }
    return concat(...out);
  }

  function derToP1363(der) {
    if (!der || der[0] !== 0x30 || der.length < 8) return null;
    let i = 2;
    if (der[1] & 0x80) return null;
    if (der[i++] !== 0x02) return null;
    const rlen = der[i++];
    let r = der.slice(i, i + rlen); i += rlen;
    if (der[i++] !== 0x02) return null;
    const slen = der[i++];
    let s = der.slice(i, i + slen);
    const trim = (b) => { let k = 0; while (k < b.length - 1 && b[k] === 0) k++; return b.slice(k); };
    r = trim(r); s = trim(s);
    if (r.length > 32 || s.length > 32) return null;
    const out = new Uint8Array(64);
    out.set(r, 32 - r.length);
    out.set(s, 64 - s.length);
    return out;
  }

  async function verifyEpoch(identityPub, epoch, epochPub, sigB64) {
    try {
      const pub = await crypto.subtle.importKey("spki", b64dec(identityPub), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      const raw = derToP1363(b64dec(sigB64));
      if (!raw) return false;
      const data = enc.encode("lochatter-epoch|" + epoch + "|" + epochPub);
      return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pub, raw, data);
    } catch { return false; }
  }

  /** PBKDF2-HMAC-SHA256, 200000 rounds, AES-GCM. Same as crypto/Migration.kt. */
  async function unpackMigration(text, pin) {
    const t = (text || "").trim();
    if (!t.startsWith("lochatter1:")) return null;
    try {
      const raw = b64dec(t.slice("lochatter1:".length));
      if (raw.length < 16 + 12 + 16) return null;
      const base = await crypto.subtle.importKey("raw", enc.encode(pin.trim()), "PBKDF2", false, ["deriveKey"]);
      const key = await crypto.subtle.deriveKey(
        { name: "PBKDF2", salt: raw.slice(0, 16), iterations: 200000, hash: "SHA-256" },
        base, { name: "AES-GCM", length: 256 }, false, ["decrypt"]);
      const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(16, 28) }, key, raw.slice(28));
      return dec.decode(pt);
    } catch { return null; }
  }

  /**
   * Opens a webpage-login box sealed by the phone.
   * privKey is the in-memory CryptoKey; mySpki is the 91-byte SPKI that was printed in the QR.
   */
  async function openWebLogin(privKey, mySpki, ticketId, boxB64) {
    try {
      const raw = b64dec(boxB64);
      if (raw.length < 91 + 12 + 16) return null;
      const eph = raw.slice(0, 91);
      const body = raw.slice(91);
      const ephKey = await crypto.subtle.importKey("spki", eph, { name: "ECDH", namedCurve: "P-256" }, false, []);
      const secret = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: ephKey }, privKey, 256));
      const salt = await sha256(concat(eph, mySpki));
      const key = await hkdf(secret, salt, enc.encode("lochatter-web-login-v1"));
      return await open(key, body, ticketId);
    } catch {
      return null;
    }
  }

  return {
    b64enc, b64dec, b64url, concat, isEncrypted, header, derive, seal, open, encryptBytes, decryptBytes,
    verifyEpoch, unpackMigration, openWebLogin,
  };
})();
