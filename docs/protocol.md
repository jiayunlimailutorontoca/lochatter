# 协议

服务端只暴露四类接口，全部经 nginx 以 TLS 反代到 `127.0.0.1:5088`：

| 接口 | 说明 |
|---|---|
| `POST /auth/login` | 用户名密码换长期设备令牌 |
| `GET /ws` | WebSocket，握手时带 `Authorization: Bearer <token>` |
| `POST /media` / `GET /media/{id}` | 图片、语音条等二进制，内容寻址 |
| `GET /healthz` | 健康检查，无需认证 |

## 认证

`POST /auth/login`，JSON：`{"name": "...", "password": "...", "device": "可选"}`。

- 200：`{"token": "...", "user": {"id", "name"}, "peer": {"id", "name"} | null}`。令牌长期有效，服务端只存其 SHA-256。
- 401 密码错误；同一 IP 连续失败 5 次锁定 5 分钟，返回 429。
- 之后所有请求带 `Authorization: Bearer <token>`。`chatterctl token revoke <name>` 可让某用户所有设备下线。

## WebSocket 帧

每帧一条 JSON 文本消息，UTF-8，最大 64 KiB；`t` 为类型判别字段，字段名 camelCase，null 字段省略。二进制帧不使用。协议层 ping/pong 由服务端每 30 秒发起，60 秒无响应断开。

端到端加密（1.0）：每个用户通过 `POST /keys {pubKey}` 发布一把 ECDH P-256 公钥（`GET /keys/{userId}` 读取；变更时服务端向所有连接广播 `keys {user, pubKey, updatedAt}`；`hello` 里带 `myPubKey` 和 `peer.pubKey`）。两端用 ECDH + HKDF 推出同一把 AES-256 密钥后，`text`（含说明文字、通话记录、`react`、`edit` 的新文本）以 `e2e:` 前缀的 AES-GCM 密文发送，附加数据为消息 id；媒体文件整体加密上传（`LCE1` 分块格式，服务端只看到密文，文件名也是密文）；`call.sdp` 的 `sdp` 与 `call.ice` 的 `candidate` 同样加密（附加数据为 callId）。服务端对 `e2e:` 开头的文本不做内容校验，引用快照原样保留不截断。

`edit`（控制消息，text = `<目标 id>|<新文本>`）：只能改自己的 `text` 消息；服务端更新原消息并记录 `editedAt`，同步时原消息带最新文本。

`ttl`（text = 秒数，0 关闭）：消息定时销毁，对双方生效；此后插入的内容消息带 `expiresAt`，服务端每 30 秒清理到期消息并以发送者身份写入 `del` 条目（id 为 `exp-<原 id>`），客户端照常应用。`hello` 里带当前 `ttlSeconds`。

账号：`POST /auth/password {oldPassword, newPassword, logoutOthers}`；`GET /auth/devices` → `[{id, device, createdAt, lastSeen, current}]`；`DELETE /auth/devices/{id}` 立即失效该令牌并断开其连接。

上传不再限制大小（nginx `client_max_body_size 0`，Kestrel 无上限），只在磁盘剩余不足 300 MB 时返回 507。WebSocket 单帧上限 256 KiB。

消息 kind 新增：`video`（mediaId 指向视频文件，thumb 为封面，d 为时长毫秒）和 `react`（控制消息，text = `<目标消息 id>|<emoji>|<1 添加 / 0 取消>`，客户端聚合显示、不单独展示）。

`call.media` `{callId, video, audio}`：通话中一方开关摄像头/麦克风，服务端原样转发并补 `from`，对方据此显示头像占位。

| t | 方向 | 字段 | 说明 |
|---|---|---|---|
| hello | S→C | connId, version, serverTs, user{id,name}, peer{id,name,online,lastSeen}?, lastSeq, myReadUpto, peerReadUpto, myPubKey?, ttlSeconds, bot{id,name,online}? | 认证成功后的首帧。客户端据 lastSeq 决定是否 sync |
| ping / pong | C→S / S→C | ts? / ts?, serverTs | 应用层心跳，可选 |
| msg.send | C→S | id, kind(text/image/audio/file/call/del/clear), text?, mediaId?, replyTo?, to? | id 为客户端生成的 UUID，重连重发时服务端按 id 去重。replyTo 引用另一条消息的 id |
| msg.ack | S→C | id, seq, ts | 仅发给发送方；重复的 id 返回原 seq |
| msg.new | S→C | msg{seq,id,from,kind,text?,media?,ts,reply?} | 发给除发送连接外的所有连接。reply{id,from,text} 是服务端落库时截取的被引用消息摘要 |
| sync | C→S | since, limit?(默认 200，最大 500), before? | 前向：seq > since；历史分页：带 before 则返回 seq < before 的最新 limit 条 |
| msg.batch | S→C | messages[], hasMore, before? | 前向 hasMore 用最后一条 seq 继续；历史分页 before 回显请求，hasMore 表示还有更早的 |
| read | C→S / S→C | upto / upto, user | 已读到某 seq；只前进不后退，转发给对方 |
| typing | C→S / S→C | – / user | 正在输入，转发给对方，不持久化 |
| active | C→S | fg | 应用进入前台 fg=true、退到后台 fg=false；新连接默认视为后台 |
| presence | S→C | user, online, lastSeen? | 对方在线指至少一个连接处于前台；离开前台或断线时带 lastSeen |
| error | S→C | code, message, ref? | bad_json / bad_frame / bad_request / conflict / unsupported / not_found / rate_limited；ref 是出错的 msg.send 的 id |

`media` 结构：`{id, mime, size, width?, height?, durationMs?, thumbId?, name?}`，`name` 是文件类消息的原始文件名。

删除与清空作为同一序列里的控制条目：`kind:"del"` 的 text 是要删除的消息 id，服务端删掉目标消息（及不再被引用的文件）并把 del 条目发给双方；`kind:"clear"` 清空全部消息，服务端把 text 填为被清空的最后一个 seq。离线的客户端通过 sync 拿到这些条目后在本地执行同样的删除，条目本身不显示。

消息可靠性：服务端落库后才 ack；客户端本地保存 lastSeq，重连后 `sync{since: lastSeq}` 补齐，未 ack 的消息按原 id 重发。

## 聊天助手（1.1）

第三个参与者是 NAS 上的 Hermes，以保留身份 **用户 0** 接入，不占两人名额，`PeerOf` / 在线 / 已读 / 通话都当它不存在。它用 `chatterctl bot token` 签发的长期令牌连同一个 `/ws`，方向是从内网**主动连出**，服务器不需要反向打洞。

- `hello` 多一个 `bot {id:0, name, online}`；名字或在线状态变化时向所有人类连接广播 `bot {id, name, online}` 帧。`GET /bot` 读取，`POST /bot/name {name}` 改名（1-24 字，不能与用户重名，人类才可改）。
- `msg.send` 多一个可选 `to`。`to:"bot"` 表示 @助手：只允许 text / image / audio / video / file，**必须明文**（`e2e:` 开头直接拒绝；媒体文件以 `LCE1` 开头即客户端加密过的也拒绝，客户端对 @助手 的媒体不加密上传，文件名也明文），不受定时销毁影响，服务端落库 `dest=bot` 并在 `msg.new` / `msg.batch` 里以 `to:"bot"` 回显。
- 转发规则：人类总是收到全部消息；助手连接只收到 `to:"bot"` 的明文消息和它自己发的消息（`sync` 同样过滤）。助手看不到两人之间的任何 `e2e:` 内容。
- 助手可以发 text / image / audio / video / file、`edit`（流式回复靠改同一条）、`del`、`typing`（转给人类，`user` 为 0）；不能发 `call` / `ttl` / `clear`，不能发起通话，没有 TURN，不参与在线 / 已读。
- 数据库 schema 6：`messages.dest`，`users` 表里插入 id 0 的保留行（pw_hash 为 `!`，不能登录）。

## 媒体

`POST /media?w=&h=&d=&thumb=&name=`，请求体为原始字节，`Content-Type` 任意合法 MIME（图片建议 image/jpeg、image/webp，语音条 audio/mp4）。`w`/`h` 像素尺寸，`d` 时长毫秒，`thumb` 为先上传的缩略图 id，`name` 为原始文件名（发文件时必带）。上限 200 MB，nginx 侧 300 MB。

- 201：media 结构，`id` 是内容的 SHA-256 十六进制，重复上传幂等。
- `GET /media/{id}` 需认证，支持 Range，`Cache-Control: private, immutable`；加 `?dl=1` 返回 `Content-Disposition: attachment` 并带原文件名。

图片发送流程：客户端先压缩生成缩略图并上传，再上传原图并带 `thumb=<缩略图 id>`，最后 `msg.send{kind:"image", mediaId:<原图 id>}`。

## 通话信令（WebRTC）

服务端只做转发：把发送方的帧原样发给对方的所有连接，并填上 `from`。媒体流走 WebRTC（P2P 优先，失败自动经 coturn 中转），全程 DTLS-SRTP 加密，服务器看不到内容。

| t | 方向 | 字段 | 说明 |
|---|---|---|---|
| call.invite | C→S→对方 | callId, video, from, ts | 发起呼叫。对方不在线时服务端直接回 `call.reject{reason:"offline"}` |
| call.accept | 被叫→主叫 | callId, from | 接听。主叫收到后创建 offer |
| call.reject | 被叫→主叫 | callId, reason(busy/declined/timeout/offline), from | 拒接或忙 |
| call.hangup | 双向 | callId, reason, from | 挂断；主叫在对方未接时挂断 reason=cancelled，振铃超时 reason=timeout |
| call.sdp | 双向 | callId, type(offer/answer), sdp, from | SDP 交换 |
| call.ice | 双向 | callId, candidate, sdpMid, sdpMLineIndex, from | Trickle ICE |
| turn.get | C→S | – | 请求 TURN 凭证 |
| turn.creds | S→C | urls[], username, credential, ttlSeconds | coturn `use-auth-secret` 临时凭证，6 小时有效；username 为空表示只有 STUN |

通话结束后由主叫方发一条 `kind:"call"` 的消息作为通话记录，text 格式 `<voice|video>:<answered|missed|declined|cancelled>:<秒数>`。

coturn 监听 UDP/TCP 3478，中继端口 UDP 49160-49200，配置见 `deploy/turnserver.conf`。

## 1.3.0

见 [protocol-1.3.md](protocol-1.3.md)：`sticker` / `pat` / `card` 三种 kind，`once`（阅后即焚）字段，`active`/`presence` 的电量，`shared` 共享键值（`GET/PUT /shared/{key}` + `shared` 帧），`bot.ttl` 与 `POST /bot/ttl`，`call.emoji`，助手 hello 的 `users[]`，`edit` 行压缩，nginx `/stickers/` 表情库反代，schema 7。

`msg.send` 的 kind 全集（1.3）：text | image | audio | video | file | call | react | edit | ttl | del | clear | sticker | pat | card。

## 1.5.0

见 [protocol-1.5.md](protocol-1.5.md)：连接限流、孤儿媒体清理、`location` kind（`edit` 也可作用于它）、`call.media.screen`、`/keys` 4096 字符与 v2 密钥 bundle（周轮换、`e2e2:` / `LCE2` 密文格式）、`POST /bot/card`。

`msg.send` 的 kind 全集（1.5）：text | image | audio | video | file | call | react | edit | ttl | del | clear | sticker | pat | card | location。
