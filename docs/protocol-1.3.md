# 1.3.0 协议增量（schema 7）

在 [protocol.md](protocol.md) 基础上新增/修改。三端（server / android / hermes）以此为准。所有新字段都是可选的，旧客户端忽略即可。

## hello

- `peer` 增加 `battery?: int`(0-100) 与 `charging?: bool`。
- `bot` 变为 `{id, name, online, ttl}`；`ttl` 为「助手消息是否跟随定时销毁」（服务端设置 `bot_ttl`，默认 true）。`bot` 帧同样带 `ttl`。
- 助手连接（用户 0）的 hello 多一个 `users: [{id, name}]`（两个人类的 id 和名字），人类连接没有此字段。
- 人类连接的 hello 多一个 `shared: [{key, value, updatedAt}]`（共享键值全量快照，见下）。

## active / presence

- `active` C→S：`{fg, battery?, charging?}`。手机在前后台切换、电量每变化 ≥ 5% 或充电状态变化时发送。
- 服务端为每个人类用户记住最近一次上报的 battery/charging（内存即可）；值变化时向对方广播 `presence {user, online, lastSeen?, battery?, charging?}`（online/lastSeen 按现有逻辑填）。`presence` 帧的 `battery`/`charging` 可选。

## 共享键值（shared）

两人共用的小配置：助手快捷指令、纪念日、自定义表情收藏。值对服务端不透明（客户端可以放明文 JSON，也可以放 `e2e:` 密文）。

- `GET /shared` → `[{key, value, updatedAt}]`（人类）。
- `PUT /shared/{key}`，body `{"value": "..."}`，key 匹配 `^[a-z0-9_-]{1,32}$`，value ≤ 65536 字符；人类才可写；助手 403。返回 200 `{key, value, updatedAt}`，并向**所有人类连接**（含发送者自己）广播帧 `shared {key, value, updatedAt}`。
- 表 `shared(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL, updated_by INTEGER)`。
- 约定的 key：`quick`（JSON 字符串数组，助手快捷指令，明文）、`anniv`（JSON，纪念日，客户端 e2e 加密）、`stickers`（JSON，自定义表情收藏，客户端 e2e 加密）。

## msg.send 新 kind

| kind | 谁能发 | text | 说明 |
|---|---|---|---|
| `sticker` | 人类、助手 | 必填，≤ 512 字符，人类之间 e2e 加密，发给助手时明文 | 表情。明文格式 `bqb\|<path>\|<w>\|<h>`（`path` 是表情库相对路径，如 `media/xxx.jpg`）或 `media\|<mediaId>\|<w>\|<h>`（自定义表情，同时把 `mediaId` 字段也填上，服务端校验存在并参与引用计数）。`bqb` 形式不带 mediaId。引用摘要 `[表情]`。计入 ContentKinds（跟随定时销毁）。 |
| `pat` | 人类 | 可选（≤ 64，可 e2e） | 拍一拍。不能发给助手。客户端显示为系统行「你拍了拍 xx」并震动。摘要 `[拍一拍]`。计入 ContentKinds。 |
| `card` | 只有助手 | 必填 ≤ MaxTextChars | 助手主动播报（cron 天气、提醒）。第一行是标题，其余正文（markdown）。摘要为第一行（≤ 120）。人类发 → bad_request。计入 ContentKinds。 |

`msg.send` 与 `msg`（msg.new / msg.batch 里的消息）新增可选布尔字段 `once`：阅后即焚，只对 `image` / `video` 有意义，人类之间；服务端原样存储在 `messages.once INTEGER` 并回显。接收方打开后由接收方客户端发 `del`。

## edit 压缩

插入 `kind:"edit"` 行之前，删除同一 `from_user`、text 以 `<目标 id>|` 开头的旧 `edit` 行（每条消息只保留最新一条编辑记录）。seq 出现空洞是正常的。

## 助手与定时销毁

- 设置项 `bot_ttl`（SettingsRepo，默认 "1"）。`POST /bot/ttl {"enabled": bool}`（人类），改后 NotifyBot（bot 帧带 `ttl`）。
- 计算 expiresAt 时：`ContentKinds.Contains(kind) && (bot_ttl || (!IsBot(uid) && !toBot))`。也就是 `bot_ttl` 打开时，发给助手的和助手发的一样过期。
- `del` / `clear` 控制条目也要发给助手连接（无论目标是谁），助手据此清理本地媒体缓存；`sync` 的 FilterForBot 同样放行 `del` / `clear`。

## 通话

- `call.emoji {callId, emoji, from}`：通话中的表情浮层，emoji ≤ 16 字符，服务端只转发并填 `from`，助手不可发。
- 共享白板走 WebRTC DataChannel（label `wb`），不经服务器。

## 表情库静态资源

nginx 新增 `location /stickers/`：反代到 `https://zhaoolee.com/ChineseBQB/`（`proxy_ssl_server_name on`，`proxy_set_header Host zhaoolee.com`），带 `proxy_cache`（`/var/cache/nginx/stickers`，`max_size=2g`，`inactive=30d`，`proxy_cache_valid 200 30d`），无需认证。`proxy_cache_path` 放在 `deploy/nginx-stickers-cache.conf`，deploy.sh 安装到 `/etc/nginx/conf.d/`。客户端访问：`${server}/stickers/catalog/index.json`、`${server}/stickers/catalog/search.json`、`${server}/stickers/<src>`、`${server}/stickers/<thumb>`。

`index.json`：`{"categories":[{slug, number, title, folder, count, cover{...}, url, bytes, download}]}`（114 个分类）。
`search.json`：`[{id, name, path, label, src, thumb, width, height, animated, bytes, category, categoryUrl, categoryTitle, folder}]`（5871 张，1280 张动图；3.3 MB）。

## 版本

- 服务端 `<Version>1.3.0</Version>`，schema 7（`messages.once`，表 `shared`）。
- 安卓 versionCode 20 / versionName 1.3.0，本地 SQLite schema 7。
- Hermes 插件 plugin.yaml version 1.3.0。
