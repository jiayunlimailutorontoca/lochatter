"""End-to-end test of the chatter server with two accounts.

usage: python tools/e2e.py <server> <userA> <passA> <userB> <passB> [botToken]
   eg: python tools/e2e.py https://chat.example.com alice secretA bob secretB

Exercises: login, bearer-auth websocket, hello, presence, text send/ack/new,
duplicate resend, sync paging, read marks, typing, media upload/download, bad token,
1.5: location + live-location edits, orphaned-upload cleanup, per-connection rate limits,
call.media screen flag, 4096-char keys, POST /bot/card.

The orphan check expects the server to run with CHATTER_MEDIA_GRACE_SECONDS=2 and CHATTER_SWEEP_SECONDS=1
(see tools/check.sh); against a production server it would report the upload as still present.
"""
import asyncio, hashlib, json, sys, time, uuid, urllib.request, urllib.error, urllib.parse
import websockets

SERVER, A, PA, B, PB = sys.argv[1:6]
BOT_TOKEN = (sys.argv[6] if len(sys.argv) > 6 else "") or __import__("os").environ.get("BOT_TOKEN", "")
WS = SERVER.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
FAILS = []


def check(cond, what):
    print(("  ok   " if cond else "  FAIL ") + what)
    if not cond:
        FAILS.append(what)


def http(method, path, body=None, token=None, ctype="application/json", raw=False):
    data = body if raw else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(SERVER + path, data=data, method=method)
    req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            payload = r.read()
            return r.status, (payload if raw else json.loads(payload or b"null")), dict(r.headers)
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload), dict(e.headers)
        except Exception:
            return e.code, payload, dict(e.headers)


def login(name, pw):
    st, body, _ = http("POST", "/auth/login", {"name": name, "password": pw, "device": "e2e.py"})
    check(st == 200 and "token" in body, f"login {name} -> {st}")
    return body


async def recv(ws, want=None, timeout=5):
    """Receive frames until one with t==want (or any frame if want is None)."""
    end = time.time() + timeout
    while True:
        left = end - time.time()
        if left <= 0:
            return None
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=left)
        except asyncio.TimeoutError:
            return None
        m = json.loads(raw)
        if want is None or m.get("t") == want:
            return m


async def recv_new(ws, mid, timeout=5):
    """Receive msg.new frames until the one carrying message id `mid` (skips frames another check left undrained)."""
    end = time.time() + timeout
    while True:
        m = await recv(ws, "msg.new", timeout=max(0.0, end - time.time()))
        if m is None or m["msg"]["id"] == mid:
            return m


async def main():
    print("== auth")
    st, body, _ = http("POST", "/auth/login", {"name": A, "password": "definitely-wrong"})
    check(st == 401, f"wrong password -> {st}")
    la, lb = login(A, PA), login(B, PB)
    ta, tb = la["token"], lb["token"]
    ida, idb = la["user"]["id"], lb["user"]["id"]
    check(la.get("peer", {}).get("id") == idb, "A's peer is B")

    print("== websocket auth")
    try:
        async with websockets.connect(WS, additional_headers={"Authorization": "Bearer nope-nope-nope-nope-nope-nope-nope"}) as ws:
            await ws.recv()
        check(False, "bad token rejected")
    except websockets.InvalidStatus as e:
        check(e.response.status_code == 401, f"bad token rejected with {e.response.status_code}")

    async with websockets.connect(WS, additional_headers={"Authorization": "Bearer " + ta}) as wa:
        ha = await recv(wa, "hello")
        check(ha and ha["user"]["id"] == ida, "A hello")
        check(ha["peer"]["id"] == idb and ha["peer"]["online"] is False, "A sees B offline")
        await wa.send(json.dumps({"t": "active", "fg": True}))
        await asyncio.sleep(0.3)  # let the server apply A's foreground state before B's hello is built (loopback is fast)
        last_seq = ha["lastSeq"]

        async with websockets.connect(WS, additional_headers={"Authorization": "Bearer " + tb}) as wb:
            hb = await recv(wb, "hello")
            check(hb and hb["peer"]["online"] is True, "B sees A online (A is foreground)")
            pres = await recv(wa, "presence", timeout=1.5)
            check(pres is None, "B connected but backgrounded -> no presence for A")
            await wb.send(json.dumps({"t": "active", "fg": True}))
            pres = await recv(wa, "presence")
            check(pres and pres["user"] == idb and pres["online"] is True, "A gets presence(B online) when B comes to foreground")
            await wb.send(json.dumps({"t": "active", "fg": False}))
            pres = await recv(wa, "presence")
            check(pres and pres["online"] is False and pres.get("lastSeen"), "B to background -> A gets presence(offline, lastSeen)")
            await wb.send(json.dumps({"t": "active", "fg": True}))
            await recv(wa, "presence")

            print("== messaging")
            mid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": mid, "kind": "text", "text": "你好 B 👋"}))
            ack = await recv(wa, "msg.ack")
            check(ack and ack["id"] == mid and ack["seq"] == last_seq + 1, f"A ack seq={ack and ack.get('seq')}")
            new = await recv(wb, "msg.new")
            check(new and new["msg"]["id"] == mid and new["msg"]["text"] == "你好 B 👋" and new["msg"]["from"] == ida, "B receives msg.new")
            seq1 = ack["seq"]

            # duplicate resend: same id -> same seq, no second msg.new
            await wa.send(json.dumps({"t": "msg.send", "id": mid, "kind": "text", "text": "你好 B 👋"}))
            ack2 = await recv(wa, "msg.ack")
            check(ack2 and ack2["seq"] == seq1, "duplicate resend acks same seq")
            dup = await recv(wb, "msg.new", timeout=1.5)
            check(dup is None, "duplicate resend not re-broadcast")

            # validation
            await wa.send(json.dumps({"t": "msg.send", "id": mid + "x", "kind": "text", "text": "   "}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request" and err.get("ref") == mid + "x", "blank text rejected with ref")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "image", "mediaId": "0" * 64}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "unknown mediaId rejected")

            # read + typing
            await wb.send(json.dumps({"t": "read", "upto": seq1}))
            rm = await recv(wa, "read")
            check(rm and rm["user"] == idb and rm["upto"] == seq1, "A gets B's read mark")
            await wb.send(json.dumps({"t": "typing"}))
            ty = await recv(wa, "typing")
            check(ty and ty["user"] == idb, "A gets B typing")

            # B replies, A syncs from before
            for i in range(3):
                await wb.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": f"reply {i}"}))
                await recv(wb, "msg.ack")
            for _ in range(3):
                await recv(wa, "msg.new")
            await wa.send(json.dumps({"t": "sync", "since": last_seq, "limit": 2}))
            b1 = await recv(wa, "msg.batch")
            check(b1 and len(b1["messages"]) == 2 and b1["hasMore"] is True, "sync page 1 (limit 2, hasMore)")
            await wa.send(json.dumps({"t": "sync", "since": b1["messages"][-1]["seq"]}))
            b2 = await recv(wa, "msg.batch")
            check(b2 and len(b2["messages"]) == 2 and b2["hasMore"] is False, "sync page 2 (rest, no more)")
            check([m["seq"] for m in b1["messages"] + b2["messages"]] == list(range(last_seq + 1, last_seq + 5)), "sync seqs contiguous")
            await wa.send(json.dumps({"t": "sync", "since": 0, "before": last_seq + 5, "limit": 2}))
            b3 = await recv(wa, "msg.batch")
            check(b3 and b3.get("before") == last_seq + 5 and len(b3["messages"]) == 2 and b3["hasMore"] is True, "history page (before, limit 2, hasMore)")
            check([m["seq"] for m in b3["messages"]] == [last_seq + 3, last_seq + 4], "history page is the two newest older than before")
            await wa.send(json.dumps({"t": "sync", "since": 0, "before": b3["messages"][0]["seq"], "limit": 10}))
            b4 = await recv(wa, "msg.batch")
            check(b4 and b4["hasMore"] is False and [m["seq"] for m in b4["messages"]] == [last_seq + 1, last_seq + 2], "history rest, no more")

            print("== media")
            blob = b"\xff\xd8\xff" + bytes(range(256)) * 40  # fake jpeg-ish payload
            sha = hashlib.sha256(blob).hexdigest()
            st, info, _ = http("POST", "/media?w=640&h=480", blob, token=ta, ctype="image/jpeg", raw=True)
            info = json.loads(info) if isinstance(info, (bytes, bytearray)) else info
            check(st == 201 and info["id"] == sha and info["width"] == 640 and info["size"] == len(blob), f"upload -> {st} id=sha256")
            st2, info2, _ = http("POST", "/media", blob, token=ta, ctype="image/jpeg", raw=True)
            check(st2 == 201, "re-upload same bytes is idempotent")
            st, _, _ = http("POST", "/media", b"x", token=ta, ctype="not a mime", raw=True)
            check(st == 415, f"malformed mime -> {st}")
            doc = bytes(range(256)) * 300
            st, finfo, _ = http("POST", "/media?name=" + urllib.parse.quote("../报告 v2.pdf"), doc, token=ta, ctype="application/pdf", raw=True)
            finfo = json.loads(finfo) if isinstance(finfo, (bytes, bytearray)) else finfo
            check(st == 201 and finfo["name"] == "报告 v2.pdf" and finfo["mime"] == "application/pdf" and finfo["size"] == len(doc), f"file upload keeps clean name: {finfo.get('name')!r}")
            fid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": fid, "kind": "file", "mediaId": finfo["id"]}))
            ack = await recv(wa, "msg.ack")
            new = await recv(wb, "msg.new")
            check(ack and new and new["msg"]["kind"] == "file" and new["msg"]["media"]["name"] == "报告 v2.pdf", "file message carries name")
            st, got, hdr = http("GET", f"/media/{finfo['id']}?dl=1", token=tb, raw=True)
            check(st == 200 and got == doc and "attachment" in hdr.get("Content-Disposition", ""), "file download with attachment disposition")
            st, got, hdr = http("GET", f"/media/{sha}", token=tb, raw=True)
            check(st == 200 and got == blob and hdr.get("Content-Type", "").startswith("image/jpeg"), "B downloads A's upload")
            st, _, _ = http("GET", f"/media/{sha}", raw=True)
            check(st == 401, f"download without token -> {st}")
            st, _, _ = http("GET", "/media/" + "a" * 64, token=ta, raw=True)
            check(st == 404, f"unknown media -> {st}")

            mid2 = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": mid2, "kind": "image", "mediaId": sha, "text": "图"}))
            ack = await recv(wa, "msg.ack")
            new = await recv(wb, "msg.new")
            check(ack and new and new["msg"]["media"]["id"] == sha and new["msg"]["media"]["width"] == 640, "image message carries media info")

            print("== reply / delete / clear")
            orig = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": orig, "kind": "text", "text": "原文：明天几点？"}))
            await recv(wa, "msg.ack"); await recv(wb, "msg.new")
            rep = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": rep, "kind": "text", "text": "九点", "replyTo": orig}))
            rack = await recv(wb, "msg.ack")
            rnew = await recv(wa, "msg.new")
            check(rnew and rnew["msg"]["reply"]["id"] == orig and rnew["msg"]["reply"]["from"] == ida and rnew["msg"]["reply"]["text"] == "原文：明天几点？", "reply carries server-side quote snapshot")
            await wb.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": "x", "replyTo": "no-such-message-id"}))
            await recv(wb, "msg.ack"); ghost = await recv(wa, "msg.new")
            check(ghost and ghost["msg"].get("reply") is None, "reply to unknown id stored as plain message")
            # A deletes B's reply for both
            did = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": did, "kind": "del", "text": rep}))
            dack = await recv(wa, "msg.ack")
            dnew = await recv(wb, "msg.new")
            check(dack and dnew and dnew["msg"]["kind"] == "del" and dnew["msg"]["text"] == rep, "peer receives del control entry")
            await wa.send(json.dumps({"t": "sync", "since": rack["seq"] - 1}))
            sb = await recv(wa, "msg.batch")
            ids = [m["id"] for m in sb["messages"]]
            check(rep not in ids and did in ids, "sync no longer returns the deleted message but does return the del entry")
            await wa.send(json.dumps({"t": "msg.send", "id": did, "kind": "del", "text": rep}))
            dack2 = await recv(wa, "msg.ack")
            check(dack2 and dack2["seq"] == dack["seq"], "resent del is idempotent")
            # clear everything for both
            cid_clear = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": cid_clear, "kind": "clear"}))
            cack = await recv(wa, "msg.ack")
            cnew = await recv(wb, "msg.new")
            check(cack and cnew and cnew["msg"]["kind"] == "clear" and cnew["msg"]["text"] == str(cack["seq"] - 1), "peer receives clear with last wiped seq")
            await wb.send(json.dumps({"t": "sync", "since": 0}))
            sb = await recv(wb, "msg.batch")
            check(sb and [m["kind"] for m in sb["messages"]] == ["clear"], "after clear, sync from 0 returns only the clear entry")
            st, _, _ = http("GET", f"/media/{sha}", token=tb, raw=True)
            check(st == 404, "media of cleared messages is gone")
            last_seq = cack["seq"]


            print("== call signaling")
            cid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "call.invite", "callId": cid, "video": False}))
            inv = await recv(wb, "call.invite")
            check(inv and inv["callId"] == cid and inv["from"] == ida and inv["video"] is False and inv.get("ts"), "B gets invite stamped from=A")
            await wb.send(json.dumps({"t": "call.accept", "callId": cid}))
            acc = await recv(wa, "call.accept")
            check(acc and acc["callId"] == cid and acc["from"] == idb, "A gets accept")
            await wa.send(json.dumps({"t": "call.sdp", "callId": cid, "type": "offer", "sdp": "v=0 o=- 1 1 IN IP4 0.0.0.0"}))
            sdp = await recv(wb, "call.sdp")
            check(sdp and sdp["type"] == "offer" and sdp["sdp"].startswith("v=0") and sdp["from"] == ida, "B gets SDP offer")
            await wb.send(json.dumps({"t": "call.ice", "callId": cid, "candidate": "candidate:1 1 udp 2113937151 192.168.1.2 5000 typ host", "sdpMid": "0", "sdpMLineIndex": 0}))
            ice = await recv(wa, "call.ice")
            check(ice and ice["sdpMLineIndex"] == 0 and ice["sdpMid"] == "0" and ice["from"] == idb, "A gets ICE candidate")
            await wa.send(json.dumps({"t": "call.sdp", "callId": cid, "type": "bogus", "sdp": "x"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "bad sdp type rejected")
            await wa.send(json.dumps({"t": "call.hangup", "callId": cid, "reason": "normal"}))
            hu = await recv(wb, "call.hangup")
            check(hu and hu["reason"] == "normal" and hu["from"] == ida, "B gets hangup")

            await wa.send(json.dumps({"t": "turn.get"}))
            tc = await recv(wa, "turn.creds")
            check(tc and any(u.startswith("turn:") for u in tc["urls"]) and tc["username"].endswith(f":{ida}") and tc["credential"] and tc["ttlSeconds"] > 0,
                  f"turn creds issued: {tc and tc['urls']}")

            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "call", "text": "voice:answered:42"}))
            ack = await recv(wa, "msg.ack")
            new = await recv(wb, "msg.new")
            check(ack and new and new["msg"]["kind"] == "call" and new["msg"]["text"] == "voice:answered:42", "call log message relayed")

            print("== video / react / call.media")
            st, vinfo, _ = http("POST", "/media?w=640&h=360&d=4000", blob, token=ta, ctype="video/mp4", raw=True)
            vinfo = json.loads(vinfo) if isinstance(vinfo, (bytes, bytearray)) else vinfo
            vid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": vid, "kind": "video", "mediaId": vinfo["id"]}))
            ack = await recv(wa, "msg.ack")
            new = await recv(wb, "msg.new")
            check(ack and new and new["msg"]["kind"] == "video" and new["msg"]["media"]["durationMs"] == 4000, "video message carries duration")
            rid = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": rid, "kind": "react", "text": vid + "|❤️|1"}))
            rack = await recv(wb, "msg.ack")
            rnew = await recv(wa, "msg.new")
            check(rack and rnew and rnew["msg"]["kind"] == "react" and rnew["msg"]["text"] == vid + "|❤️|1" and rnew["msg"]["from"] == idb, "reaction relayed as control entry")
            await wb.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "react", "text": "malformed"}))
            err = await recv(wb, "error")
            check(err and err["code"] == "bad_request", "malformed reaction rejected")
            await wa.send(json.dumps({"t": "call.media", "callId": "c-media", "video": False}))
            cm = await recv(wb, "call.media")
            check(cm and cm["callId"] == "c-media" and cm["video"] is False and cm["audio"] is True and cm["from"] == ida, "call.media relayed with defaults")
            check(cm and "screen" not in cm, "call.media without screen has no screen key")
            await wa.send(json.dumps({"t": "call.media", "callId": "c-media", "video": True, "screen": True}))
            cm2 = await recv(wb, "call.media")
            check(cm2 and cm2.get("screen") is True and cm2["video"] is True and cm2["from"] == ida, "call.media screen flag relayed to the peer")

            print("== 1.3: sticker / pat / once / edit squash / shared / battery / call.emoji")
            check(isinstance(ha.get("shared"), list), "human hello carries shared snapshot")
            check("ttl" in ha.get("bot", {}), "hello bot info carries ttl")
            # 1.6: hello advertises the optional helpers; the local test server has none configured
            feats = ha.get("features")
            check(isinstance(feats, dict) and all(isinstance(feats.get(k), bool) for k in ("stt", "geo", "tiles")), f"hello carries features {feats}")
            mp = b"--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.m4a\"\r\n\r\nzz\r\n--x--\r\n"
            st, sbody, _ = http("POST", "/stt", body=mp, token=ta, ctype="multipart/form-data; boundary=x", raw=True)
            check(st == 503 and sbody.get("code") == "stt_unavailable", f"/stt without a backend is 503 stt_unavailable ({st} {sbody})")
            gs, gbody, _ = http("GET", "/geo/regeo?lat=31.2304&lng=121.4737", token=ta)
            check(gs == 503 and gbody.get("code") == "geo_unavailable", f"/geo/regeo without a key is 503 geo_unavailable ({gs} {gbody})")
            gs, gbody, _ = http("GET", "/geo/regeo?lat=91&lng=0", token=ta)
            check(gs == 400, f"/geo/regeo with lat=91 is 400 ({gs})")
            gs, _, _ = http("GET", "/geo/search?q=&lat=1&lng=1", token=ta)
            check(gs in (400, 503), f"/geo/search without q is rejected ({gs})")
            gs, _, _ = http("GET", "/geo/regeo?lat=1&lng=1")
            check(gs == 401, f"/geo/regeo without a token is 401 ({gs})")
            # sticker, library form (no mediaId)
            skid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": skid, "kind": "sticker", "text": "bqb|media/001.jpg|240|240"}))
            sack = await recv(wa, "msg.ack"); snew = await recv(wb, "msg.new")
            check(sack and snew and snew["msg"]["kind"] == "sticker" and snew["msg"]["text"].startswith("bqb|") and snew["msg"].get("media") is None, "library sticker relayed")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "sticker", "text": "bqb|media/001.jpg|240|240", "mediaId": "0" * 64}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "library sticker with mediaId rejected")
            # sticker, custom form (mediaId required and must exist)
            sblob = b"\x89PNG\r\n\x1a\n" + bytes(range(256)) * 12
            st, sinfo, _ = http("POST", "/media?w=128&h=128", sblob, token=ta, ctype="image/png", raw=True)
            sinfo = json.loads(sinfo) if isinstance(sinfo, (bytes, bytearray)) else sinfo
            sid = sinfo["id"]
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "sticker", "text": f"media|{sid}|128|128"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "custom sticker without mediaId rejected")
            cskid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": cskid, "kind": "sticker", "text": f"media|{sid}|128|128", "mediaId": sid}))
            sack = await recv(wa, "msg.ack"); snew = await recv(wb, "msg.new")
            check(sack and snew and snew["msg"]["kind"] == "sticker" and snew["msg"]["media"]["id"] == sid, "custom sticker relayed with media info")
            await wb.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": "哈哈", "replyTo": cskid}))
            await recv(wb, "msg.ack"); sq = await recv(wa, "msg.new")
            check(sq and sq["msg"]["reply"]["text"] == "[表情]", "sticker quote preview is [表情]")
            # pat: humans only, optional text
            pid = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": pid, "kind": "pat"}))
            pack = await recv(wb, "msg.ack"); pnew = await recv(wa, "msg.new")
            check(pack and pnew and pnew["msg"]["kind"] == "pat" and pnew["msg"]["from"] == idb, "pat relayed")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "pat", "to": "bot"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "pat cannot be sent to the bot")
            # card is assistant-only
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "card", "text": "天气\n今天晴"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "card from a human rejected")
            # once: echoed on image, ignored on text
            oid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": oid, "kind": "image", "mediaId": sid, "once": True}))
            oack = await recv(wa, "msg.ack"); onew = await recv(wb, "msg.new")
            check(oack and onew and onew["msg"]["kind"] == "image" and onew["msg"].get("once") is True, "once echoed on an image")
            await wa.send(json.dumps({"t": "sync", "since": oack["seq"] - 1, "limit": 1}))
            osb = await recv(wa, "msg.batch")
            check(osb and osb["messages"][0]["id"] == oid and osb["messages"][0].get("once") is True, "once persisted and returned by sync")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": "not once", "once": True}))
            await recv(wa, "msg.ack"); tnew = await recv(wb, "msg.new")
            check(tnew and "once" not in tnew["msg"], "once ignored on text")
            # edit squash: two edits of the same message leave one edit row
            sqid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": sqid, "kind": "text", "text": "v0"}))
            sqack = await recv(wa, "msg.ack"); await recv(wb, "msg.new")
            e1 = str(uuid.uuid4()); e2 = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": e1, "kind": "edit", "text": sqid + "|v1"}))
            await recv(wa, "msg.ack"); await recv(wb, "msg.new")
            await wa.send(json.dumps({"t": "msg.send", "id": e2, "kind": "edit", "text": sqid + "|v2"}))
            await recv(wa, "msg.ack"); await recv(wb, "msg.new")
            await wa.send(json.dumps({"t": "sync", "since": sqack["seq"] - 1}))
            esb = await recv(wa, "msg.batch")
            edits = [m for m in esb["messages"] if m["kind"] == "edit" and m["text"].startswith(sqid + "|")]
            base = next((m for m in esb["messages"] if m["id"] == sqid), None)
            check(len(edits) == 1 and edits[0]["id"] == e2 and edits[0]["text"] == sqid + "|v2", f"edit squash keeps only the latest edit row ({len(edits)} row(s))")
            check(base and base["text"] == "v2" and base.get("editedAt"), "edited message carries the latest text")
            # shared key/value: PUT broadcasts to both humans, GET returns it
            st, si, _ = http("PUT", "/shared/quick", {"value": json.dumps(["/new", "/stop"])}, token=ta)
            sfa = await recv(wa, "shared"); sfb = await recv(wb, "shared")
            check(st == 200 and si["key"] == "quick" and si.get("updatedAt") and sfa and sfb and sfa["key"] == "quick" and sfb["value"] == si["value"], "PUT /shared broadcasts a shared frame to both humans")
            st, sl, _ = http("GET", "/shared", token=tb)
            check(st == 200 and any(x["key"] == "quick" and x["value"] == si["value"] for x in sl), "GET /shared returns the value")
            st, _, _ = http("PUT", "/shared/Bad%20Key", {"value": "x"}, token=ta)
            check(st == 400, f"bad shared key -> {st}")
            st, _, _ = http("PUT", "/shared/big", {"value": "x" * 65537}, token=ta)
            check(st == 400, f"oversized shared value -> {st}")
            # battery report -> presence with battery for the peer
            await wb.send(json.dumps({"t": "active", "fg": True, "battery": 83, "charging": False}))
            bp = await recv(wa, "presence")
            check(bp and bp["user"] == idb and bp["online"] is True and bp.get("battery") == 83 and bp.get("charging") is False, "battery report produces presence with battery on the peer")
            await wb.send(json.dumps({"t": "active", "fg": True, "battery": 83, "charging": False}))
            bp2 = await recv(wa, "presence", timeout=1.5)
            check(bp2 is None, "unchanged battery report produces no presence")
            # call.emoji relayed with from
            await wa.send(json.dumps({"t": "call.emoji", "callId": "c-emo", "emoji": "❤️"}))
            cemo = await recv(wb, "call.emoji")
            check(cemo and cemo["callId"] == "c-emo" and cemo["emoji"] == "❤️" and cemo["from"] == ida, "call.emoji relayed with from")
            await wa.send(json.dumps({"t": "call.emoji", "callId": "c-emo", "emoji": "x" * 17}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "oversized call.emoji rejected")

            print("== keys / edit / ttl")
            st, ki, _ = http("POST", "/keys", {"pubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest"}, token=ta)
            kf = await recv(wb, "keys")
            check(st == 200 and ki["userId"] == ida and kf and kf["user"] == ida and kf["pubKey"].endswith("test"), "key published and broadcast")
            st, kg, _ = http("GET", f"/keys/{ida}", token=tb)
            check(st == 200 and kg["pubKey"].endswith("test"), "peer fetches key")
            st, _, _ = http("GET", f"/keys/{idb}", token=ta)
            check(st == 404, "no key -> 404")
            bigkey = "A" * 600  # 1.5: signed epoch-key chain, well over the old 512 limit
            st, kb, _ = http("POST", "/keys", {"pubKey": bigkey}, token=ta)
            kfb = await recv(wb, "keys")
            check(st == 200 and kb["pubKey"] == bigkey and kfb and kfb["pubKey"] == bigkey, "600-char pubKey accepted and broadcast")
            st, _, _ = http("POST", "/keys", {"pubKey": "A" * 4097}, token=ta)
            check(st == 400, f"4097-char pubKey -> {st}")
            eid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": eid, "kind": "text", "text": "e2e:AAAAfake"}))
            eack = await recv(wa, "msg.ack"); enew = await recv(wb, "msg.new")
            check(eack and enew and enew["msg"]["text"] == "e2e:AAAAfake", "encrypted blob stored verbatim")
            rq = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": rq, "kind": "text", "text": "q", "replyTo": eid}))
            await recv(wb, "msg.ack"); rqn = await recv(wa, "msg.new")
            check(rqn and rqn["msg"]["reply"]["text"] == "e2e:AAAAfake", "quote of encrypted text is not clipped")
            edit_id = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": edit_id, "kind": "edit", "text": eid + "|e2e:BBBBnew"}))
            edack = await recv(wa, "msg.ack"); ednew = await recv(wb, "msg.new")
            check(edack and ednew and ednew["msg"]["kind"] == "edit" and ednew["msg"]["text"].startswith(eid + "|"), "edit relayed")
            await wa.send(json.dumps({"t": "sync", "since": eack["seq"] - 1, "limit": 1}))
            sb = await recv(wa, "msg.batch")
            check(sb and sb["messages"][0]["id"] == eid and sb["messages"][0]["text"] == "e2e:BBBBnew" and sb["messages"][0].get("editedAt"), "edited text persisted with editedAt")
            await wb.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "edit", "text": eid + "|hijack"}))
            err = await recv(wb, "error")
            check(err and err["code"] == "not_found", "peer cannot edit my message")
            tid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": tid, "kind": "ttl", "text": "1"}))
            tack = await recv(wa, "msg.ack"); tnew = await recv(wb, "msg.new")
            check(tack and tnew and tnew["msg"]["kind"] == "ttl" and tnew["msg"]["text"] == "1", "ttl set to 1s and relayed")
            xid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": xid, "kind": "text", "text": "gone soon"}))
            xack = await recv(wa, "msg.ack"); xnew = await recv(wb, "msg.new")
            check(xack and xnew and xnew["msg"].get("expiresAt") and xnew["msg"]["expiresAt"] - xnew["msg"]["ts"] == 1000, "message carries expiresAt = ts + ttl")
            xdel = await recv(wb, "msg.new", timeout=40)
            while xdel and not (xdel["msg"]["kind"] == "del" and xdel["msg"]["text"] == xid):
                xdel = await recv(wb, "msg.new", timeout=40)
            check(xdel is not None, "expired message swept into a del entry within the sweep interval")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "ttl", "text": "0"}))
            await recv(wa, "msg.ack"); await recv(wb, "msg.new")

            print("== 1.5: location / live location edit / orphaned uploads")
            # an upload no message ever references: still there now, gone after the grace period (checked at the end of this section)
            oblob = b"ORPHAN" + bytes(range(256)) * 8
            st, oinfo, _ = http("POST", "/media", oblob, token=ta, ctype="application/octet-stream", raw=True)
            oinfo = json.loads(oinfo) if isinstance(oinfo, (bytes, bytearray)) else oinfo
            st2, _, _ = http("GET", f"/media/{oinfo['id']}", token=ta, raw=True)
            check(st == 201 and st2 == 200, "unreferenced upload is still there right after upload")
            orphan_t0 = time.time()
            # a referenced image with a thumbnail: both must survive the orphan sweep
            tblob = b"THUMB" + bytes(range(256)) * 4
            st, tinfo, _ = http("POST", "/media?w=64&h=64", tblob, token=ta, ctype="image/jpeg", raw=True)
            tinfo = json.loads(tinfo) if isinstance(tinfo, (bytes, bytearray)) else tinfo
            mblob = b"MAIN" + bytes(range(256)) * 16
            st, minfo, _ = http("POST", f"/media?w=640&h=640&thumb={tinfo['id']}", mblob, token=ta, ctype="image/jpeg", raw=True)
            minfo = json.loads(minfo) if isinstance(minfo, (bytes, bytearray)) else minfo
            kept_id = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": kept_id, "kind": "image", "mediaId": minfo["id"]}))
            await recv(wa, "msg.ack"); knew = await recv_new(wb, kept_id)
            check(knew and knew["msg"]["media"]["thumbId"] == tinfo["id"], "image with thumbnail sent (both must survive the orphan sweep)")

            # location: plaintext form lat,lng|accuracy|address|live
            loc_text = "31.2304,121.4737|12|上海市黄浦区人民广场|1"
            lid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": lid, "kind": "location", "text": loc_text}))
            lack = await recv(wa, "msg.ack"); lnew = await recv_new(wb, lid)
            check(lack and lnew and lnew["msg"]["kind"] == "location" and lnew["msg"]["text"] == loc_text and "expiresAt" not in lnew["msg"], "location relayed")
            lq = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": lq, "kind": "text", "text": "到了吗", "replyTo": lid}))
            await recv(wb, "msg.ack"); lqn = await recv_new(wa, lq)
            check(lqn and lqn["msg"]["reply"]["text"] == "[位置] 上海市黄浦区人民广场", f"location quote preview: {lqn and lqn['msg']['reply']['text']!r}")
            # live location: later positions edit the same bubble
            le = str(uuid.uuid4())
            live2 = "31.2310,121.4740|8|上海市黄浦区人民广场|0"
            await wa.send(json.dumps({"t": "msg.send", "id": le, "kind": "edit", "text": lid + "|" + live2}))
            leack = await recv(wa, "msg.ack"); lenew = await recv_new(wb, le)
            check(leack and lenew and lenew["msg"]["kind"] == "edit" and lenew["msg"]["text"] == lid + "|" + live2, "live location update relayed as an edit")
            await wa.send(json.dumps({"t": "sync", "since": lack["seq"] - 1, "limit": 1}))
            lsb = await recv(wa, "msg.batch")
            check(lsb and lsb["messages"][0]["id"] == lid and lsb["messages"][0]["text"] == live2 and lsb["messages"][0].get("editedAt"), "edited location persisted with editedAt")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "edit", "text": kept_id + "|nope"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "not_found", "edit of an image message still rejected")
            # encrypted location: stored verbatim, generic quote preview
            elid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": elid, "kind": "location", "text": "e2e:AAAAloc"}))
            await recv(wa, "msg.ack"); elnew = await recv_new(wb, elid)
            check(elnew and elnew["msg"]["kind"] == "location" and elnew["msg"]["text"] == "e2e:AAAAloc", "encrypted location stored verbatim")
            elq = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": elq, "kind": "text", "text": "?", "replyTo": elid}))
            await recv(wb, "msg.ack"); elqn = await recv_new(wa, elq)
            check(elqn and elqn["msg"]["reply"]["text"] == "[位置] 位置", "encrypted location quote preview is generic")
            # v2 (epoch-key) ciphertext "e2e2:<uid>:<se>.<re>:…" must pass the same checks as v1 (1.5.0 rejected it: locations could not be sent)
            v2lid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": v2lid, "kind": "location", "text": "e2e2:1:3.3:AAAAloc"}))
            v2ack = await recv(wa, "msg.ack"); v2new = await recv_new(wb, v2lid)
            check(v2ack and v2new and v2new["msg"]["kind"] == "location" and v2new["msg"]["text"] == "e2e2:1:3.3:AAAAloc", "v2-encrypted location accepted and stored verbatim")
            v2q = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": v2q, "kind": "text", "text": "?", "replyTo": v2lid}))
            await recv(wb, "msg.ack"); v2qn = await recv_new(wa, v2q)
            check(v2qn and v2qn["msg"]["reply"]["text"] == "[位置] 位置", "v2-encrypted location quote preview is generic")
            long_v2 = "e2e2:1:3.3:" + "Q" * 300
            v2tid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": v2tid, "kind": "text", "text": long_v2}))
            await recv(wa, "msg.ack"); await recv_new(wb, v2tid)
            v2tq = str(uuid.uuid4())
            await wb.send(json.dumps({"t": "msg.send", "id": v2tq, "kind": "text", "text": "?", "replyTo": v2tid}))
            await recv(wb, "msg.ack"); v2tqn = await recv_new(wa, v2tq)
            check(v2tqn and v2tqn["msg"]["reply"]["text"] == long_v2, "quote of v2-encrypted text is not clipped")
            v2rid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": v2rid, "kind": "react", "text": "e2e2:1:3.3:AAAAreact"}))
            v2rack = await recv(wa, "msg.ack")
            check(v2rack and v2rack.get("t") == "msg.ack", "v2-encrypted reaction accepted")
            v2sid = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": v2sid, "kind": "sticker", "text": "e2e2:1:3.3:AAAAsticker"}))
            v2sack = await recv(wa, "msg.ack")
            check(v2sack and v2sack.get("t") == "msg.ack", "v2-encrypted sticker accepted")
            # 1.7 撤回: own message within 2 minutes -> row becomes kind "recall", the peer gets a control message
            rc_id = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": rc_id, "kind": "text", "text": "oops"}))
            rc_a0 = await recv(wa, "msg.ack"); await recv_new(wb, rc_id)
            rc_ctl = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": rc_ctl, "kind": "recall", "text": rc_id}))
            rc_ack = await recv(wa, "msg.ack"); rc_new = await recv_new(wb, rc_ctl)
            check(rc_ack and rc_new and rc_new["msg"]["kind"] == "recall" and rc_new["msg"]["text"] == rc_id, "recall relayed as a control message")
            await wb.send(json.dumps({"t": "sync", "since": rc_a0["seq"] - 1}))
            rc_sync = await recv(wb, "msg.batch", timeout=8)
            rc_row = next((m for m in (rc_sync or {}).get("messages", []) if m["id"] == rc_id), None)
            check(rc_row is not None and rc_row["kind"] == "recall" and not rc_row.get("text"), f"recalled row synced as kind recall ({rc_row and rc_row['kind']})")
            await wb.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "recall", "text": rc_id}))
            rc_err = await recv(wb, "error")
            check(rc_err and rc_err["code"] == "bad_request", "recalling someone else's message is rejected")
            # validation
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "location", "text": "not a location"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "garbled plaintext location rejected")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "location", "text": "91,0|1|x|0"}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "out-of-range latitude rejected")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "location", "text": loc_text, "mediaId": minfo["id"]}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "location with mediaId rejected")
            await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "location", "text": "1,1|1|" + "x" * 1100}))
            err = await recv(wa, "error")
            check(err and err["code"] == "bad_request", "oversized location rejected")
            # location follows disappearing messages like every content kind
            ttl_l = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": ttl_l, "kind": "ttl", "text": "1"}))
            await recv(wa, "msg.ack"); await recv_new(wb, ttl_l)
            xl = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": xl, "kind": "location", "text": loc_text}))
            await recv(wa, "msg.ack"); xln = await recv_new(wb, xl)
            check(xln and xln["msg"].get("expiresAt") and xln["msg"]["expiresAt"] - xln["msg"]["ts"] == 1000, "location carries expiresAt when ttl is on")
            xldel = await recv_new(wb, "exp-" + xl, timeout=40)
            check(xldel is not None and xldel["msg"]["kind"] == "del" and xldel["msg"]["text"] == xl, "expired location swept into a del entry")
            ttl_off = str(uuid.uuid4())
            await wa.send(json.dumps({"t": "msg.send", "id": ttl_off, "kind": "ttl", "text": "0"}))
            await recv(wa, "msg.ack"); await recv_new(wb, ttl_off)

            # orphan sweep: grace 2 s + pass every 2 s + 1 s tick on the test server
            await asyncio.sleep(max(0.0, orphan_t0 + 6.5 - time.time()))
            st, _, _ = http("GET", f"/media/{oinfo['id']}", token=ta, raw=True)
            check(st == 404, f"unreferenced upload removed after the grace period -> {st}")
            st, _, _ = http("GET", f"/media/{minfo['id']}", token=ta, raw=True)
            st2, _, _ = http("GET", f"/media/{tinfo['id']}", token=ta, raw=True)
            check(st == 200 and st2 == 200, "referenced image and its thumbnail survive the orphan sweep")

            print("== assistant (bot)")
            st, bi, _ = http("GET", "/bot", token=ta)
            check(st == 200 and bi["id"] == 0 and bi["name"] and bi["online"] is False, f"bot info: {bi}")
            check(ha.get("bot", {}).get("id") == 0, "hello carries bot info")
            st, bn, _ = http("POST", "/bot/name", {"name": "小助"}, token=ta)
            bf = await recv(wb, "bot")
            check(st == 200 and bn["name"] == "小助" and bf and bf["name"] == "小助", "rename broadcast to the peer as a bot frame")
            await recv(wa, "bot")  # the renamer hears it too; drain so the next bot frame is the connect
            st, _, _ = http("POST", "/bot/name", {"name": B}, token=ta)
            check(st == 409, "cannot name the bot after a user")
            bot_token = BOT_TOKEN
            if not bot_token:
                check(False, "BOT_TOKEN env missing; skipping socket checks")
            else:
                async with websockets.connect(WS, additional_headers={"Authorization": "Bearer " + bot_token}) as wbot:
                    hbot = await recv(wbot, "hello")
                    check(hbot and hbot["user"]["id"] == 0 and hbot.get("peer") is None, "bot hello has no peer")
                    check(sorted(u["id"] for u in hbot.get("users", [])) == sorted([ida, idb]) and all(u.get("name") for u in hbot["users"]), "bot hello carries the humans' names")
                    check("shared" not in hbot, "bot hello has no shared snapshot")
                    online = await recv(wa, "bot")
                    check(online and online["online"] is True, "humans see bot online")
                    # e2e text: bot must not see it
                    await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": "e2e:secretsecret"}))
                    await recv(wa, "msg.ack"); await recv(wb, "msg.new")
                    leak = await recv(wbot, "msg.new", timeout=1.5)
                    check(leak is None, "encrypted message not forwarded to the bot")
                    # plain text without @: bot must not see it either
                    await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": "just us"}))
                    await recv(wa, "msg.ack"); await recv(wb, "msg.new")
                    leak = await recv(wbot, "msg.new", timeout=1.5)
                    check(leak is None, "plain message without @ not forwarded to the bot")
                    # @bot with e2e blob: rejected
                    await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "text", "text": "e2e:xx", "to": "bot"}))
                    err = await recv(wa, "error")
                    check(err and err["code"] == "bad_request", "encrypted @bot rejected")
                    # @bot mention: both peer and bot get it, with to=bot and no expiresAt
                    qid = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": qid, "kind": "text", "text": "今晚吃什么", "to": "bot"}))
                    qack = await recv(wa, "msg.ack")
                    qb = await recv(wb, "msg.new"); qbot = await recv(wbot, "msg.new")
                    check(qack and qb and qb["msg"]["to"] == "bot" and qbot and qbot["msg"]["id"] == qid and qbot["msg"]["text"] == "今晚吃什么", "@bot mention reaches peer and bot with to=bot")
                    # bot typing reaches humans
                    await wbot.send(json.dumps({"t": "typing"}))
                    ty = await recv(wa, "typing")
                    check(ty and ty["user"] == 0, "bot typing reaches humans with user=0")
                    # bot reply + streamed edit
                    rid = str(uuid.uuid4())
                    await wbot.send(json.dumps({"t": "msg.send", "id": rid, "kind": "text", "text": "火锅", "replyTo": qid}))
                    rack = await recv(wbot, "msg.ack")
                    ra = await recv(wa, "msg.new"); rb = await recv(wb, "msg.new")
                    check(rack and ra and rb and ra["msg"]["from"] == 0 and ra["msg"]["reply"]["id"] == qid, "bot reply reaches both humans, quoting the mention")
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "edit", "text": rid + "|火锅，或者烤肉"}))
                    await recv(wbot, "msg.ack")
                    ed = await recv(wa, "msg.new")
                    check(ed and ed["msg"]["kind"] == "edit" and ed["msg"]["text"].startswith(rid + "|"), "bot edit (streaming) relayed")
                    # bot may delete its own message but not a human's
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "del", "text": qid}))
                    err = await recv(wbot, "error")
                    check(err and err["code"] == "bad_request", "bot cannot delete a human's message")
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "del", "text": rid}))
                    dack = await recv(wbot, "msg.ack"); dnew = await recv(wa, "msg.new")
                    check(dack and dnew and dnew["msg"]["kind"] == "del" and dnew["msg"]["text"] == rid, "bot can delete its own message")
                    # bot cannot do human-only things
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "ttl", "text": "60"}))
                    err = await recv(wbot, "error")
                    check(err and err["code"] == "bad_request", "bot cannot set ttl")
                    await wbot.send(json.dumps({"t": "call.invite", "callId": "c-bot", "video": False}))
                    err = await recv(wbot, "error")
                    check(err and err["code"] == "unsupported", "bot cannot place calls")

                    print("== 2.0: assistant voice call")
                    await wa.send(json.dumps({"t": "call.invite", "callId": "c-vid", "video": True, "bot": True}))
                    rj = await recv(wa, "call.reject")
                    check(rj and rj["reason"] == "voice_only", "video call to the assistant is refused")
                    leak = await recv(wbot, "call.invite", timeout=0.4)
                    check(leak is None, "refused video invite is not delivered")
                    cid = "c-assistant"
                    await wa.send(json.dumps({"t": "call.invite", "callId": cid, "video": False, "bot": True}))
                    inv = await recv(wbot, "call.invite")
                    leak = await recv(wb, "call.invite", timeout=0.4)
                    check(inv and inv["callId"] == cid and inv.get("bot") is True and inv["from"] == ida and leak is None, "assistant invite reaches the bot only")
                    await wb.send(json.dumps({"t": "call.invite", "callId": "c-busy", "video": False, "bot": True}))
                    busy = await recv(wb, "call.reject")
                    check(busy and busy["reason"] == "busy", "second assistant call is busy")
                    await wbot.send(json.dumps({"t": "call.accept", "callId": cid}))
                    acc = await recv(wa, "call.accept")
                    leak = await recv(wb, "call.accept", timeout=0.4)
                    check(acc and acc["callId"] == cid and acc["from"] == 0 and leak is None, "assistant accept reaches the caller only")
                    await wbot.send(json.dumps({"t": "call.sdp", "callId": cid, "type": "answer", "sdp": "v=0 bot"}))
                    sdp = await recv(wa, "call.sdp")
                    leak = await recv(wb, "call.sdp", timeout=0.4)
                    check(sdp and sdp["sdp"] == "v=0 bot" and leak is None, "assistant sdp stays with the caller")
                    await wa.send(json.dumps({"t": "call.sdp", "callId": cid, "type": "offer", "sdp": "v=0 phone"}))
                    off = await recv(wbot, "call.sdp")
                    leak = await recv(wb, "call.sdp", timeout=0.4)
                    check(off and off["sdp"] == "v=0 phone" and leak is None, "caller sdp reaches the assistant only")
                    await wbot.send(json.dumps({"t": "call.caption", "callId": cid, "who": "assistant", "text": "我在", "state": "final", "phase": "speaking"}))
                    cap = await recv(wa, "call.caption")
                    leak = await recv(wb, "call.caption", timeout=0.4)
                    check(cap and cap["text"] == "我在" and cap["who"] == "assistant" and cap["state"] == "final" and cap["from"] == 0 and cap.get("phase") == "speaking" and leak is None, "caption reaches the caller only")
                    await wbot.send(json.dumps({"t": "call.caption", "callId": "stale-call", "who": "assistant", "text": "迟到", "state": "final"}))
                    leak = await recv(wa, "call.caption", timeout=0.4)
                    check(leak is None, "caption for an unknown call id is ignored")
                    await wb.send(json.dumps({"t": "call.caption", "callId": cid, "who": "user", "text": "旁听", "state": "final"}))
                    leak = await recv(wa, "call.caption", timeout=0.4)
                    leak2 = await recv(wbot, "call.caption", timeout=0.4)
                    check(leak is None and leak2 is None, "the other human cannot inject a caption")
                    await wbot.send(json.dumps({"t": "call.caption", "callId": cid, "who": "nope", "text": "x", "state": "final"}))
                    err = await recv(wbot, "error")
                    check(err and err["code"] == "bad_request", "bad caption speaker is rejected")
                    await wbot.send(json.dumps({"t": "turn.get"}))
                    tc = await recv(wbot, "turn.creds")
                    check(tc is not None and "urls" in tc, "assistant can request TURN credentials")
                    await wa.send(json.dumps({"t": "call.hangup", "callId": cid, "reason": "normal"}))
                    hu = await recv(wbot, "call.hangup")
                    leak = await recv(wb, "call.hangup", timeout=0.4)
                    check(hu and hu["callId"] == cid and leak is None, "hangup reaches the assistant only")
                    await wbot.send(json.dumps({"t": "call.caption", "callId": cid, "who": "assistant", "text": "结束后", "state": "final"}))
                    leak = await recv(wa, "call.caption", timeout=0.4)
                    check(leak is None, "caption after hangup is ignored")
                    # bot sync sees only its own + mentions (plus del / clear control entries so it can drop cached media)
                    await wbot.send(json.dumps({"t": "sync", "since": 0, "limit": 500}))
                    sbb = await recv(wbot, "msg.batch")
                    kinds = [(m["from"], m.get("to"), m.get("text")) for m in sbb["messages"]]
                    check(sbb and all(m["from"] == 0 or m.get("to") == "bot" or m["kind"] in ("del", "clear", "recall") for m in sbb["messages"]) and not any((m.get("text") or "").startswith("e2e:") for m in sbb["messages"]), f"bot sync filtered ({len(kinds)} rows)")
                    # bot upload + image message
                    st, binfo, _ = http("POST", "/media?w=8&h=8", blob, token=bot_token, ctype="image/jpeg", raw=True)
                    binfo = json.loads(binfo) if isinstance(binfo, (bytes, bytearray)) else binfo
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "image", "mediaId": binfo["id"], "text": "图"}))
                    await recv(wbot, "msg.ack")
                    im = await recv(wa, "msg.new")
                    check(st == 201 and im and im["msg"]["kind"] == "image" and im["msg"]["from"] == 0, "bot can upload and send an image")
                    # humans @ the bot with media: plaintext reaches it, client-encrypted (LCE1) is refused
                    st, pinfo, _ = http("POST", "/media?w=8&h=8", blob, token=ta, ctype="image/jpeg", raw=True)
                    pinfo = json.loads(pinfo) if isinstance(pinfo, (bytes, bytearray)) else pinfo
                    await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "image", "mediaId": pinfo["id"], "text": "看看这个", "to": "bot"}))
                    await recv(wa, "msg.ack"); await recv(wb, "msg.new")
                    pm = await recv(wbot, "msg.new")
                    check(pm and pm["msg"]["kind"] == "image" and pm["msg"]["to"] == "bot" and pm["msg"]["media"]["id"] == pinfo["id"], "plaintext image @bot reaches the bot")
                    st, einfo, _ = http("POST", "/media", b"LCE1" + blob, token=ta, ctype="image/jpeg", raw=True)
                    einfo = json.loads(einfo) if isinstance(einfo, (bytes, bytearray)) else einfo
                    await wa.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "image", "mediaId": einfo["id"], "to": "bot"}))
                    err = await recv(wa, "error")
                    check(err and err["code"] == "bad_request" and "unencrypted" in err["message"], "encrypted media @bot rejected")

                    print("== assistant 1.3: card / sticker / shared / ttl")
                    # card from the bot reaches humans; preview is the first line
                    card_id = str(uuid.uuid4())
                    await wbot.send(json.dumps({"t": "msg.send", "id": card_id, "kind": "card", "text": "今日天气\n晴，25°C"}))
                    cack = await recv(wbot, "msg.ack"); cnew = await recv_new(wa, card_id); await recv_new(wb, card_id)
                    check(cack and cnew and cnew["msg"]["kind"] == "card" and cnew["msg"]["from"] == 0 and cnew["msg"]["text"].startswith("今日天气"), "card from the bot reaches humans")
                    cr_id = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": cr_id, "kind": "text", "text": "谢谢", "replyTo": card_id}))
                    await recv(wa, "msg.ack"); cq = await recv_new(wb, cr_id)
                    check(cq and cq["msg"].get("reply", {}).get("text") == "今日天气", "card quote preview is its first line")
                    # bot cannot pat; sticker to the bot must be plaintext
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "pat"}))
                    err = await recv(wbot, "error")
                    check(err and err["code"] == "bad_request", "bot cannot pat")
                    bsk_id = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": bsk_id, "kind": "sticker", "text": "bqb|media/002.jpg|200|200", "to": "bot"}))
                    await recv(wa, "msg.ack"); await recv_new(wb, bsk_id); bsk = await recv_new(wbot, bsk_id)
                    check(bsk and bsk["msg"]["kind"] == "sticker" and bsk["msg"]["to"] == "bot", "plaintext sticker @bot reaches the bot")
                    # 1.5: a plaintext location @bot reaches the bot; the bot itself cannot share one
                    bloc_id = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": bloc_id, "kind": "location", "text": "31.2304,121.4737|12|上海市黄浦区人民广场|0", "to": "bot"}))
                    await recv(wa, "msg.ack"); await recv_new(wb, bloc_id); bloc = await recv_new(wbot, bloc_id)
                    check(bloc and bloc["msg"]["kind"] == "location" and bloc["msg"]["to"] == "bot" and bloc["msg"]["text"].startswith("31.2304,"), "plaintext location @bot reaches the bot")
                    await wbot.send(json.dumps({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "location", "text": "31.2,121.4|10|x|0"}))
                    err = await recv(wbot, "error")
                    check(err and err["code"] == "bad_request", "bot cannot send a location")
                    # 1.5: POST /bot/card (assistant token only) is stored and broadcast like a socket-sent card
                    st, cc, _ = http("POST", "/bot/card", {"text": "喝水提醒\n该喝水了"}, token=bot_token)
                    hca = hcb = None
                    if st == 200:
                        hca = await recv_new(wa, cc["id"]); hcb = await recv_new(wb, cc["id"]); await recv_new(wbot, cc["id"])  # the assistant's own socket hears it too
                    check(st == 200 and cc["id"].startswith("card-") and cc["seq"] > 0 and hca and hca["msg"]["kind"] == "card" and hca["msg"]["from"] == 0
                          and hca["msg"]["seq"] == cc["seq"] and hca["msg"]["text"].startswith("喝水提醒") and hcb and hcb["msg"]["id"] == cc["id"], f"POST /bot/card -> {st}, reaches both humans as a card")
                    st, _, _ = http("POST", "/bot/card", {"text": "x"}, token=ta)
                    check(st == 403, f"POST /bot/card with a human token -> {st}")
                    st, _, _ = http("POST", "/bot/card", {"text": "   "}, token=bot_token)
                    check(st == 400, f"POST /bot/card with blank text -> {st}")
                    # shared: bot may read, not write
                    st, _, _ = http("GET", "/shared", token=bot_token)
                    st2, _, _ = http("PUT", "/shared/quick", {"value": "[]"}, token=bot_token)
                    check(st == 200 and st2 == 403, f"bot reads shared ({st}) but cannot write ({st2})")
                    # bot_ttl: off -> @bot question has no expiresAt; on -> it expires like everything else, and the del reaches the bot
                    st, bt, _ = http("POST", "/bot/ttl", {"enabled": False}, token=ta)
                    bfa = await recv(wa, "bot"); await recv(wb, "bot")
                    check(st == 200 and bt["ttl"] is False and bfa and bfa.get("ttl") is False, "POST /bot/ttl false broadcast as a bot frame")
                    ttl1 = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": ttl1, "kind": "ttl", "text": "1"}))
                    await recv(wa, "msg.ack"); await recv_new(wb, ttl1)
                    q1 = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": q1, "kind": "text", "text": "不过期？", "to": "bot"}))
                    await recv(wa, "msg.ack"); q1b = await recv_new(wb, q1); await recv_new(wbot, q1)
                    check(q1b and q1b["msg"]["id"] == q1 and "expiresAt" not in q1b["msg"], "with bot ttl off an @bot question has no expiresAt")
                    st, bt, _ = http("POST", "/bot/ttl", {"enabled": True}, token=ta)
                    bfa = await recv(wa, "bot"); await recv(wb, "bot")
                    check(st == 200 and bt["ttl"] is True and bfa and bfa.get("ttl") is True, "POST /bot/ttl true broadcast as a bot frame")
                    q2 = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": q2, "kind": "text", "text": "会过期？", "to": "bot"}))
                    await recv(wa, "msg.ack"); q2b = await recv_new(wb, q2); await recv_new(wbot, q2)
                    check(q2b and q2b["msg"].get("expiresAt") and q2b["msg"]["expiresAt"] - q2b["msg"]["ts"] == 1000, "with bot ttl on an @bot question carries expiresAt")
                    ttl0 = str(uuid.uuid4())
                    await wa.send(json.dumps({"t": "msg.send", "id": ttl0, "kind": "ttl", "text": "0"}))
                    await recv(wa, "msg.ack"); await recv_new(wb, ttl0)
                    bdel = await recv_new(wbot, "exp-" + q2, timeout=40)
                    check(bdel is not None and bdel["msg"]["kind"] == "del" and bdel["msg"]["text"] == q2, "expiry del entry reaches the bot connection")
                    await wbot.send(json.dumps({"t": "sync", "since": 0, "limit": 500}))
                    sbb = await recv(wbot, "msg.batch")
                    check(sbb and any(m["kind"] == "del" and m["text"] == q2 for m in sbb["messages"]) and not any(m["id"] == q2 for m in sbb["messages"]), "bot sync passes del entries and drops the expired message")
                offline = await recv(wa, "bot")
                check(offline and offline["online"] is False, "humans see bot offline after it disconnects")
            st, _, _ = http("POST", "/bot/name", {"name": "助手"}, token=ta)
            await recv(wb, "bot"); await recv(wa, "bot")

            print("== account")
            st, dv, _ = http("GET", "/auth/devices", token=ta)
            check(st == 200 and any(d["current"] for d in dv) and all("id" in d for d in dv), f"device list ({len(dv)} device(s))")
            st, extra, _ = http("POST", "/auth/login", {"name": A, "password": PA, "device": "second-phone"})
            st2, dv2, _ = http("GET", "/auth/devices", token=ta)
            other = next(d for d in dv2 if d["device"] == "second-phone")
            st3, _, _ = http("DELETE", f"/auth/devices/{other['id']}", token=ta)
            st4, _, _ = http("GET", "/auth/devices", token=extra["token"])
            check(st == 200 and st3 == 204 and st4 == 401, "revoked device token stops working immediately")
            st, _, _ = http("POST", "/auth/password", {"oldPassword": "wrong", "newPassword": "newpass123"}, token=ta)
            check(st == 403, f"wrong old password -> {st}")
            st, pr, _ = http("POST", "/auth/password", {"oldPassword": PA, "newPassword": PA + "x", "logoutOthers": True}, token=ta)
            st2, _, _ = http("POST", "/auth/login", {"name": A, "password": PA + "x", "device": "e2e"})
            st3, _, _ = http("POST", "/auth/password", {"oldPassword": PA + "x", "newPassword": PA}, token=ta)
            check(st == 200 and st2 == 200 and st3 == 200, "password change works and reverts")

        # B closed -> A gets offline presence with lastSeen
        pres = await recv(wa, "presence")
        check(pres and pres["user"] == idb and pres["online"] is False and pres.get("lastSeen"), "A gets presence(B offline, lastSeen)")
        await wa.send(json.dumps({"t": "call.invite", "callId": str(uuid.uuid4()), "video": True}))
        rj = await recv(wa, "call.reject")
        check(rj and rj["reason"] == "offline", "invite while peer offline -> reject(offline)")

        print("== 1.5: rate limiting")
        # 200 pings in one burst: the frame bucket (40/s, burst 80) refuses the excess with rate_limited, the socket stays up
        for i in range(200):
            await wa.send(json.dumps({"t": "ping", "ts": i}))
        pongs = limited = 0
        end = time.time() + 5
        while pongs + limited < 200 and time.time() < end:
            m = await recv(wa, timeout=max(0.0, end - time.time()))
            if m is None:
                break
            if m["t"] == "pong":
                pongs += 1
            elif m["t"] == "error" and m.get("code") == "rate_limited":
                limited += 1
        # (the burst of 80 minus whatever the frames just before spent: the bucket does not refill in the few ms this takes)
        check(limited >= 100 and pongs >= 70 and pongs + limited == 200, f"200 pings in a burst: {pongs} pongs, {limited} rate_limited")
        await asyncio.sleep(2.2)  # the bucket refills at 40/s
        for i in range(30):  # ~20 frames/s is a normal pace: never limited
            await wa.send(json.dumps({"t": "ping", "ts": 1000 + i}))
            await asyncio.sleep(0.05)
        pongs = limited = 0
        end = time.time() + 3
        while pongs + limited < 30 and time.time() < end:
            m = await recv(wa, timeout=max(0.0, end - time.time()))
            if m is None:
                break
            if m["t"] == "pong":
                pongs += 1
            elif m["t"] == "error" and m.get("code") == "rate_limited":
                limited += 1
        check(pongs == 30 and limited == 0, f"30 pings at 20/s: {pongs} pongs, {limited} rate_limited")
        # msg.send bucket (60/min, burst 90) on a fresh connection, paced under the frame limit so only the send bucket can refuse
        async with websockets.connect(WS, additional_headers={"Authorization": "Bearer " + ta}) as wa2:
            await recv(wa2, "hello")
            n = 120
            for i in range(n):
                await wa2.send(json.dumps({"t": "msg.send", "id": f"rate-{i:04d}-{uuid.uuid4().hex[:8]}", "kind": "text", "text": "   "}))
                await asyncio.sleep(0.03)
            bad = limited = 0
            end = time.time() + 5
            while bad + limited < n and time.time() < end:
                m = await recv(wa2, "error", timeout=max(0.0, end - time.time()))
                if m is None:
                    break
                if m.get("code") == "rate_limited" and m.get("ref", "").startswith("rate-"):
                    limited += 1
                elif m.get("code") == "bad_request":
                    bad += 1
            check(bad >= 90 and limited > 0 and bad + limited == n, f"120 msg.send at ~30/s: {bad} validated, {limited} rate_limited with ref")

    print()
    print("ALL PASSED" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}")
    sys.exit(1 if FAILS else 0)


asyncio.run(main())
