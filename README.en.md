# lochatter

A chat for two people. Text, pictures, voice notes, and files are end-to-end encrypted by default. The server delivers ciphertext. It does not read the conversation.

Version 2.1.2. Android application id `ink.jvm.chatter`, arm64 only. [MIT](LICENSE), copyright 2026 laosaonan2.

The Chinese README is the one to follow: [README.md](README.md). This page is the short English version of the same facts.

## What it does

Two human accounts, plus an optional assistant. The assistant only receives messages you explicitly address to it, in the clear. It never sees the encrypted thread between the two of you.

The phone app is Android 8.0+ (minSdk 26), Kotlin and Jetpack Compose. The server is C# on .NET 10, published as Native AOT, listening on `127.0.0.1:5088`. nginx terminates TLS in front of it. A small web client does text and images; keys move over from a phone QR code (`lochatter1:` plus a 6-digit PIN).

Messages are JSON frames on a WebSocket. After a disconnect the client syncs by sequence and retries unacked sends with the same id.

Encryption is ECDH P-256, HKDF, and AES-256-GCM. Older messages use `e2e:` / `LCE1`. After a key epoch (about weekly) new ones use `e2e2:` / `LCE2`. Call setup between the two humans is encrypted with the same session key. The media itself is WebRTC DTLS-SRTP and is not stored on the chat server. If either side has no key yet, that message is sent in the clear.

One exception: the offline-push style that includes the original text uploads the already-derived session key so the server can decrypt that one notification. The identity private key stays on the phone. Use the "someone messaged you" style, or turn push off, if you do not want that.

Voice notes are transcribed on the phone (SenseVoice) unless cloud recognition is enabled in settings. That path is not the assistant.

Calls between the two phones use WebRTC, with coturn (UDP/TCP 3478, relay UDP 49160–49200) when a direct path fails. A call with the assistant is also WebRTC, between the phone and the machine running Hermes. The chat server does not relay that audio.

Offline push goes to Server酱³ or MeoW only when the recipient has no WebSocket connected. Styles: a hint, the plaintext, or a count.

## Layout

`android/` client, `server/` chat server, `web/` browser client, `deploy/` install scripts, `hermes/lochatter/` assistant plugin, `docs/` protocol and design notes, `tools/` local probes.

[docs/protocol.md](docs/protocol.md) is the base protocol. `docs/protocol-1.3.md` through `docs/protocol-2.1.md` are deltas. The 2.1 row in [docs/roadmap.md](docs/roadmap.md) used to say the assistant would join a call between the two people. What actually shipped in 2.1 / 2.1.2 is offline push, plus a personal avatar and signature. The three-way call is not built.

## Config

Secrets stay in gitignored files. Commit the `*.example` copies only.

| Example | Copy to |
|---|---|
| `android/server.properties.example` | `android/server.properties` (URL baked into a fresh install) |
| `android/local.properties.example` | `android/local.properties` |
| `android/keystore.properties.example` | `android/keystore.properties` |
| `deploy/local.env.example` | `deploy/local.env` on a dev machine, `/etc/chatter/deploy.env` on the server |
| `deploy/turn.env.example` | `/etc/chatter/turn.env` (usually written by `turn-setup.sh`) |
| `deploy/stt.env.example` | `/etc/chatter/stt.env` |
| `deploy/geo.env.example` | `/etc/chatter/geo.env` |
| `hermes/lochatter/env.example` | the plugin environment |

`CHATTER_DOMAIN` is the public host. `CHATTER_SSL_DIR` is the directory name under `/etc/nginx/ssl/` that holds `fullchain.pem` and `key.pem`. Set both when those names differ. `deploy.sh` substitutes them into `deploy/nginx-chat.conf`.

## Deploy

Install nginx, git, sqlite3, and the .NET 10 SDK (`deploy.sh` expects it at `/usr/share/dotnet`). Put the certificate in place, write `/etc/chatter/deploy.env`, then:

```bash
git clone https://github.com/jiayunlimailutorontoca/lochatter.git /opt/chatter/src
bash /opt/chatter/src/deploy/deploy.sh
bash /opt/chatter/src/deploy/turn-setup.sh
chatterctl user add alice
chatterctl user add bob
```

`deploy.sh` publishes the server, installs the systemd unit (user `chatter`, data `/var/lib/chatter`, 300 MB cap), renders the nginx site, and restarts. Open 443, plus the TURN ports above if you want calls.

The phone app needs JDK 17, Android SDK 35, and Gradle 8.9 (there is no wrapper in the repo):

```bash
cd android
cp server.properties.example server.properties
cp local.properties.example local.properties
cp keystore.properties.example keystore.properties
gradle :app:assembleRelease
```

Optional: `deploy/stt-setup.sh` for server-side SenseVoice, `deploy/geo.env` for place search, `hermes/lochatter` for the assistant, `deploy/maintenance-cron.sh` for a nightly SQLite and media backup under `/var/backups/chatter`.

## Limits

Two human accounts. arm64 only. A side without a key sends plaintext. The "include original text" push style uploads session keys. The assistant cannot join a live call between the two people. The web client is text and images only.
