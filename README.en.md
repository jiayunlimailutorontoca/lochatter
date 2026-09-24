# lochatter

lochatter is an instant-messaging system for two users. It includes an Android client, a .NET server, an optional web client, and an optional Hermes assistant plugin. Text, images, voice messages, and files are end-to-end encrypted on the client by default. The server delivers ciphertext and does not read message bodies.

Android 2.2.1 (`versionCode` 37). Server 2.2.0. Application id `ink.jvm.chatter`, `arm64-v8a` only. License [MIT](LICENSE), copyright 2026 laosaonan2.

The normative document is the Chinese README: [README.md](README.md).

2.2.0 adds no protocol frames and no message types. The server process reads its runtime settings from environment variables. The default server URL written into an Android build is taken from `CHATTER_SERVER_URL`, then `android/server.properties`, then `https://$CHATTER_DOMAIN`. Filled-in configuration is gitignored. Only the `*.example` files are committed.

2.2.1 does not change the protocol or the server. On Android, the sticker panel stays open when its search field takes focus, and the panel is placed above the keyboard.

A deployment has at most two human accounts, plus an optional assistant (user id 0). The assistant receives only messages explicitly addressed to it, and only in plaintext. Frames are JSON over a WebSocket. After a disconnect the client resumes by sequence number and retries an unacknowledged send with the original id.

Encryption is ECDH P-256, HKDF, and AES-256-GCM. Ciphertext uses the prefix `e2e:` or, after a key epoch of about seven days, `e2e2:`. Media uses `LCE1` or `LCE2`. SDP and ICE between the two users are encrypted with the same session key. Media is WebRTC DTLS-SRTP and is not stored by the chat server. If either party has not published a public key, that message is sent in plaintext.

The offline-notification style that includes the original text uploads the already-derived session key. The identity private key remains on the device.

Voice messages are transcribed on the device with SenseVoice, unless cloud recognition is enabled. Calls use WebRTC, with coturn (UDP/TCP 3478, relay UDP 49160–49200) when a direct path is unavailable. A call with the assistant is also WebRTC; its SDP and ICE are plaintext. The chat server does not relay that audio.

External push (Server酱³ or MeoW) is sent only when the recipient has no WebSocket connection.

Directories: `android/` client, `server/` chat server, `web/` browser client, `deploy/` install scripts, `hermes/lochatter/` assistant plugin, `docs/` protocol and version notes, `tools/` local checks.

[docs/protocol.md](docs/protocol.md) is the base protocol. `docs/protocol-1.3.md` through `docs/protocol-2.2.md` are later additions. [docs/roadmap.md](docs/roadmap.md) records what each version shipped. Version 2.1 shipped offline notification and per-user avatar and signature. The assistant does not join a call between the two users.
