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
