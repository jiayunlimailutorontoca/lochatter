"""Quick end-to-end check of the WebSocket endpoint.
usage: python tools/wstest.py [wss://chat.example.com/ws]
"""
import asyncio, json, sys, time
import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "wss://chat.example.com/ws"

async def main():
    t0 = time.perf_counter()
    async with websockets.connect(URL, open_timeout=15) as ws:
        print(f"connected in {(time.perf_counter()-t0)*1000:.0f} ms")
        print("<-", await ws.recv())                       # hello
        await ws.send(json.dumps({"t": "ping", "ts": int(time.time()*1000)}))
        print("<-", await ws.recv())                       # pong
        await ws.send(json.dumps({"t": "echo", "text": "你好, chatter", "n": 42}))
        print("<-", await ws.recv())                       # echo
        await ws.send("{not json")
        print("<-", await ws.recv())                       # error bad_json
        await ws.send(json.dumps({"t": "nope"}))
        print("<-", await ws.recv())                       # error bad_json (unknown discriminator)
        await ws.send(b"\x00\x01")
        print("<-", await ws.recv())                       # error bad_frame
        await ws.send(json.dumps({"t": "echo", "text": "x" * (70 * 1024)}))
        try:
            print("<-", await ws.recv())
        except websockets.ConnectionClosed as e:
            print(f"closed by server as expected: code={e.rcvd.code} reason={e.rcvd.reason!r}")

asyncio.run(main())
