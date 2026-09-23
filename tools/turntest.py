"""Verifies TURN end to end: get creds from the chat server, then do an authenticated
TURN Allocate against coturn and release it.

usage: python tools/turntest.py https://chat.example.com <user> <password>
"""
import asyncio, hashlib, hmac, json, os, socket, struct, sys, urllib.request
import websockets

SERVER, USER, PW = sys.argv[1:4]
MAGIC = 0x2112A442


def attr(t, v):
    pad = (4 - len(v) % 4) % 4
    return struct.pack("!HH", t, len(v)) + v + b"\x00" * pad


def build(mtype, txid, attrs, key=None):
    body = b"".join(attrs)
    if key is not None:
        hdr = struct.pack("!HHI", mtype, len(body) + 24, MAGIC) + txid
        body += attr(0x0008, hmac.new(key, hdr + body, hashlib.sha1).digest())
    return struct.pack("!HHI", mtype, len(body), MAGIC) + txid + body


def parse(data):
    mtype, mlen, _ = struct.unpack("!HHI", data[:8])
    attrs, i = {}, 20
    while i + 4 <= 20 + mlen:
        t, l = struct.unpack("!HH", data[i:i + 4])
        attrs[t] = data[i + 4:i + 4 + l]
        i += 4 + ((l + 3) & ~3)
    return mtype, attrs


def xor_addr(v):
    port = struct.unpack("!H", v[2:4])[0] ^ 0x2112
    ip = ".".join(str(b ^ m) for b, m in zip(v[4:8], b"\x21\x12\xa4\x42"))
    return ip, port


async def creds():
    req = urllib.request.Request(SERVER + "/auth/login", data=json.dumps({"name": USER, "password": PW, "device": "turntest"}).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=20) as r:
        token = json.loads(r.read())["token"]
    ws_url = SERVER.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    async with websockets.connect(ws_url, additional_headers={"Authorization": "Bearer " + token}) as ws:
        await ws.recv()
        await ws.send(json.dumps({"t": "turn.get"}))
        while True:
            m = json.loads(await ws.recv())
            if m.get("t") == "turn.creds":
                return m


def main():
    c = asyncio.run(creds())
    turn_url = next(u for u in c["urls"] if u.startswith("turn:") and "udp" in u)
    host, port = turn_url[5:].split("?")[0].split(":")
    print(f"creds: user={c['username']} urls={len(c['urls'])} -> {host}:{port}/udp")

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(5)
    s.connect((host, int(port)))
    txid = os.urandom(12)
    s.send(build(0x0003, txid, [attr(0x0019, b"\x11\x00\x00\x00")]))
    mtype, a = parse(s.recv(2048))
    code = a[0x0009][2] * 100 + a[0x0009][3] if 0x0009 in a else None
    realm, nonce = a.get(0x0014, b"").decode(), a.get(0x0015, b"")
    print(f"unauth allocate -> type=0x{mtype:04x} error={code} realm={realm!r}")
    assert mtype == 0x0113 and code == 401, "expected 401 challenge"

    key = hashlib.md5(f"{c['username']}:{realm}:{c['credential']}".encode()).digest()
    txid = os.urandom(12)
    s.send(build(0x0003, txid, [
        attr(0x0006, c["username"].encode()), attr(0x0014, realm.encode()), attr(0x0015, nonce),
        attr(0x0019, b"\x11\x00\x00\x00"),
    ], key))
    mtype, a = parse(s.recv(2048))
    if mtype != 0x0103:
        code = a[0x0009][2] * 100 + a[0x0009][3] if 0x0009 in a else None
        print(f"FAIL auth allocate -> type=0x{mtype:04x} error={code} {a.get(0x0009, b'')[4:].decode(errors='replace')}")
        sys.exit(1)
    relay = xor_addr(a[0x0016])
    mapped = xor_addr(a[0x0020])
    life = struct.unpack("!I", a[0x000D])[0]
    print(f"ok   allocate -> relay={relay[0]}:{relay[1]} lifetime={life}s mapped={mapped[0]}:{mapped[1]}")
    assert 49160 <= relay[1] <= 49200, "relay port outside configured range"

    txid = os.urandom(12)
    s.send(build(0x0004, txid, [
        attr(0x0006, c["username"].encode()), attr(0x0014, realm.encode()), attr(0x0015, nonce),
        attr(0x000D, struct.pack("!I", 0)),
    ], key))
    mtype, _ = parse(s.recv(2048))
    print(f"ok   refresh(0) -> type=0x{mtype:04x} (released)" if mtype == 0x0104 else f"warn refresh -> 0x{mtype:04x}")
    print("TURN OK")


main()
