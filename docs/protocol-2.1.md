# 协议增量 2.1

接收方没有任何 WebSocket 连接时，服务端按该账号的配置，向 Server酱³ 或 MeoW 提交一条短文本。接收方仍在线时不发送外部通知，由客户端本地提醒。

## 1. 账号配置

`GET /push` 与 `PUT /push` 要求人类用户登录。助手账号不能修改该配置。

```json
{ "provider": "off|serverchan|meow", "secret": "", "intervalSec": 60, "style": "hint|text|count" }
```

- `serverchan` 的 `secret` 为 SendKey，形式为 `sctp<数字>t<字母数字>`。服务端只向 `https://<uid>.push.ft07.com/send/<sendkey>.send` 提交 JSON `{title, desp, short}`。
- `meow` 的 `secret` 为昵称。服务端只向 `https://api.chuckfang.com/<昵称>` 提交 JSON `{title, msg}`。
- `intervalSec` 的范围为 0–86400。0 表示每条消息都发送。窗口内的后续消息合并为一条。接收方重新连接后，尚未发出的合并结果丢弃。
- `style`：`hint` 为「某用户发来消息」；`text` 为该句加上解密后的原文；`count` 为条数。
- `POST /push/keys` 的正文为 `{ "keys": [{ "s": 1, "r": 2, "key": "<base64，32 字节>" }] }`。`key` 是客户端已经导出的会话密钥，用于解密 `e2e:` 与 `e2e2:`。身份私钥不上报。无法解密时使用发送端附带的明文预览，不推送密文。该数据自 schema 9 起保存。

命令行操作同一张表：`chatter-server notify list`，以及 `notify set <名字> off|serverchan|meow …`。

schema 8 增加表 `push_pref`。

## 2. 个人资料

共享表中的 `av-<用户 id>` 与 `sg-<用户 id>` 分别保存该用户自己的头像媒体 id 与签名。只有本人可以 `PUT`。其他用户写入时返回 403。旧的共用键 `avatar` 与 `sign` 不再表示头像与签名。对端通过 `shared` 帧立即收到新值。客户端在连接存续期间每 30 秒再执行一次 `GET /shared`。

## 3. msg.send.notice

可选字段 `notice` 为明文预览，最多取前 200 个字符。该字段不入库，也不转发给对端。没有 `notice` 时，密文消息的通知为「一条新消息」，或按类型使用标签：`[图片]`、`[语音]`、`[视频]`、`[文件]`、`[表情]`、`[位置]`、`[通话]`。`once` 为 true 时固定通知「阅后即焚」，忽略 `notice`。助手的明文与卡片可以直接使用正文。
