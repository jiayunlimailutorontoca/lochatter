# 协议 2.2.0

本版本不增加帧类型，不增加消息 `kind`。数据库 schema 保持为 9。

变更限于配置来源。按下列变量完成配置后，服务进程与新安装的客户端可以使用该配置运行，无需改源码。

## 服务进程

进程通过标准配置读取环境变量。

| 变量 | 缺省 |
|---|---|
| `CHATTER_DATA` | 程序目录下的 `data/`。systemd 单元设置为 `/var/lib/chatter` |
| `ASPNETCORE_URLS` | 开发宿主的缺省地址。单元设置为 `http://127.0.0.1:5088` |
| `CHATTER_TURN_HOST` | 不提供 TURN |
| `CHATTER_TURN_SECRET` | 仅有 host、没有 secret 时只提供 STUN |
| `CHATTER_STT_URL` | 不提供 `POST /stt`。值必须是绝对 http(s) URL |
| `CHATTER_STT_KEY` | 转写请求不携带密钥 |
| `CHATTER_STT_MODEL` | `sense-voice` |
| `CHATTER_AMAP_KEY` | 不提供地名检索。位置消息仍可发送经纬度 |
| `CHATTER_TILES` | 瓦片可用，坐标系为 WGS-84。值为 `amap` 时坐标系为 GCJ-02 |
| `CHATTER_SWEEP_SECONDS` | 30 |
| `CHATTER_MEDIA_GRACE_SECONDS` | 7200。超过该时间且未被任何消息引用的上传由清理任务删除 |

`deploy/chatter.service` 加载 `/etc/chatter/turn.env`、`geo.env` 与 `stt.env`。这三个文件不进入版本库。`deploy.env` 仅由 shell 脚本读取，用于域名、证书目录与附加备份，不传入聊天进程。

## Android 构建期默认地址

写入新安装的默认服务器地址按以下顺序取值，命中即停止：

1. 环境变量 `CHATTER_SERVER_URL`
2. `android/server.properties` 的 `serverUrl`。该文件不进入版本库
3. `https://` 与 `CHATTER_DOMAIN` 拼接。`CHATTER_DOMAIN` 可带或不带 `https://` 前缀
4. `https://chat.example.com`

已安装客户端继续使用本地保存的地址，升级不修改该值。`deploy/android-build.sh` 在服务器上构建前，由 `CHATTER_DOMAIN` 导出 `CHATTER_SERVER_URL`。

Android `versionCode` 为 36，`versionName` 为 `2.2.0`。服务端 `Version` 为 `2.2.0`。`GET /healthz` 的 `version` 返回该版本号。

2.2.1 仅修订 Android 客户端，不新增帧或字段，服务端版本不变。表情面板在搜索框取得焦点、输入法弹出后保持打开，并排列在输入法上方。该客户端 `versionCode` 为 37，`versionName` 为 `2.2.1`。

2.2.2 的 Android `versionCode` 为 38，`versionName` 为 `2.2.2`。服务端 `Version` 为 `2.2.2`。`GET /healthz` 的 `version` 返回该版本号。数据库 schema 仍为 9。2.3.0 的版本号与行为见 [protocol-2.3.md](protocol-2.3.md)。

## 网页扫码登录

2.2.2 增加下列 HTTP 接口。不增加消息 `kind`，不增加 WebSocket 帧。票据只存在于进程内存。

网页不提供密码登录。页面不从 `sessionStorage`、`localStorage` 或持久 cookie 恢复会话。每次加载都生成新的临时 P-256 密钥对和新的登录票据。私钥只留在该次页面的内存中。刷新或关闭页面后，须重新扫码。

### 票据

`POST /auth/web-ticket` 无需认证。同一客户端 IP 每 60 秒最多创建 20 张票据，超出返回 429。成功时响应：

```json
{ "id": "<22 字符>", "expiresAt": 0 }
```

`id` 为 16 字节随机数的 Base64Url，字符集为 `[A-Za-z0-9_-]`，不含 `.`。`expiresAt` 为到期时刻的 Unix 毫秒。有效期 90 秒。

`GET /auth/web-ticket/{id}` 无需认证。`id` 不符合上述格式时返回 400。响应为 `{ "status": "pending", "box": null }`。`status` 取值：

| status | 含义 |
|---|---|
| pending | 手机尚未提交密文 |
| ready | 本响应携带 `box`。服务端随后删除该票据，`box` 只返回这一次 |
| expired | 已过期，且没有密文 |
| gone | 票据不存在，或密文已被取走 |

网页约每秒轮询一次。收到 `ready` 后，用页面内存中的私钥解开 `box`。

`POST /auth/web-ticket/{id}` 使用手机的 Bearer 令牌。正文为 `{ "box": "<标准 Base64>" }`。`box` 长度须在 32 与 65536 之间，字符限于 `A-Za-z0-9+/=`。票据不是待提交状态时返回 409。成功返回 204。服务端不解析 `box`。

`POST /auth/web-token` 使用手机的 Bearer 令牌。服务端删除该用户 `device` 恰好为 `web` 的既有令牌，断开这些令牌上的连接，再签发一个新令牌，`device` 为 `web`。响应为 `{ "token": "..." }`。该令牌只出现在这一次响应以及随后的 `box` 中。手机自己的令牌不变。

### 二维码

网页将下列文本画成二维码。手机只读取屏幕上的这一串，不向服务端索取网页公钥。

```text
lochatter-web:<id>.<spki>
```

`<id>` 为 22 字符。紧随一个 `.`。`<spki>` 为网页临时公钥的 SPKI，标准 Base64，解码后为 91 字节。前缀与换机码 `lochatter1:` 不同。

### 密文

手机先调用 `POST /auth/web-token`，再将与换机载荷相同的 JSON 封入 `box`，然后调用 `POST /auth/web-ticket/{id}`。字段为 `server`、`token`、`userId`、`userName`、`peerId`、`peerName`、`botName`、`keyRing`、`e2ePriv`、`e2ePub`、`peerPub`。其中 `token` 是新的网页令牌。`keyRing` 为密钥环的 JSON 文本。

`box` 解码后的字节为：临时公钥 SPKI（91 字节）‖ 随机数（12 字节）‖ AES-256-GCM 密文与 16 字节标签。

密钥派生：

1. ECDH。手机使用临时私钥，对方公钥是二维码中的网页公钥。P-256 的共享秘密取 X 坐标，32 字节。
2. salt 为 `SHA-256(临时公钥 SPKI ‖ 网页公钥 SPKI)`。顺序固定，两侧公钥不排序。
3. HKDF-SHA256。info 为 UTF-8 字符串 `lochatter-web-login-v1`，输出 32 字节。
4. AES-256-GCM 的附加认证数据为票据 `id` 的 UTF-8。

服务端看不到私钥，也看不到解开后的令牌与密钥环。

网页解开后，把令牌写入名为 `chatter` 的会话 cookie：`Path=/`、`Secure`、`SameSite=Strict`，不设 `Max-Age`。该 cookie 只供当次 `GET /ws` 使用。页面脚本在每次加载时先清除该 cookie，并删除名为 `lochatter` 的 `sessionStorage` 与 `localStorage` 项。因此刷新不能延续上次会话。令牌不得写入 URL。

聊天范围仍为文本与图像。其他 `kind` 显示为一行摘要。消息加密仍使用 `e2e2:` / `LCE2`；没有 epoch 密钥时退回 `e2e:` / `LCE1`。
