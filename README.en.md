# lochatter

lochatter is an instant-messaging system for two users. It includes an Android client, a .NET server, an optional web client, and an optional Hermes assistant plugin. Text, images, voice messages, and files are end-to-end encrypted on the client by default. The server delivers ciphertext and does not read message bodies.

Android 2.3.7 (`versionCode` 47). Server 2.3.0. Application id `ink.jvm.chatter`, `arm64-v8a` only. License [MIT](LICENSE), copyright 2026 laosaonan2.

The normative document is the Chinese README: [README.md](README.md).

2.2.0 adds no protocol frames and no message types. The server process reads its runtime settings from environment variables. The default server URL written into an Android build is taken from `CHATTER_SERVER_URL`, then `android/server.properties`, then `https://$CHATTER_DOMAIN`. Filled-in configuration is gitignored. Only the `*.example` files are committed.

2.2.1 does not change the protocol or the server. On Android, the sticker panel stays open when its search field takes focus, and the panel is placed above the keyboard.

2.2.2 adds no message kind and no WebSocket frame. Schema stays 9. The web client at `https://<domain>/web/` shows a QR code. A signed-in phone confirms it from Settings. The web token and key ring stay in that page's memory and are discarded when the page is reloaded. Android `versionCode` is 38. The server `Version` is `2.2.2`.

2.3.0 adds no message kind and no WebSocket frame. Schema stays 9. Android `versionCode` is 40. The server `Version` is `2.3.0`. A call between the two people connects through relay first. While the media path is still relayed, the caller gathers candidates again about every 20 seconds and switches to a direct path when one succeeds. A failed attempt does not end the call. TCP relay is only a last resort. Call captions are produced on the phone from its own microphone and are not sent. The call summary is off by default. When enabled, an on-device model summarizes only that phone's captions and does not upload them. Assistant calls are unchanged. The web page decrypts audio and video in memory, downloads a file under its decrypted name, and opens images larger. Location, call, and sticker messages stay one line.

2.3.1 does not change the protocol or the server. Android `versionCode` is 41. The summary model is downloaded from its own settings row. Turning on the call summary does not start that download. If the model is not on the phone, the switch stays off.

2.3.2 does not change the protocol or the server. Android `versionCode` is 42. The summary model is downloaded from ModelScope. The Hugging Face address that required a license token is no longer used. The weight file is still Gemma 3 1B int4.

2.3.3 does not change the protocol or the server. Android `versionCode` is 43. Call captions are still produced on the phone from its own microphone and are not sent. The microphone copy no longer calls `ByteBuffer.array()`, which aborted the process on Android 16.

2.3.4 does not change the protocol or the server. Android `versionCode` is 44. Call captions stay. Each microphone read rewinds the buffer first, and the copy does not change the buffer WebRTC is using. Hardware echo cancellation and noise suppression are off; WebRTC's software processor is used instead, so recording can start on Huawei Android 16.

2.3.5 does not change the protocol or the server. Android `versionCode` is 45. Call captions stay. A few milliseconds after the local description is set, WebRTC's network thread calls a method on a null object and the process exits. The Android network monitor stays off. Candidates are not gathered ahead of time, and they are not surfaced again when the transport type changes. Continual gathering and backup-path checks stay.

2.3.6 does not change the protocol or the server. Android `versionCode` is 46. Call captions stay. 2.3.5 still exited at the same address while setting the local description. The backup-candidate ping interval and the STUN keepalive interval are not set: the connection list is empty then, and this library calls a method on that missing item. The whiteboard data channel is created only after ICE is connected. A later ICE restart still tries a direct path. The Android network monitor stays on.

2.3.7 does not change the protocol or the server. Android `versionCode` is 47. Call setup matches 2.2.0 again: the caller creates the whiteboard channel before the first offer, and a dropped link still restarts the way 2.2.0 did. A human call still connects through the relay first; the caller tries a direct path only after that media path is up. The on-device speech model loads only after both sides have media. Until that load finishes, the record thread does not copy samples. After it finishes, the record thread copies one frame into its own buffer before handing the frame to the send path, and transcription reads only that buffer. A null buffer or `AudioRecord` skips the copy. Hangup closes the copy before the connection is released. Call captions stay on the phone and are not sent.

A deployment has at most two human accounts, plus an optional assistant (user id 0). The assistant receives only messages explicitly addressed to it, and only in plaintext. Frames are JSON over a WebSocket. After a disconnect the client resumes by sequence number and retries an unacknowledged send with the original id.

Encryption is ECDH P-256, HKDF, and AES-256-GCM. Ciphertext uses the prefix `e2e:` or, after a key epoch of about seven days, `e2e2:`. Media uses `LCE1` or `LCE2`. SDP and ICE between the two users are encrypted with the same session key. Media is WebRTC DTLS-SRTP and is not stored by the chat server. If either party has not published a public key, that message is sent in plaintext.

The offline-notification style that includes the original text uploads the already-derived session key. The identity private key remains on the device.

Voice messages are transcribed on the device with SenseVoice, unless cloud recognition is enabled. Calls use WebRTC, with coturn (UDP/TCP 3478, relay UDP 49160–49200) when a direct path is unavailable. A call with the assistant is also WebRTC; its SDP and ICE are plaintext. The chat server does not relay that audio.

External push (Server酱³ or MeoW) is sent only when the recipient has no WebSocket connection.

Directories: `android/` client, `server/` chat server, `web/` browser client, `deploy/` install scripts, `hermes/lochatter/` assistant plugin, `docs/` protocol and version notes, `tools/` local checks.

[docs/protocol.md](docs/protocol.md) is the base protocol. `docs/protocol-1.3.md` through `docs/protocol-2.2.md` are later additions. [docs/roadmap.md](docs/roadmap.md) records what each version shipped. Version 2.1 shipped offline notification and per-user avatar and signature. The assistant does not join a call between the two users.
