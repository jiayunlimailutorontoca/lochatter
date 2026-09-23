# 协议增量 1.3.0

相对 [protocol.md](protocol.md)。schema 为 7。新增字段均为可选；不识别这些字段的客户端须忽略它们。服务端、Android 客户端与 Hermes 插件以本文为准。

## 1. hello

- `peer` 增加 `battery`（整数，0–100）与 `charging`（布尔）。
- `bot` 的结构为 `{id, name, online, ttl}`。`ttl` 表示助手消息是否参与定时销毁，对应服务端设置 `bot_ttl`，默认 true。`bot` 帧同样携带 `ttl`。
- 助手连接（用户 0）的 `hello` 增加 `users: [{id, name}]`，内容为两名人类用户。人类连接没有该字段。
- 人类连接的 `hello` 增加 `shared: [{key, value, updatedAt}]`，为共享键值的全量快照。

## 2. active 与 presence

`active`（C→S）的结构为 `{fg, battery?, charging?}`。客户端在前后台切换时发送；电量变化达到或超过 5 个百分点，或充电状态变化时，也发送。

服务端在内存中保存每名人类用户最近一次上报的 `battery` 与 `charging`。值变化时向对端广播 `presence {user, online, lastSeen?, battery?, charging?}`。`online` 与 `lastSeen` 的规则与基线相同。`presence` 中的 `battery` 与 `charging` 可选。

## 3. 共享键值

共享键值保存双方共用的小配置，包括助手快捷指令、纪念日与自定义表情收藏。服务端不解析 `value`。客户端可以存放明文 JSON，也可以存放 `e2e:` 密文。

- `GET /shared` 返回 `[{key, value, updatedAt}]`。仅人类用户可调用。
- `PUT /shared/{key}` 的正文为 `{"value":"..."}`。`key` 须匹配 `^[a-z0-9_-]{1,32}$`，`value` 最长 65536 个字符。仅人类用户可写，助手返回 403。成功时返回 200 `{key, value, updatedAt}`，并向全部人类连接（含发送者）广播帧 `shared {key, value, updatedAt}`。
- 表结构：`shared(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL, updated_by INTEGER)`。

约定键：

| key | 内容 |
|---|---|
| `quick` | JSON 字符串数组。助手快捷指令，明文 |
| `anniv` | JSON。纪念日，由客户端以 `e2e:` 加密 |
| `stickers` | JSON。自定义表情收藏，由客户端以 `e2e:` 加密 |

## 4. 新增 kind

| kind | 发送者 | text | 规则 |
|---|---|---|---|
| `sticker` | 人类、助手 | 必填，最长 512 字符。人类之间为密文，发给助手时为明文 | 明文格式为 `bqb\|<path>\|<w>\|<h>` 或 `media\|<mediaId>\|<w>\|<h>`。`path` 为表情库相对路径。`media` 形式必须同时填写 `mediaId`，服务端校验对象存在并计入引用。`bqb` 形式不带 `mediaId`。引用摘要为 `[表情]`。属于内容消息，参与定时销毁 |
| `pat` | 人类 | 可选，最长 64 字符，可以为密文 | 不能发给助手。客户端显示为系统行。摘要为 `[拍一拍]`。属于内容消息 |
| `card` | 仅助手 | 必填，最长为文本上限 | 助手主动播报。第一行为标题，其余为正文。摘要取第一行，最长 120 字符。人类发送则返回 `bad_request`。属于内容消息 |

`msg.send` 与同步下来的消息增加可选布尔字段 `once`。它只对人类之间的 `image` 与 `video` 有意义。服务端将其存入 `messages.once` 并原样回显。接收方打开后，由接收方发送 `del`。

## 5. edit 压缩

插入 `kind:"edit"` 之前，删除同一发送者、且 `text` 以 `<目标 id>|` 开头的旧 `edit` 行。每条消息只保留最新一次编辑。`seq` 出现空缺是正常结果。

## 6. 助手与定时销毁

设置项 `bot_ttl` 默认 `"1"`。`POST /bot/ttl {"enabled": bool}` 仅人类用户可调用。修改后通过 `bot` 帧通知，帧中带 `ttl`。

计算 `expiresAt` 的条件为：`kind` 属于内容类型，且（`bot_ttl` 为开，或发送者与接收者都不是助手）。因此 `bot_ttl` 打开时，发给助手的消息与助手发出的消息同样过期。

`del` 与 `clear` 也发给助手连接，供助手清理本地媒体缓存。助手的 `sync` 过滤同样放行这两类控制消息。

## 7. 通话与表情库

`call.emoji` 的字段为 `{callId, emoji, from}`。`emoji` 最长 16 个字符。服务端只转发并填写 `from`。助手不能发送该帧。共享白板使用 WebRTC DataChannel，标签为 `wb`，不经过聊天服务。

nginx 增加 `location /stickers/`，反向代理到 `https://zhaoolee.com/ChineseBQB/`，设置 `proxy_ssl_server_name on` 与 `Host: zhaoolee.com`。缓存目录为 `/var/cache/nginx/stickers`，`max_size=2g`，`inactive=30d`，状态码 200 的缓存有效期 30 日。该位置不要求认证。`proxy_cache_path` 位于 `deploy/nginx-stickers-cache.conf`，由 `deploy.sh` 安装到 `/etc/nginx/conf.d/`。

客户端访问路径：

- `${server}/stickers/catalog/index.json`
- `${server}/stickers/catalog/search.json`
- `${server}/stickers/<src>`
- `${server}/stickers/<thumb>`

`index.json` 的结构为 `{"categories":[{slug, number, title, folder, count, cover, url, bytes, download}]}`。`search.json` 为对象数组，元素含 `id, name, path, label, src, thumb, width, height, animated, bytes, category, categoryUrl, categoryTitle, folder`。

## 8. 版本对应关系

- 服务端版本 1.3.0，schema 7。新增 `messages.once` 与表 `shared`。
- Android `versionCode` 20，`versionName` 1.3.0，本地 SQLite schema 7。
- Hermes 插件 `plugin.yaml` 版本 1.3.0。
