# 协议基线

聊天服务监听 `127.0.0.1:5088`。TLS 在 nginx 终止，nginx 将请求反向代理到该地址。本文规定基线接口。后续版本增加的帧与字段见对应的 `protocol-*.md`。实现须同时满足基线与已部署版本的增量。

字段名为 camelCase。值为 null 的字段省略。不使用二进制 WebSocket 帧。

| 接口 | 认证 | 作用 |
|---|---|---|
| `POST /auth/login` | 无 | 以用户名与密码换取长期设备令牌 |
| `GET /ws` | 令牌 | WebSocket。原生客户端使用 `Authorization: Bearer`。Web 客户端握手可使用 cookie `chatter` |
| `POST /media`、`GET /media/{id}` | 令牌 | 上传与读取媒体。`id` 为内容的 SHA-256 |
| `POST /keys`、`GET /keys/{userId}` | 令牌 | 发布与读取身份公钥 |
| `GET /healthz` | 无 | 存活检查，响应含版本号 |

## 1. 认证

`POST /auth/login` 的正文为 `{"name":"...","password":"...","device":"可选"}`。

- 200：`{"token":"...","user":{"id","name"},"peer":{"id","name"}|null}`。服务端只保存令牌的 SHA-256。
- 401：凭据错误。同一 IP 连续失败 5 次后锁定 5 分钟，此期间返回 429。
- 此后请求携带 `Authorization: Bearer <token>`。`chatterctl token revoke <名字>` 撤销该用户全部设备令牌。

修改密码：`POST /auth/password`，正文 `{oldPassword, newPassword, logoutOthers}`。设备列表：`GET /auth/devices`，响应 `[{id, device, createdAt, lastSeen, current}]`。`DELETE /auth/devices/{id}` 立即作废对应令牌并断开其连接。

## 2. WebSocket

每帧一条 JSON 文本，编码 UTF-8，最大 256 KiB。`t` 为类型判别字段。服务端约每 30 秒发送一次协议层 ping，60 秒无响应则断开。

认证成功后的首帧为 `hello`。客户端根据 `lastSeq` 决定是否发送 `sync`。服务端将消息写入数据库后才返回 `msg.ack`。客户端保存 `lastSeq`，重连后发送 `sync{since: lastSeq}` 补齐缺口。未收到 ack 的消息以原 `id` 重发；服务端按 `id` 去重，并返回原 `seq`。

| t | 方向 | 字段 | 约束 |
|---|---|---|---|
| hello | S→C | connId, version, serverTs, user, peer?, lastSeq, myReadUpto, peerReadUpto, myPubKey?, ttlSeconds | 认证成功后的首帧。后续版本可增加字段；未知字段由旧客户端忽略 |
| ping / pong | 双向 | ts? / ts?, serverTs | 应用层心跳，可选 |
| msg.send | C→S | id, kind, text?, mediaId?, replyTo?, to? | `id` 为客户端生成的 UUID |
| msg.ack | S→C | id, seq, ts | 只发给发送连接。重复 `id` 返回原 `seq` |
| msg.new | S→C | msg | 发给除该发送连接以外的连接 |
| sync | C→S | since, limit?, before? | `limit` 默认 200，最大 500。带 `before` 时向前翻页 |
| msg.batch | S→C | messages[], hasMore, before? | 正向同步以最后一条 `seq` 继续。历史分页回显 `before` |
| read | 双向 | upto / upto, user | 已读序号只前进 |
| typing | 双向 | – / user | 输入状态。不持久化 |
| active | C→S | fg | 前台为 true，后台为 false。新连接初始视为后台 |
| presence | S→C | user, online, lastSeen? | `online` 表示至少一条连接处于前台 |
| error | S→C | code, message, ref? | `bad_json`、`bad_frame`、`bad_request`、`conflict`、`unsupported`、`not_found`、`rate_limited`。`ref` 为失败的 `msg.send` 的 `id` |

`msg` 的结构为 `{seq, id, from, kind, text?, media?, ts, reply?}`。`reply` 为 `{id, from, text}`，由服务端在入库时从被引用消息截取。

`media` 的结构为 `{id, mime, size, width?, height?, durationMs?, thumbId?, name?}`。`name` 为原始文件名。加密上传时该字段同样为密文。

基线 `kind` 为 `text`、`image`、`audio`、`video`、`file`、`call`、`del`、`clear`。后续增量增加 `album`、`sticker`、`pat`、`card`、`location`，以及控制类型 `react`、`edit`、`ttl`、`recall`。当前全集以各增量文档为准。

`del` 的 `text` 为目标消息 `id`。服务端删除该消息及不再被引用的文件，并将 `del` 发给双方。`clear` 清空全部消息，服务端把 `text` 填为清空前的最后 `seq`。离线客户端通过 `sync` 取得这些条目后执行相同操作。控制条目本身不渲染为气泡。

`edit` 的 `text` 为 `<目标 id>|<新文本>`，只能修改发送者自己的 `text` 消息。服务端更新原消息并记录 `editedAt`。同一目标只保留最新一条 `edit`，因此 `seq` 可以不连续。

`ttl` 的 `text` 为秒数，`0` 表示关闭。定时销毁对双方同时生效。此后插入的内容消息带 `expiresAt`。服务端约每 30 秒删除到期消息，并以发送者身份写入 `del`，其 `id` 为 `exp-<原 id>`。当前秒数位于 `hello.ttlSeconds`。

`react` 的 `text` 为 `<目标消息 id>|<emoji>|<1 添加 / 0 取消>`。客户端将其聚合到目标消息上，不单独显示。

`video` 的 `mediaId` 指向视频文件，封面为缩略图，时长毫秒由查询参数 `d` 传入并保存在媒体记录中。

文本最大长度为 20000 个字符。

## 3. 密钥与密文

用户通过 `POST /keys {"pubKey":...}` 发布 ECDH P-256 公钥，通过 `GET /keys/{userId}` 读取。公钥变化时，服务端向全部连接广播 `keys {user, pubKey, updatedAt}`。`hello` 携带 `myPubKey` 与对端 `peer.pubKey`。

双方以 ECDH 与 HKDF 导出 AES-256-GCM 会话密钥后：

- 文本、说明、通话记录、`react` 与 `edit` 的新文本使用前缀 `e2e:`。附加认证数据为消息 `id`。
- 媒体整体加密后上传。旧格式为 `LCE1`。服务端只保存密文。
- 双人通话中 `call.sdp` 的 `sdp` 与 `call.ice` 的 `candidate` 使用同一密钥，附加认证数据为 `callId`。

服务端不解析 `e2e:` 之后的内容。引用快照原样保存，不截断。1.5 引入按周轮换的 `e2e2:` / `LCE2`，格式见 [protocol-1.5.md](protocol-1.5.md)。自 1.6 起，两种前缀都视为密文，见 [protocol-1.6.md](protocol-1.6.md)。

任一方尚未发布公钥时，该条消息以明文发送。

## 4. 媒体

`POST /media?w=&h=&d=&thumb=&name=` 的正文为原始字节，`Content-Type` 为实际 MIME 类型。`w` 与 `h` 为像素，`d` 为时长毫秒，`thumb` 为已上传缩略图的 `id`。发送文件时必须带 `name`。

上传不设固定字节上限。nginx 的 `client_max_body_size` 为 0。数据目录所在磁盘剩余空间不足 300 MB 时返回 507。

- 201：返回媒体结构。`id` 为 SHA-256 的十六进制表示。相同内容重复上传返回同一 `id`。
- `GET /media/{id}` 要求认证，支持 Range，响应头 `Cache-Control: private, immutable`。查询参数 `dl=1` 时增加 `Content-Disposition: attachment`，并带原始文件名。

发送图像的顺序：先上传缩略图，再上传原图并附带 `thumb=<缩略图 id>`，最后发送 `msg.send{kind:"image", mediaId:<原图 id>}`。

## 5. 通话信令

服务端只转发信令。对端的每条连接都会收到该帧，帧中补上 `from`。媒体使用 WebRTC，优先直连，失败后经 coturn 中继。媒体保护为 DTLS-SRTP，服务端不解密。

| t | 方向 | 字段 | 约束 |
|---|---|---|---|
| call.invite | C→S→对端 | callId, video, from, ts | 对端不在线时，服务端直接返回 `call.reject{reason:"offline"}` |
| call.accept | 被叫→主叫 | callId, from | 主叫收到后创建 offer |
| call.reject | 被叫→主叫 | callId, reason, from | `reason` 为 `busy`、`declined`、`timeout` 或 `offline` |
| call.hangup | 双向 | callId, reason, from | 未接通时主叫挂断，`reason` 为 `cancelled`。振铃超时为 `timeout` |
| call.sdp | 双向 | callId, type, sdp, from | `type` 为 `offer` 或 `answer`。双人通话的 `sdp` 为密文 |
| call.ice | 双向 | callId, candidate, sdpMid, sdpMLineIndex, from | 双人通话的 `candidate` 为密文 |
| call.media | 双向 | callId, video, audio, from | 通话中切换摄像头或麦克风。1.6 起可带 `screen` |
| turn.get | C→S | – | 请求 TURN 凭据 |
| turn.creds | S→C | urls[], username, credential, ttlSeconds | `use-auth-secret`，有效期约 6 小时。`username` 为空表示仅有 STUN |

通话结束后，主叫发送 `kind:"call"`。`text` 格式为 `<voice|video>:<answered|missed|declined|cancelled>:<秒数>`。

coturn 监听 UDP/TCP 3478，中继 UDP 49160–49200，样例配置为 `deploy/turnserver.conf`。地址与密钥分别来自 `CHATTER_TURN_HOST` 与 `CHATTER_TURN_SECRET`。

## 6. 助手

助手为 Hermes 进程，用户 id 固定为 0。它不占用两名人类用户的名额，也不参与对端在线、已读与双人通话。令牌由 `chatterctl bot token` 签发。连接由助手主动建立。

`hello` 增加 `bot {id:0, name, online}`。名称或在线状态变化时，向全部人类连接广播 `bot` 帧。`GET /bot` 读取当前值。`POST /bot/name {"name":...}` 修改名称，长度为 1–24 个字符，不得与人类用户重名，且只有人类用户可以调用。

`msg.send` 的可选字段 `to:"bot"` 表示消息发给助手。允许的 `kind` 为 `text`、`image`、`audio`、`video`、`file`，且必须为明文。前缀 `e2e:` 的文本被拒绝。媒体以 `LCE1` 开头时同样被拒绝。发给助手的媒体不加密，文件名为明文。除非打开 1.3 的 `bot_ttl`，否则这些消息不受双人定时销毁影响。数据库字段 `dest=bot`，同步结果带 `to:"bot"`。

人类连接接收全部消息。助手连接只接收 `to:"bot"` 的明文以及助手自己发送的消息。助手不能读取双人会话中的 `e2e:` 或 `e2e2:` 内容。

助手可以发送 `text`、`image`、`audio`、`video`、`file`，以及 `edit`、`del`、`typing`。流式回复通过 `edit` 修改同一条消息实现。助手不能发送 `call`、`ttl`、`clear`，也不能发起通话。

自 schema 6 起，`messages` 含 `dest` 列。`users` 含 id 为 0 的保留行，其密码哈希为 `!`，不能用于登录。

## 7. 增量文档

- [1.3](protocol-1.3.md)：`sticker`、`pat`、`card`，阅后即焚，电量，共享键值，表情库。
- [1.5](protocol-1.5.md)：限流，位置消息，按周轮换密钥。
- [1.6](protocol-1.6.md)：`e2e2:` 视为密文；地图与转写接口。
- [1.7](protocol-1.7.md)：撤回；瓦片坐标系。
- [1.8](protocol-1.8.md)：`album`；会话置顶与当时的共享头像。
- [1.9](protocol-1.9.md)：Web 客户端。无新增 `kind`。
- [2.0](protocol-2.0.md)：与助手的语音通话及字幕。
- [2.1](protocol-2.1.md)：离线通知；每名用户独立的头像与签名。
- [2.2](protocol-2.2.md)：无新增帧。配置改为环境变量。
- [2.3](protocol-2.3.md)：无新增帧。通话在中转接通后继续尝试直连。字幕与纪要只在本机处理。网页可播放语音和视频并下载文件。2.3.1 将纪要模型的下载与开关分开。2.3.2 将纪要模型的下载改为魔搭社区。2.3.3 保留通话字幕，并修正 Android 16 上接通即退出进程的录音拷贝。2.3.4 继续保留字幕。每次读取录音前将缓冲区位置回到起点，拷贝不改动 WebRTC 正在使用的缓冲区，并关闭本机硬件回声消除与降噪。2.3.5 关闭本机网络监视器，不再预收集候选，也不再因传输类型变化重复报告候选。2.3.6 取消备用候选对探测间隔与 STUN 保活间隔，并将白板数据通道改到 ICE 接通之后创建。2.3.7 将接通顺序恢复为 2.2.0，并在双方媒体接通且本机模型加载完成之后才开始字幕拷贝。2.3.8 在挂断后展示带时间的本机字幕，用户点「总结摘要」后才调用本机模型；同一模型还可总结最近消息、润色草稿和给出回复建议。服务端仍为 2.3.0。
