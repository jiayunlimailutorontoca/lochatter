# 1.5.0 协议增量

在 [protocol-1.3.md](protocol-1.3.md) 之上。schema 不变（7）。

## 限流

每个连接两个令牌桶：帧 40/s（突发 80），`msg.send` 60/min（突发 90）；助手连接三倍。超出时回 `error{code:"rate_limited", ref?}` 并丢弃该帧；连续 10 秒超帧限直接断开。

## 孤儿媒体清理

上传超过 2 小时（`CHATTER_MEDIA_GRACE_SECONDS`，默认 7200）且没有任何消息引用（既不是 `media_id` 也不是被引用媒体的 `thumb_id`）的媒体行和文件，由清理循环定期删除（每 `clamp(grace/2, 2, 600)` 秒一轮，默认 10 分钟）。

## 新 kind `location`

人类可发，也可 `to:"bot"`（明文）。text ≤ 512：`lat,lng|精度米|地址|live`，`live` 为 `1` 表示实时分享进行中。人类之间为 `e2e:` / `e2e2:` 密文。摘要 `[位置] 地址`。计入 ContentKinds。**`edit` 允许作用于 `location`**（实时位置每 10 秒编辑同一条，15 分钟后最后一次编辑把 `live` 置 0）。客户端对 location 的 edit 不显示「已编辑」。

## 通话

`call.media` 多一个可选 `screen: bool`：发送方开始 / 停止共享屏幕。视频通话里共享屏幕复用摄像头轨道（不重协商）；语音通话里新增一条视频轨并重新 offer。

## 密钥

- `/keys` 的 `pubKey` 上限 512 → 4096 字符，服务端仍不解析。
- 1.5 客户端发布的是 **v2 bundle**：`v2|<身份公钥>|<n>|<第 n 期公钥>|<签名>|<n-1>|...`（最近 12 期，新的在前）。签名 = 身份私钥 ECDSA-SHA256 over `"lochatter-epoch|" + n + "|" + 公钥`。旧客户端发的裸 X.509 公钥仍被接受（视为第 0 期）。
- 会话密钥（发送方 A 第 a 期，接收方 B 第 b 期）：`HKDF-SHA256(ECDH(A_a, B_b), salt = SHA-256(sorted(pub_a, pub_b)), info = "lochatter-e2e-v2|a.b")`。任一方为第 0 期时退回 v1（身份密钥）。每周自动轮换一次；安全码仍只看身份公钥，轮换不改安全码。
- 文本密文 v2：`e2e2:<发送方 uid>:<a>.<b>:<base64(nonce‖ct)>`；媒体 v2：`LCE2` ‖ uid(8) ‖ a(4) ‖ b(4) ‖ LCE1 正文。接收方按 uid 判断自己是发送方还是接收方，从密钥环里取对应两期的密钥。旧 `e2e:` / `LCE1` 照常解。
- 换手机：设置 → 迁移到新手机 出二维码（内容是 PBKDF2(6 位码) + AES-GCM 包起来的密钥环 JSON + 服务器地址 + 令牌），新手机扫码输入 6 位码即可，随后重新同步历史。

## 助手

- `POST /bot/card {text}`：仅助手令牌；插入一条 `card` 消息并广播（NAS 上的健康检查脚本用它报告「服务器恢复了」）。
- 助手收到的 `location` 转成 `[位置] 地址 (lat,lng，精度 N 米)`。
