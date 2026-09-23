# 协议增量 2.1：离线推送

对方任何一个连接都不在时，服务器按**收件人账号**上的配置，把一条短文字交给 Server酱³ 或 MeoW。在线（还有 WebSocket）时不推，手机自己会响。

## 账号配置

`GET /push`、`PUT /push`，要登录。助手账号不能改。

```json
{ "provider": "off|serverchan|meow", "secret": "", "intervalSec": 60, "style": "hint|text|count" }
```

- `serverchan` 的 secret 是 SendKey，形如 `sctp<数字>t<字母数字>`。服务器只向 `https://<uid>.push.ft07.com/send/<sendkey>.send` 发 JSON `{title, desp, short}`。
- `meow` 的 secret 是昵称。服务器只向 `https://api.chuckfang.com/<昵称>` 发 JSON `{title, msg}`。
- `intervalSec` 是 0 到 86400。0 表示每条都推。窗口里的后续消息合成一条，人重新连上就丢掉还没发出的合并。
- `style`：`hint` 是「某某给你发了消息」；`text` 是「某某给你发了」加上解开后的原文；`count` 是「某某的 App 发送了 N 条信息」。
- `POST /push/keys`：`{ "keys": [{ "s": 1, "r": 2, "key": "<base64 32 字节>" }] }`。这是手机已经算出的会话密钥，用来解开 `e2e:` / `e2e2:`。身份私钥不上服务器。解不开就退回发送端附带的明文，仍然不推密文。schema 9。

命令行同一张表：`chatter-server notify list` / `notify set <名字> off|serverchan|meow …`。

schema 8，表 `push_pref`。

## 个人资料

共享表里的 `av-<用户id>` 和 `sg-<用户id>` 是这个人自己的头像媒体 id 和签名。只有本人能 `PUT`。别人改会回 403。旧的共用键 `avatar` / `sign` 不再当头像用。对方的值靠 `shared` 帧马上到，客户端连着的时候每 30 秒再 `GET /shared` 一次。

## msg.send

可选字段 `notice`：最多用前 200 字的明文预览，**不入库、不转发给对方**。没有它时，密文消息只推「一条新消息」或按类型的标签（`[图片]`、`[语音]`、`[视频]`、`[文件]`、`[表情]`、`[位置]`、`[通话]`）。`once: true` 固定推「阅后即焚」，忽略 notice。助手的明文和卡片可以直接用正文。
