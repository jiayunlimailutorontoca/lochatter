# 协议增量 1.9

本版本不增加消息 `kind`，也不改变既有帧的字段。交付内容为客户端内部分层，以及 Web 客户端。

## 1. 浏览器认证

浏览器不能在 WebSocket 握手中设置 `Authorization` 头。同一设备令牌可以放在名为 `chatter` 的 cookie 中。仅当请求没有 `Authorization: Bearer` 时，服务端才读取该 cookie。`/auth/login`、`/media` 与 `/keys` 仍使用 Bearer。该 cookie 只用于 `GET /ws` 的握手。

令牌不得写入 URL。写入 URL 的令牌会进入 nginx 访问日志。

## 2. Web 客户端

静态文件位于 `web/`。部署后的路径为 `https://<域名>/web/`。

- 使用既有的 `POST /auth/login` 与 `GET /ws`。
- 只实现文本与图像。其他 `kind` 显示为一行摘要。
- 端到端加密使用 `e2e2:` / `LCE2`。没有 epoch 密钥时退回 `e2e:` / `LCE1`。算法与 Android 客户端相同：P-256 ECDH、HKDF-SHA256、AES-256-GCM，附加认证数据为消息 `id`。
- Web 客户端不生成身份密钥。密钥从 Android 设置中的迁移二维码导入。二维码前缀为 `lochatter1:`，封装为 PBKDF2（200000 次）与 AES-GCM，并附 6 位 PIN。Web 客户端与导出密钥的那台手机共用该令牌与该密钥材料。

## 3. 媒体路径

聊天进程只投递消息与信令。双人通话，以及用户与助手的通话，音频都走 WebRTC，不在聊天进程中做媒体中继。转写与合成使用独立的模型服务。模型标识以该服务实际提供的为准。
