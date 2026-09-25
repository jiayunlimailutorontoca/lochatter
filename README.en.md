# lochatter

lochatter is an instant-messaging system for two users. It includes an Android client, a .NET server, an optional web client, and an optional Hermes assistant plugin. Text, images, voice messages, and files are end-to-end encrypted on the client by default. The server delivers ciphertext and does not read message bodies.

Android 2.3.0 (`versionCode` 40). Server 2.3.0. Application id `ink.jvm.chatter`, `arm64-v8a` only. License [MIT](LICENSE), copyright 2026 laosaonan2.

The normative document is the Chinese README: [README.md](README.md).

2.2.0 adds no protocol frames and no message types. The server process reads its runtime settings from environment variables. The default server URL written into an Android build is taken from `CHATTER_SERVER_URL`, then `android/server.properties`, then `https://$CHATTER_DOMAIN`. Filled-in configuration is gitignored. Only the `*.example` files are committed.

2.2.1 does not change the protocol or the server. On Android, the sticker panel stays open when its search field takes focus, and the panel is placed above the keyboard.

2.2.2 adds no message kind and no WebSocket frame. Schema stays 9. The web client at `https://<domain>/web/` shows a QR code. A signed-in phone confirms it from Settings. The web token and key ring stay in that page's memory and are discarded when the page is reloaded. Android `versionCode` is 38. The server `Version` is `2.2.2`.

2.3.0 adds no message kind and no WebSocket frame. Schema stays 9. Android `versionCode` is 40. The server `Version` is `2.3.0`. A call between the two people connects through relay first. While the media path is still relayed, the caller gathers candidates again about every 20 seconds and switches to a direct path when one succeeds. A failed attempt does not end the call. TCP relay is only a last resort. Call captions are produced on the phone from its own microphone and are not sent. The call summary is off by default. When enabled, an on-device model summarizes only that phone's captions and does not upload them. Assistant calls are unchanged. The web page decrypts audio and video in memory, downloads a file under its decrypted name, and opens images larger. Location, call, and sticker messages stay one line.

A deployment has at most two human accounts, plus an optional assistant (user id 0). The assistant receives only messages explicitly addressed to it, and only in plaintext. Frames are JSON over a WebSocket. After a disconnect the client resumes by sequence number and retries an unacknowledged send with the original id.

Encryption is ECDH P-256, HKDF, and AES-256-GCM. Ciphertext uses the prefix `e2e:` or, after a key epoch of about seven days, `e2e2:`. Media uses `LCE1` or `LCE2`. SDP and ICE between the two users are encrypted with the same session key. Media is WebRTC DTLS-SRTP and is not stored by the chat server. If either party has not published a public key, that message is sent in plaintext.

The offline-notification style that includes the original text uploads the already-derived session key. The identity private key remains on the device.

Voice messages are transcribed on the device with SenseVoice, unless cloud recognition is enabled. Calls use WebRTC, with coturn (UDP/TCP 3478, relay UDP 49160–49200) when a direct path is unavailable. A call with the assistant is also WebRTC; its SDP and ICE are plaintext. The chat server does not relay that audio.

External push (Server酱³ or MeoW) is sent only when the recipient has no WebSocket connection.

Directories: `android/` client, `server/` chat server, `web/` browser client, `deploy/` install scripts, `hermes/lochatter/` assistant plugin, `docs/` protocol and version notes, `tools/` local checks.

[docs/protocol.md](docs/protocol.md) is the base protocol. `docs/protocol-1.3.md` through `docs/protocol-2.2.md` are later additions. [docs/roadmap.md](docs/roadmap.md) records what each version shipped. Version 2.1 shipped offline notification and per-user avatar and signature. The assistant does not join a call between the two users.
