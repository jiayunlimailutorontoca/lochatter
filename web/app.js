/* lochatter web client: text + images. The phone seals a one-time session into this page; nothing is stored. */
const LOCKED = "🔒 无法解密的消息";
const $ = (id) => document.getElementById(id);

const state = {
  server: location.origin,
  token: "",
  me: 0,
  meName: "",
  peerName: "",
  ring: null,
  keys: new Map(),
  messages: [],
  ws: null,
};

function wipeStoredLogin() {
  try { sessionStorage.removeItem("lochatter"); } catch { /* private mode */ }
  try { localStorage.removeItem("lochatter"); } catch { /* private mode */ }
  document.cookie = "chatter=; Path=/; Max-Age=0; Secure; SameSite=Strict";
}
wipeStoredLogin();

let wantSocket = false;
let loginGen = 0;
let loginPriv = null;
let loginSpki = null;

function setCookie() {
  document.cookie = "chatter=" + state.token + "; Path=/; Secure; SameSite=Strict";
}
function clearCookie() {
  document.cookie = "chatter=; Path=/; Max-Age=0; Secure; SameSite=Strict";
}

async function api(path, opt = {}) {
  const headers = Object.assign({ Authorization: "Bearer " + state.token }, opt.headers || {});
  const res = await fetch(state.server + path, Object.assign({}, opt, { headers }));
  if (!res.ok) throw new Error((await res.text()) || res.statusText);
  const type = res.headers.get("content-type") || "";
  return type.includes("json") ? res.json() : res.arrayBuffer();
}

function myEpoch() {
  const list = state.ring && state.ring.epochs || [];
  return list.reduce((m, e) => Math.max(m, e.n || 0), 0);
}
function peerEpoch() {
  return (state.ring && state.ring.peer && state.ring.peer.highest) || 0;
}
function epochByN(n) {
  return (state.ring.epochs || []).find((e) => e.n === n) || null;
}
function peerPub(n) {
  const pubs = (state.ring.peer && state.ring.peer.pubs) || {};
  if (pubs[n] || pubs[String(n)]) return pubs[n] || pubs[String(n)];
  const retired = state.ring.retired || [];
  for (let i = retired.length - 1; i >= 0; i--) {
    const p = (retired[i].peer && retired[i].peer.pubs) || {};
    if (p[n] || p[String(n)]) return p[n] || p[String(n)];
  }
  return null;
}

async function sessionKey(senderUid, senderEpoch, receiverEpoch) {
  if (!state.ring) return null;
  const iAmSender = senderUid === state.me || senderUid === 0 && senderEpoch === 0 && receiverEpoch === 0;
  const id = senderEpoch + "." + receiverEpoch + "." + (senderUid === state.me);
  if (state.keys.has(id)) return state.keys.get(id);
  let key = null;
  if (senderEpoch === 0 && receiverEpoch === 0) {
    if (state.ring.identityPriv && state.ring.peerIdentityPub && state.ring.identityPub) {
      key = await E2E.derive(state.ring.identityPriv, state.ring.peerIdentityPub, state.ring.identityPub, "lochatter-e2e-v1");
    }
  } else if (senderEpoch > 0 && receiverEpoch > 0) {
    const mine = epochByN(iAmSender ? senderEpoch : receiverEpoch);
    const other = peerPub(iAmSender ? receiverEpoch : senderEpoch);
    if (mine && other) {
      key = await E2E.derive(mine.priv, other, mine.pub, "lochatter-e2e-v2|" + senderEpoch + "." + receiverEpoch);
    }
  }
  if (key) state.keys.set(id, key);
  return key;
}

async function currentSend() {
  const m = myEpoch();
  const p = peerEpoch();
  if (m > 0 && p > 0) {
    const key = await sessionKey(state.me, m, p);
    if (key) return { key, v2: { uid: state.me, s: m, r: p } };
  }
  const key = await sessionKey(0, 0, 0);
  return key ? { key, v2: null } : null;
}

async function decryptText(text, aad) {
  if (text == null || !E2E.isEncrypted(text)) return text;
  const h = E2E.header(text);
  if (!h) return LOCKED;
  const key = h.v2 ? await sessionKey(h.uid, h.s, h.r) : await sessionKey(0, 0, 0);
  if (!key) return LOCKED;
  try { return await E2E.open(key, E2E.b64dec(h.body), aad); }
  catch { return LOCKED; }
}

function upsert(m) {
  const i = state.messages.findIndex((x) => x.id === m.id);
  if (i >= 0) state.messages[i] = m;
  else state.messages.push(m);
  state.messages.sort((a, b) => (a.seq ?? 9e15) - (b.seq ?? 9e15) || a.ts - b.ts);
}

async function apply(m) {
  if (m.kind === "del") {
    state.messages = state.messages.filter((x) => x.id !== m.text);
    return;
  }
  if (m.kind === "recall" && m.text) {
    const t = state.messages.find((x) => x.id === m.text);
    if (t) { t.kind = "recall"; t.text = null; t.media = null; }
    return;
  }
  if (m.kind === "clear") {
    const upto = Number(m.text);
    state.messages = state.messages.filter((x) => x.seq == null || x.seq > upto);
    return;
  }
  if (m.kind === "edit" && m.text) {
    const bar = m.text.indexOf("|");
    if (bar > 0) {
      const id = m.text.slice(0, bar);
      const body = await decryptText(m.text.slice(bar + 1), id);
      const t = state.messages.find((x) => x.id === id);
      if (t && t.from === m.from) t.text = body === LOCKED ? t.text : body;
    }
    return;
  }
  if (m.kind === "react") return;
  const copy = Object.assign({}, m);
  copy.text = await decryptText(m.text, m.id);
  if (copy.media && copy.media.name && E2E.isEncrypted(copy.media.name)) {
    const name = await decryptText(copy.media.name, "name");
    copy.media = Object.assign({}, copy.media, { name: name === LOCKED ? "加密文件" : name });
  }
  upsert(copy);
}

function preview(m) {
  if (!m) return "";
  if (m.kind === "image" || m.kind === "album") return m.text ? "[图片] " + m.text : "[图片]";
  if (m.kind === "audio") return "[语音]";
  if (m.kind === "video") return "[视频]";
  if (m.kind === "file") return "[文件] " + ((m.media && m.media.name) || "");
  if (m.kind === "sticker") return "[表情]";
  if (m.kind === "location") return "[位置]";
  if (m.kind === "call") return "[通话]";
  if (m.kind === "recall") return "撤回了一条消息";
  if (m.kind === "ttl") return "消息定时销毁";
  return m.text || "";
}

async function render() {
  const box = $("log");
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  box.innerHTML = "";
  for (const m of state.messages) {
    if (m.kind === "del" || m.kind === "react" || m.kind === "edit" || m.kind === "clear") continue;
    const row = document.createElement("div");
    row.className = "row " + (m.from === state.me ? "me" : "them");
    if (m.kind === "image" || m.kind === "album") {
      const img = document.createElement("img");
      img.alt = "图片";
      img.className = "shot";
      row.appendChild(img);
      const id = (m.media && (m.media.thumbId || m.media.id)) || "";
      if (id) loadImage(id, m.media.mime || "image/jpeg").then((url) => { img.src = url; }).catch(() => { img.replaceWith(textNode("图片打不开")); });
      if (m.text) row.appendChild(textNode(m.text));
    } else if (m.kind === "text" || m.kind === "card") {
      row.appendChild(textNode(m.text || ""));
    } else {
      row.classList.add("sys");
      row.appendChild(textNode(preview(m)));
    }
    box.appendChild(row);
  }
  if (atBottom) box.scrollTop = box.scrollHeight;
}
function textNode(s) {
  const p = document.createElement("p");
  p.textContent = s;
  return p;
}

const imageCache = new Map();
async function loadImage(id, mime) {
  if (imageCache.has(id)) return imageCache.get(id);
  const buf = new Uint8Array(await api("/media/" + id));
  const magic = String.fromCharCode(buf[0], buf[1], buf[2], buf[3]);
  const bytes = magic === "LCE1" || magic === "LCE2"
    ? await E2E.decryptBytes(buf, (uid, s, r) => sessionKey(uid, s, r))
    : buf;
  const url = URL.createObjectURL(new Blob([bytes], { type: mime || "image/jpeg" }));
  imageCache.set(id, url);
  return url;
}

function connect() {
  if (state.ws) {
    const old = state.ws;
    state.ws = null;
    old.onclose = null;
    try { old.close(); } catch { /* already closing */ }
  }
  wantSocket = true;
  setCookie();
  const ws = new WebSocket(state.server.replace(/^http/, "ws") + "/ws");
  state.ws = ws;
  $("status").textContent = "正在连接…";
  ws.onopen = () => { $("status").textContent = "已连接"; };
  ws.onclose = () => {
    if (!wantSocket || state.ws !== ws) return;
    $("status").textContent = "连接断了，正在重连…";
    setTimeout(() => { if (wantSocket && state.token) connect(); }, 1500);
  };
  ws.onerror = () => { $("status").textContent = "连接失败"; };
  ws.onmessage = async (ev) => {
    let f;
    try { f = JSON.parse(ev.data); } catch { return; }
    if (f.t === "ping") { ws.send(JSON.stringify({ t: "pong", ts: f.ts })); return; }
    if (f.t === "hello") {
      if (f.peer) state.peerName = f.peer.name || state.peerName;
      $("who").textContent = (state.peerName || "对方") + " · " + (state.meName || "");
      if (f.peer && f.peer.pubKey) await adoptPeer(f.peer.pubKey);
      ws.send(JSON.stringify({ t: "active", fg: true }));
      ws.send(JSON.stringify({ t: "sync", since: 0, limit: 200 }));
      return;
    }
    if (f.t === "msg.new") { await apply(f.msg); render(); return; }
    if (f.t === "msg.ack") {
      const m = state.messages.find((x) => x.id === f.id);
      if (m) { m.seq = f.seq; m.ts = f.ts; }
      render();
      return;
    }
    if (f.t === "msg.batch") {
      for (const m of f.messages || []) await apply(m);
      render();
      if (!f.before && f.hasMore) {
        const last = (f.messages || []).reduce((n, m) => Math.max(n, m.seq || 0), 0);
        ws.send(JSON.stringify({ t: "sync", since: last, limit: 200 }));
      } else if (!f.before) {
        const max = state.messages.reduce((n, m) => Math.max(n, m.seq || 0), 0);
        if (max > 0) ws.send(JSON.stringify({ t: "read", upto: max }));
      }
      return;
    }
    if (f.t === "shared") return;
    if (f.t === "error") $("status").textContent = f.message || f.code;
  };
}

async function adoptPeer(bundleText) {
  if (!state.ring || !bundleText) return;
  const parts = bundleText.split("|");
  if (parts[0] !== "v2" || parts.length < 5) {
    if (!bundleText.includes("|") && !state.ring.peerIdentityPub) state.ring.peerIdentityPub = bundleText;
    return;
  }
  const identity = parts[1];
  if (state.ring.peerIdentityPub && state.ring.peerIdentityPub !== identity) return;
  state.ring.peerIdentityPub = identity;
  state.ring.peer = state.ring.peer || { highest: 0, pubs: {} };
  for (let i = 2; i + 2 < parts.length; i += 3) {
    const n = Number(parts[i]);
    const pub = parts[i + 1];
    const sig = parts[i + 2];
    if (!(await E2E.verifyEpoch(identity, n, pub, sig))) continue;
    state.ring.peer.pubs[String(n)] = pub;
    if (n > (state.ring.peer.highest || 0)) state.ring.peer.highest = n;
  }
  state.keys.clear();
}

async function sendText() {
  const input = $("composer");
  const text = input.value.trim();
  if (!text) return;
  const send = await currentSend();
  if (!send) { $("status").textContent = "这一页没有密钥。刷新后用手机重新扫码。"; return; }
  const id = crypto.randomUUID();
  const cipher = "e2e" + (send.v2
    ? "2:" + send.v2.uid + ":" + send.v2.s + "." + send.v2.r + ":" + E2E.b64enc(await E2E.seal(send.key, text, id))
    : ":" + E2E.b64enc(await E2E.seal(send.key, text, id)));
  upsert({ id, seq: null, from: state.me, kind: "text", text, ts: Date.now() });
  render();
  input.value = "";
  state.ws.send(JSON.stringify({ t: "msg.send", id, kind: "text", text: cipher, notice: text.slice(0, 200) }));
}

async function fileToJpeg(file, maxEdge, quality) {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, maxEdge / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * scale));
  const h = Math.max(1, Math.round(bmp.height * scale));
  const canvas = document.createElement("canvas");
  canvas.width = w; canvas.height = h;
  canvas.getContext("2d").drawImage(bmp, 0, 0, w, h);
  const blob = await new Promise((ok) => canvas.toBlob(ok, "image/jpeg", quality));
  return { bytes: new Uint8Array(await blob.arrayBuffer()), w, h };
}

async function sendImage(file) {
  const send = await currentSend();
  if (!send) { $("status").textContent = "这一页没有密钥。刷新后用手机重新扫码。"; return; }
  $("status").textContent = "正在发送图片…";
  const full = await fileToJpeg(file, 1600, 0.86);
  const thumb = await fileToJpeg(file, 360, 0.7);
  const encFull = await E2E.encryptBytes(send.key, full.bytes, send.v2);
  const encThumb = await E2E.encryptBytes(send.key, thumb.bytes, send.v2);
  const t = await api("/media?w=" + thumb.w + "&h=" + thumb.h, {
    method: "POST", headers: { "Content-Type": "image/jpeg" }, body: encThumb,
  });
  const f = await api("/media?w=" + full.w + "&h=" + full.h + "&thumb=" + encodeURIComponent(t.id), {
    method: "POST", headers: { "Content-Type": "image/jpeg" }, body: encFull,
  });
  const id = crypto.randomUUID();
  upsert({ id, seq: null, from: state.me, kind: "image", text: null, media: { id: f.id, mime: "image/jpeg", size: full.bytes.length, width: full.w, height: full.h, thumbId: t.id }, ts: Date.now() });
  render();
  state.ws.send(JSON.stringify({ t: "msg.send", id, kind: "image", mediaId: f.id, notice: "[图片]" }));
  $("status").textContent = "已连接";
}

function sleep(ms) { return new Promise((ok) => setTimeout(ok, ms)); }

function applySealedAccount(p) {
  state.token = p.token;
  state.me = p.userId;
  state.meName = p.userName || "";
  state.peerName = p.peerName || "";
  state.ring = p.keyRing ? JSON.parse(p.keyRing) : null;
  if (!state.ring && p.e2ePriv && p.e2ePub) {
    state.ring = { myUserId: p.userId, identityPriv: p.e2ePriv, identityPub: p.e2ePub, peerIdentityPub: p.peerPub || null, epochs: [], peer: { highest: 0, pubs: {} }, retired: [] };
  }
  if (state.ring && !state.ring.peerIdentityPub && p.peerPub) state.ring.peerIdentityPub = p.peerPub;
  if (state.ring && !state.ring.myUserId) state.ring.myUserId = p.userId;
  state.keys.clear();
}

async function acceptBox(gen, id, box) {
  $("loginStatus").textContent = "手机已确认，正在进入…";
  const json = await E2E.openWebLogin(loginPriv, loginSpki, id, box);
  if (gen !== loginGen) return;
  loginPriv = null;
  loginSpki = null;
  let p = null;
  try { p = json ? JSON.parse(json) : null; } catch { p = null; }
  if (!p || !p.token || !p.userId) {
    $("loginStatus").textContent = "登录失败";
    $("loginErr").textContent = "解不开手机送来的内容。点刷新后再扫一次。";
    return;
  }
  applySealedAccount(p);
  showChat();
}

async function pollTicket(gen, id, expiresAt) {
  while (gen === loginGen) {
    if (expiresAt && Date.now() > expiresAt) {
      $("loginStatus").textContent = "二维码已过期，正在更换…";
      startLogin();
      return;
    }
    try {
      const res = await fetch(state.server + "/auth/web-ticket/" + encodeURIComponent(id));
      if (gen !== loginGen) return;
      if (!res.ok) throw new Error((await res.text()) || "查询失败");
      const body = await res.json();
      if (body.status === "pending") {
        $("loginStatus").textContent = "等待手机确认";
        $("loginErr").textContent = "";
      } else if (body.status === "ready" && body.box) {
        await acceptBox(gen, id, body.box);
        return;
      } else {
        $("loginStatus").textContent = "二维码已失效，正在更换…";
        startLogin();
        return;
      }
    } catch (e) {
      if (gen !== loginGen) return;
      $("loginErr").textContent = e.message || "查询失败";
    }
    await sleep(1000);
  }
}

async function startLogin() {
  const gen = ++loginGen;
  loginPriv = null;
  loginSpki = null;
  $("loginErr").textContent = "";
  $("loginStatus").textContent = "正在生成二维码…";
  $("qr").textContent = "";
  try {
    const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    if (gen !== loginGen) return;
    const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
    if (spki.length !== 91) throw new Error("公钥长度不对");
    const res = await fetch(state.server + "/auth/web-ticket", { method: "POST" });
    if (gen !== loginGen) return;
    if (res.status === 429) throw new Error("刷新太频繁，等一会儿再试");
    if (!res.ok) throw new Error((await res.text()) || "取二维码失败");
    const body = await res.json();
    if (!body.id || String(body.id).length !== 22) throw new Error("票据格式不对");
    loginPriv = pair.privateKey;
    loginSpki = spki;
    const text = "lochatter-web:" + body.id + "." + E2E.b64enc(spki);
    $("qr").innerHTML = QR.toSvg(text);
    $("loginStatus").textContent = "请用已登录的手机扫描";
    pollTicket(gen, body.id, body.expiresAt);
  } catch (e) {
    if (gen !== loginGen) return;
    $("loginStatus").textContent = "二维码没有生成";
    $("loginErr").textContent = e.message || "失败";
  }
}

function showChat() {
  $("login").hidden = true;
  $("chat").hidden = false;
  $("who").textContent = (state.peerName || "对方") + " · " + (state.meName || "");
  connect();
}

function bind() {
  $("refreshBtn").onclick = () => startLogin();
  $("sendBtn").onclick = sendText;
  $("composer").onkeydown = (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendText(); } };
  $("file").onchange = () => { const f = $("file").files[0]; $("file").value = ""; if (f) sendImage(f).catch((e) => { $("status").textContent = e.message || "图片发送失败"; }); };
  $("logout").onclick = () => {
    wantSocket = false;
    loginGen++;
    loginPriv = null;
    loginSpki = null;
    state.token = "";
    state.ring = null;
    state.keys.clear();
    clearCookie();
    wipeStoredLogin();
    if (state.ws) state.ws.close();
    location.reload();
  };
  $("pushBtn").onclick = async () => {
    const box = $("pushBox");
    box.hidden = !box.hidden;
    if (box.hidden) return;
    $("pushErr").textContent = "";
    try {
      const pref = await api("/push");
      $("pushProvider").value = pref.provider || "off";
      $("pushSecret").value = pref.secret || "";
      $("pushEvery").value = String(pref.intervalSec ?? 60);
      $("pushStyle").value = pref.style || "text";
    } catch (e) { $("pushErr").textContent = e.message || "读取失败"; }
  };
  $("pushBox").onsubmit = async (ev) => {
    ev.preventDefault();
    $("pushErr").textContent = "";
    const intervalSec = Number($("pushEvery").value);
    try {
      await api("/push", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          provider: $("pushProvider").value,
          secret: $("pushSecret").value.trim(),
          intervalSec: Number.isFinite(intervalSec) ? intervalSec : 60,
          style: $("pushStyle").value || "text",
        }),
      });
      $("pushErr").textContent = "已保存";
    } catch (e) { $("pushErr").textContent = e.message || "保存失败"; }
  };
}

bind();
startLogin();
