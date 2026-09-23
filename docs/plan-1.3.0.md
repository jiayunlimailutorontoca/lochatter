# lochatter 1.3.0 版本计划

日期：2026-09-22。基于对三端代码、服务器数据库（`/var/lib/chatter/chatter.db`）里助手对话记录、以及 NAS 上 Hermes 0.21.3 网关源码和日志的排查。

## 0. 这一版解决什么

1. 助手不再发重复消息，打断它也不会丢消息、不会刷屏。
2. 助手页有预设命令（新对话 / 停止 / 排队 / 状态 …）和几条家务快捷指令。
3. 助手页的消息也遵守「消息定时销毁」。
4. 表情包：内置一个可搜索的表情库 + 自定义收藏。
5. 回复排版正常（Markdown 渲染），问答配对正确，通知只发给发问的人。
6. APK 在本机编译，编不过就回退服务器。
7. 第 9 节的新功能全部纳入（转发给助手、收藏、拍一拍、语音转文字、链接预览、媒体库、定时发送、阅后即焚、通话浮层与统计、助手朗读与记忆、播报卡片、电量状态、纪念日、小组件、白板、背景与气泡样式、流量保护、通知细分）；只有「加密备份到 NAS」推迟到 1.4。

## 1. 助手：重复消息与打断（P0）

### 1.1 根因（已确认）

- 服务器库里同一条助手回复普遍是 3 份（如 seq 2009 / 2011 / 2014 是同一段长回复；「↪ Redirected current run」「⚡ Stopped」「⚡ Interrupting current task」都是每条 ×3）。
- Hermes 日志同一时刻：`Send failed (attempt 1/2, retrying in 2.2s): timeout` → `attempt 2/2` → `Failed to deliver response after 2 retries`，随后适配器自己报 `no frames for 40s; dropping socket`。
- 原因链：适配器在 WebSocket 读循环里 `await self.handle_message(event)`（[adapter.py:262](../hermes/lochatter/adapter.py)），而 Hermes 的 `handle_message → _handle_message_while_active → await _busy_session_handler(...)` 在会话忙的时候（打断、等会话锁「⏳ Another Hermes process is using this session」）会在里面阻塞几十秒。读循环一停，`msg.ack` 和 `pong` 全收不到 → `send()` 等 ack 10 秒超时 → 返回 `retryable` → Hermes 基类用**新 uuid** 重发两次 → 服务器存三份 → 最后再补一条「⚠️ Message delivery failed」。心跳同时误判连接死了，断线重连，重连又触发 sync 补拉。
- 所以「打断的时候尤其严重」：打断正好走最阻塞的那条路径。

### 1.2 修法（Hermes 适配器，`hermes/lochatter/adapter.py`）

1. `_on_message` 里改成 `asyncio.create_task(self.handle_message(event))`，读循环永不阻塞。这是主修。
2. 发送幂等：`send()` 内维护「最近 120 秒 内容 → 已用 message id」缓存，同内容重试复用同一个 id（服务器按 id 去重、返回原 seq），即便再有超时也不会出现第二份。
3. `ACK_TIMEOUT` 10 s → 30 s；ack 超时时先查缓存里有没有该 id 的 `msg.ack` 晚到，再决定报错。
4. 出站过滤 Hermes 的控制性文案（参考 Hermes 自带 discord 适配器的正则）：`↪ Redirected current run`、`⚡ Interrupting current task`、`⏳ Another Hermes process…`、`⏳ Still waiting…`、`⚠️ Message delivery failed…`、`💾 Self-improvement review:`、`Stopped waiting for another Hermes process`。这些不进聊天，只进日志。
5. 持久化 `_last_seq` 到 `HERMES_HOME/lochatter.seq`：Hermes 重启期间发的提问，重连后能补拉，不再永远「等待回复…」。
6. typing 心跳：在 `handle_message` 任务运行期间每 4 秒发一次 `typing`，直到第一条 `send()` 发出；手机端超时从 6 秒改为 8 秒。

### 1.3 Hermes 配置（NAS `config.yaml`）

- `display.busy_input_mode`: `interrupt` → `steer`。现在每条新消息都硬打断上一轮，打断要等 API 中止，经常超过 60 秒然后「Your message was not processed」把消息丢掉。`steer` 把新消息塞进正在跑的这一轮，不丢、不等。用户想真正停下来用「停止」按钮（发 `/stop`）。
- `display.platforms.lochatter.busy_steer_ack_enabled: false`（关掉「↪ Redirected current run」回显）。
- 保留现有 `tool_progress: off`、`interim_assistant_messages: false`、`long_running_notifications: false`、`busy_ack_detail: false`。
- 把上述项写进 README 的 Hermes 配置段，并在 `hermes/lochatter/plugin.yaml` 里给出推荐值。

### 1.4 验收

- `tools/e2e.py` 加一段：模拟 ack 延迟 15 秒，助手 `send()` 同内容重试两次，库里只出现一条。
- 手机上连续发 3 条问题并中途按「停止」，助手页每个问题至多一条回复，没有任何 ⚡ / ↪ / ⏳ / ⚠️ 开头的系统行。

## 2. 助手：预设命令（P0）

Hermes 网关命令是明文 `/xxx`（已验证 `/new`、`/stop` 可用，`/clear` 不存在）。全部在客户端做，服务端不动。

### 2.1 助手页「/」菜单（输入框左侧一个按钮，或输入 `/` 自动弹）

| 显示 | 发送 | 说明 |
|---|---|---|
| 新对话 | `/new` | 清空 Hermes 上下文（本地记录保留） |
| 停止 | `/stop` | 打断当前回答 |
| 排队说 | `/queue <文字>` | 等它做完再处理 |
| 插一句 | `/steer <文字>` | 不打断，下一次工具调用后插入 |
| 顺便问 | `/btw <文字>` | 不影响主线的旁支提问 |
| 状态 | `/status` | 模型、上下文、token |
| 压缩上下文 | `/compress` | 上下文快满时用 |
| 撤回上一问 | `/undo` | 回退一轮重问 |
| 重试 | `/retry` | 上一条重发 |
| 全部命令 | `/commands` | Hermes 自己的分页列表 |

- 顶栏再放两个常驻按钮：停止、新对话。
- 适配器把 `/clear` 映射成 `/new`，把 `/帮助` `/停止` `/新对话` 这类中文别名也映射过去。
- 命令类消息在主聊天里不生成「你 → 管家」折叠行；命令回复不计入「已回复」配对。

### 2.2 家务快捷指令（设置 → 助手 → 快捷指令，可增删改，两人共享）

预置四条：「家里现在什么状况」「全屋关灯关窗帘」「今天/明天天气」「让客厅小爱说：…」。存服务器 `store` 表，用现有的 `bot` 帧广播给两人。

## 3. 助手：定时销毁适配（P1）

现状：服务端对 `to:"bot"` 和 `from=0` 的消息一律不设 `expires_at`（[Hub.cs:471](../server/Chatter.Server/Ws/Hub.cs)），手机端也同样跳过。

- 服务端：助手相关消息同样套用当前 TTL（`ContentKinds` 里 text/image/audio/video/file，包括助手发的）。到期由现有 `sweepExpired` 删除并广播 `del`；广播时也发给助手连接，适配器收到就清掉本地 `image_cache/lochatter` 里对应文件。
- 手机端：`sendText/sendMedia/sendVoice/sendFile` 的 `expiresAt = if (toBot) null else expiry()` 改为统一 `expiry()`；助手页气泡角标显示沙漏与剩余时间（复用主聊天的实现）。
- 设置 → 助手 加一项「助手页跟随定时销毁」（默认开），关掉则维持现状。
- 说明写清楚的限制：销毁只清聊天记录，Hermes 会话上下文里的内容不会同时消失；想彻底清，用「新对话」。可选做法：到期删除的问答比例超过一半时，适配器自动发一次 `/compress`。

## 4. 助手：排版、配对、通知（P1）

1. **Markdown 渲染**：助手气泡接一个轻量 Compose Markdown 渲染（`com.mikepenz:multiplatform-markdown-renderer-m3` 或 `io.noties:markwon` 包一层 AndroidView），支持加粗、列表、标题、行内代码、代码块、链接；人类气泡不变。同时 `platform_hint` 改为「只用加粗和列表，不用表格和标题」。
2. **问答配对**：适配器不再用全局 `_pending_mention`，改为 `MessageEvent.metadata["lochatter_reply_to"] = mid`，`send()` 从 `metadata` 里取；并发两问时各归各。主聊天「已回复」、通知「只通知发问者」都随之正确。
3. **助手知道你们的名字**：服务端给助手的 `hello` 加 `users:[{id,name}]`，改名时 `bot` 帧一起带；适配器填 `_names`。
4. **离线提示**：助手离线时发问，输入框上方一行「它现在不在线，上线后会看到」（不阻止发送）。
5. **主聊天折叠行**点击后滚到对应问题；助手页加「回到底部」按钮。

## 5. 表情包（P1）

### 5.1 表情库选型

**ChineseBQB**（github.com/zhaoolee/ChineseBQB，16k star，2026-09 仍在更新）：

- 5871 张，110+ 个分类包（每包有封面、张数、zip 下载地址）。
- 有现成 JSON 目录：`https://zhaoolee.com/ChineseBQB/catalog/index.json`（分类）与 `catalog/search.json`（全部图片：`src`/`thumb`(webp)/`width`/`height`/`animated`/`bytes`/`categoryTitle`），相对路径以 `https://zhaoolee.com/ChineseBQB/` 为根。
- 注意：仓库没有 LICENSE 文件（GitHub 显示 license: null），图片版权属各原作者。两人私用没问题，不要拿去商用。
- 备选：Telegram 贴纸集（需要 Bot API 与翻墙，动态 .tgs 要 Lottie）、signalstickers.org（需解包密钥）。都比 ChineseBQB 麻烦，不作为默认。

### 5.2 做法

- **服务端**：`deploy/stickers-sync.sh` 每周拉一次两份 JSON 和 zip 分包到 `/var/www/chatter/stickers/`，nginx 直接静态服务；`GET /stickers/catalog.json` 给客户端。这样手机不依赖 zhaoolee.com 的可用性，也不把访问记录留在第三方。首次全量约 3 GB（可只同步前 30 个热门分类，设置里可再加）。
- **协议**：新 `kind:"sticker"`，`text` 为表情 id（库内 id 或自定义表情的 media id），走 e2e 与普通文字一样加密；服务端只做 kind 白名单。助手页发表情为明文，Hermes 侧当图片收（适配器把 sticker 解析为 `image_path`）。
- **Android**：输入栏加表情按钮 → 面板三栏：最近使用 / 收藏 / 分类浏览 + 搜索（按 `label`、`categoryTitle` 做本地模糊搜索）；气泡里表情按固定宽度 140dp 渲染、无气泡背景、GIF 动图播放（Coil 已带 gif 解码）。
- **自定义表情**：长按任意图片 → 「添加到表情」，上传到 `/media` 后写入服务器 `stickers_fav` 表（两人共享），收藏页显示；长按收藏可删除。
- **通知与导出**：通知文案「[表情]」；导出聊天记录写表情 id 与分类名。

## 6. 数据与性能（P2）

- 流式 `edit` 行合并：服务端插入新 `edit` 行时删除同作者、同目标的上一条 `edit` 行（[Repos.cs:358](../server/Chatter.Server/Storage/Repos.cs)），每条消息最多留一条编辑记录；现库 981 行里 65 行是助手 edit，再加流式频率更高后会更明显。
- 助手页 `botTick` 触发的 `botHistory(limit)` 全量重查改为按 id 就地更新。
- 适配器 `edit_message` 节流 1 秒。

## 7. 构建与发布

- **本机编译**：JDK 17 / Gradle 8.9 / SDK 35 已装在 `C:\Android`，`android/local.properties` 已写。当前 `:app:compileReleaseJavaWithJavac` 报 Windows「文件名、目录名或卷标语法不正确」，还没定位（怀疑 JAVA_HOME 带空格或 网盘同步目录 路径）。给它最多半小时：把 JDK 拷到 `C:\Android\jdk`、`org.gradle.java.home` 指过去、`--stacktrace` 看具体文件；不行就按你说的走服务器 `deploy/android-build.sh`。
- 补 `gradlew` wrapper 进仓库，两边命令一致。
- **服务器**：`git push server main` → `deploy.sh`；e2e 全绿再发。
- **NAS**：`scp hermes/lochatter` 到 `/path/to/hermes/data/plugins/`，改 `config.yaml`（第 1.3 节），`docker restart hermes`，看日志 `connected as`。

## 8. 顺序与工作量

| 步 | 内容 | 涉及 | 预计 |
|---|---|---|---|
| 1 | 1.2 适配器五项修改 + 1.3 配置 + 部署 NAS 验证 | hermes/ | 半天 |
| 2 | 2 预设命令与快捷指令 | android/, server store | 半天 |
| 3 | 4.1 Markdown、4.2 配对、4.3 名字下发 | android/, hermes/, server | 1 天 |
| 4 | 3 定时销毁适配 | server, android | 半天 |
| 5 | 5 表情包（库同步 → 协议 → 面板 → 自定义） | deploy/, server, android, hermes/ | 2 天 |
| 6 | 9.1 ★ 转发给助手、收藏夹、拍一拍 + 9.4 ★ 电量状态 | android/, 协议小改 | 1 天 |
| 7 | 9.3 助手朗读、记忆可见、播报卡片、按住说话转文字 | android/, hermes/ | 1 天 |
| 8 | 9.1 语音转文字、链接预览、媒体库页、定时发送、单条阅后即焚 | android/, server(阅后即焚 del) | 2 天 |
| 9 | 9.2 通话表情浮层、时长与流量统计、铃声与静音时段 | android/ | 1 天 |
| 10 | 9.4 纪念日倒计时、桌面小组件、共享白板 | android/ | 2 天 |
| 11 | 9.5 聊天背景与气泡样式、Wi-Fi 才下载原图、通知细分 | android/ | 1 天 |
| 12 | 6 性能项、e2e 补测、README/protocol.md 更新、发版 | 全部 | 1 天 |

合计约 14 个工作日。第 1 步单独先发一个 1.2.1，因为它不改协议、不用升级 APK，只换 NAS 上的插件和配置；之后按步骤逐个提交，每完成 3 到 4 步出一个内测 APK。

协议改动汇总（一次性写进 protocol.md，schema 升到 7）：`kind:"sticker"`；`react` 扩「拍一拍」和通话浮层 emoji；`active` 帧带 `battery`/`charging`；`hello` 给助手带 `users[]`；`kind:"card"`（助手播报卡片）；「看一次」图片用 `text` 里的 `once` 标记 + 打开后客户端发 `del`；快捷指令与纪念日存服务器 `store` 表并随 `bot`/`hello` 下发。

## 9. 新功能与交互（已确认全部进 1.3.0，除「加密备份到 NAS」推迟）

2026-09-22 确认：本节除 9.5 的「加密备份到 NAS」外全部纳入 1.3.0。★ 表示改动小、优先做。

### 9.1 主聊天交互

- ★ **转发给助手**：长按主聊天里任何一条（文字 / 图片 / 文件）→「问助手」，明文复制到助手页并带上你追加的问题。现在助手只能看到你在它页面里说的话，这条打通两个页面。
- ★ **收藏夹**：长按 → 收藏；设置里一个「收藏」页，按时间列出，点开跳转。两人各自的收藏各自保存，只在本机。
- ★ **拍一拍**：双击对方头像 / 气泡，对方手机震一下并在时间线里显示「你拍了拍 xx」。协议用现有 `react` 控制消息扩一个 emoji，零服务端改动。
- **语音转文字**：长按语音条 → 转文字，走 Android 系统 `SpeechRecognizer`（离线包，不出本机）；转好的文字缓存在本地库。助手页的语音默认转文字后再发给 Hermes，省得依赖 NAS 的 STT。
- **链接预览卡片**：文字里有 URL 时手机自己抓 OpenGraph 标题和缩略图，卡片显示；设置里可关（抓取会暴露本机 IP 给目标站）。
- **媒体库页**：菜单 →「图片与文件」，按月份网格显示所有图片 / 视频 / 文件，翻旧照片不用往上刷。
- **定时发送**：输入框长按发送键 → 选时间，到点由手机本地闹钟发出（手机得活着；被杀后台的机型退化成下次打开时发）。
- **单条阅后即焚**：发图时勾「看一次」，对方打开后 10 秒删除，双方都删。现在只有全局 TTL。
- **回到底部 / 未读锚点**在助手页补齐（第 4 节已列）。

### 9.2 通话

- ★ **通话中表情浮层**：通话页点一个 ❤️ / 👋，对方屏幕上飘一下。走现有 `call.media` 类信令帧。
- **通话时长与流量统计**：设置里看本月通话分钟数和用了多少流量。
- **来电铃声与震动模式**：可选铃声、静音时段。

### 9.3 助手相关

- ★ **助手朗读**：助手页长按回复 → 朗读，用系统 TTS；「开车模式」开关下自动朗读新回复。
- ★ **助手记忆可见**：设置 → 助手 →「它记住的事」，实际发 `/memory` 并把回复渲染成列表；不满意可让它删。
- **助手主动卡片**：Hermes cron 播报（天气、提醒）用一种「卡片」样式显示（标题 + 正文 + 时间），和普通对话区分开；协议加 `kind:"card"` 或在 text 里约定前缀。
- **语音直接问助手**：助手页麦克风改成「按住说话 → 本机转文字 → 直接发」，不发语音文件。

### 9.4 两人专属

- ★ **状态与电量**：顶栏对方名字旁显示电量和「在充电」（`active` 帧带 `battery`），情侣常用的小功能，一行改动。
- **纪念日与倒计时**：设置里加纪念日，主聊天当天顶部一条提示；桌面小组件显示倒计时和对方在线状态。
- **桌面小组件**：对方在线 / 最近一条消息 / 一键语音、视频、问助手。
- **一起画**：共享白板，笔迹走 WebRTC DataChannel（通话里的信道已经有），不落库。

### 9.5 设置与系统

- ★ **聊天背景与气泡样式**：几张内置背景 + 从相册选，气泡圆角 / 主题色（现在只有玫瑰色一套，深色跟随系统）。
- （推迟到 1.4）**加密备份到 NAS**：整库 + 媒体用 e2e 密钥派生的口令打包，通过 WebDAV / SMB 传到 NAS，换手机整体恢复（现在只能导出密钥和文本记录）。
- **Wi-Fi 下才自动下载原图 / 视频**：流量保护开关。
- **通知细分**：引用了你的消息单独提示音；对方「拍一拍」单独提示。
