/* lochatter web prototype: text + images, keys from the phone's migration QR. */
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

function saveSession() {
  sessionStorage.setItem("lochatter", JSON.stringify({
    token: state.token, me: state.me, meName: state.meName, peerName: state.peerName, ring: state.ring,
  }));
}
function loadSession() {
  const raw = sessionStorage.getItem("lochatter");
  if (!raw) return;
  try {
    const s = JSON.parse(raw);
    state.token = s.token || "";
    state.me = s.me || 0;
    state.meName = s.meName || "";
    state.peerName = s.peerName || "";
    state.ring = s.ring || null;
  } catch { /* ignore a broken tab session */ }
}

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
  if (state.ws) state.ws.close();
  setCookie();
  const ws = new WebSocket(state.server.replace(/^http/, "ws") + "/ws");
  state.ws = ws;
  $("status").textContent = "正在连接…";
  ws.onopen = () => { $("status").textContent = "已连接"; };
  ws.onclose = () => { $("status").textContent = "连接断了，刷新页面重连"; };
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
  saveSession();
}

async function sendText() {
  const input = $("composer");
  const text = input.value.trim();
  if (!text) return;
  const send = await currentSend();
  if (!send) { $("status").textContent = "还没有密钥。用手机上的换机二维码扫进来。"; return; }
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
  if (!send) { $("status").textContent = "还没有密钥。用手机上的换机二维码扫进来。"; return; }
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

async function loginPassword(ev) {
  ev.preventDefault();
  $("loginErr").textContent = "";
  try {
    const res = await fetch(state.server + "/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: $("name").value.trim(), password: $("password").value, device: "web" }),
    });
    if (!res.ok) throw new Error((await res.text()) || "登录失败");
    const body = await res.json();
    state.token = body.token;
    state.me = body.user.id;
    state.meName = body.user.name;
    state.peerName = body.peer ? body.peer.name : "";
    state.ring = null;
    state.keys.clear();
    saveSession();
    showChat();
  } catch (e) { $("loginErr").textContent = e.message || "登录失败"; }
}

async function importQr(payloadText, pin) {
  $("loginErr").textContent = "";
  const json = await E2E.unpackMigration(payloadText, pin);
  if (!json) { $("loginErr").textContent = "二维码或 PIN 不对"; return; }
  let p;
  try { p = JSON.parse(json); } catch { $("loginErr").textContent = "这不是换机码"; return; }
  if (!p.token || !p.userId) { $("loginErr").textContent = "换机码里没有账号"; return; }
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
  saveSession();
  showChat();
}

function showChat() {
  $("login").hidden = true;
  $("chat").hidden = false;
  $("who").textContent = (state.peerName || "对方") + " · " + (state.meName || "");
  connect();
}

async function scanLoop(video, detector) {
  if (video.dataset.stop) return;
  try {
    const codes = await detector.detect(video);
    if (codes && codes[0] && codes[0].rawValue && codes[0].rawValue.startsWith("lochatter1:")) {
      $("qrtext").value = codes[0].rawValue;
      $("loginErr").textContent = "扫到了。填手机上的 6 位数字，再点导入。";
      return;
    }
  } catch { /* frame not ready */ }
  requestAnimationFrame(() => scanLoop(video, detector));
}

function bind() {
  $("loginForm").onsubmit = loginPassword;
  $("importBtn").onclick = () => importQr($("qrtext").value, $("pin").value);
  $("sendBtn").onclick = sendText;
  $("composer").onkeydown = (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendText(); } };
  $("file").onchange = () => { const f = $("file").files[0]; $("file").value = ""; if (f) sendImage(f).catch((e) => { $("status").textContent = e.message || "图片发送失败"; }); };
  $("logout").onclick = () => { clearCookie(); sessionStorage.removeItem("lochatter"); if (state.ws) state.ws.close(); location.reload(); };
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
  $("scanBtn").onclick = async () => {
    if (!("BarcodeDetector" in window)) { $("loginErr").textContent = "这个浏览器不能扫码。把换机码内容贴到下面的框里。"; return; }
    const video = $("cam");
    video.hidden = false;
    video.dataset.stop = "";
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
    video.srcObject = stream;
    await video.play();
    scanLoop(video, new BarcodeDetector({ formats: ["qr_code"] }));
  };
}

loadSession();
bind();
if (state.token && state.me) showChat();
