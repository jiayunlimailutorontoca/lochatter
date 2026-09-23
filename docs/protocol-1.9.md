# 协议增量 1.9

没有新的消息 kind，帧格式也不变。1.9 是客户端拆分和网页雏形。

## 浏览器登录

网页打不开 WebSocket 的 `Authorization` 头。同一条设备令牌也可以放在 cookie `chatter` 里（仅当请求没有 `Authorization: Bearer` 时才看这个 cookie）。`/auth/login`、`/media`、`/keys` 仍然用 Bearer。cookie 只为了 `GET /ws` 的握手。

令牌不要写进 URL，否则会进 nginx 访问日志。

## 网页端

静态页在 `web/`，部署后是 `https://chat.example.com/web/`。

- 用现有 `POST /auth/login` 和 `GET /ws`。
- 只做文字和图片。其它 kind 只显示一行摘要。
- 端到端沿用 `e2e2:` / `LCE2`（没有 epoch 密钥时退回 `e2e:` / `LCE1`）。算法与手机一致：P-256 ECDH、HKDF-SHA256、AES-256-GCM，附加数据是消息 id。
- 密钥不在网页上生成。手机设置里的换机二维码（`lochatter1:`，PBKDF2 20 万次 + AES-GCM，旁边 6 位 PIN）扫进来或贴进来。网页和那台手机共用这一把令牌和同一套密钥。

## 语音不要从聊天服务器中转

聊天进程本身很小，只递消息和信令。两人通话、还有人和助手通话，音频都走 WebRTC，不要再在这台机器上做媒体中继。转写和朗读用单独的模型服务，模型名以那台服务实际提供的为准。
