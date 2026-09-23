# 协议增量 1.6（相对 1.5）

1.6 的主题：位置要像微信（地图预览、地图选点、附近地点、导航），文件和图片一键保存，
手机没有语音识别（国产 ROM / Android 12 无 Google 服务）时由服务器转文字。
服务器版本 1.6.0，数据库 schema 不变（7）。

## 0. 1.5.0 的 bug：`e2e2:` 密文被当成明文

1.5.0 客户端两边都有前向保密密钥后，文本一律以 `e2e2:<uid>:<se>.<re>:…` 加密；服务器只认 `e2e:` 前缀，
于是 `location` / `react` / `sticker` 三种要校验明文格式的消息全部被 `bad_request` 拒掉（位置发不出去），
`text` 的引用预览被截到 120 字（对方解不开引用）。1.6.0 服务器把两代前缀都当密文（`Hub.IsE2e` / `MessageRepo.IsE2e`），
位置密文上限从 512 提到 1024 字（120 字中文地址的 v2 密文约 600 字）。`tools/e2e.py` 加了对应用例。

## 1. `hello` 增加 `features`

```json
"features": {"stt": true, "geo": true, "tiles": true}
```

- `stt`：`POST /stt` 可用（服务器配置了转写后端）。
- `geo`：`/geo/*` 可用（服务器配置了高德 Web 服务 key）。
- `tiles`：`/tiles/` 可用（nginx 里配置了瓦片反代）。
缺省全为 false；老客户端忽略这个字段。

## 2. 位置

线上格式不变：`lat,lng|accuracy|address|live`，坐标一律 **WGS-84**（手机 GPS 原始坐标）。
服务器和高德之间的 GCJ-02 转换在服务器做，客户端不用管。

### 2.1 瓦片 `GET /tiles/{z}/{x}/{y}.png`（nginx，无鉴权）

标准 XYZ 瓦片（Web Mercator，256px，z ≤ 19），上游 OpenStreetMap，nginx `proxy_cache` 30 天，带 UA。
客户端 `TileMap` 用它画可拖拽缩放的地图；换上游只改 nginx，不用发版。

### 2.2 `GET /geo/regeo?lat=&lng=`（鉴权）

逆地理：返回这个点的中文地址和附近地点（微信选点页的列表就是它）。

```json
{"address": "上海市黄浦区南京东路街道人民广场", "name": "人民广场",
 "pois": [{"name": "人民广场", "address": "南京西路 75 号", "lat": 31.2304, "lng": 121.4737, "distance": 12, "type": "风景名胜"}]}
```

`pois` 最多 20 个，按距离排序；`lat`/`lng` 已转回 WGS-84。找不到时 `address` 为 `"纬度 x，经度 y"`，`pois` 为空。

### 2.3 `GET /geo/around?lat=&lng=&q=&page=`（鉴权）

附近关键字搜索（半径 2 km，每页 20，`page` 从 1 起）。`q` 为空时返回附近的热门地点。返回同 `pois`。

### 2.4 `GET /geo/search?q=&lat=&lng=`（鉴权）

全城搜索（选点页顶部的搜索框；`lat`/`lng` 用来确定城市和排序）。返回同 `pois`。

### 2.5 错误

- 503 `{"code":"geo_unavailable"}`：服务器没配 `CHATTER_AMAP_KEY`。
- 502 `{"code":"geo_upstream"}`：高德返回错误（`message` 带高德的 info）。
- 参数越界（纬度不在 ±90 内等）400。
服务器按每连接令牌桶限速（沿用 1.5 的 sends 桶）；客户端拖地图时至少 600 ms 才发一次 regeo。

## 3. 转文字 `POST /stt`（鉴权）

`multipart/form-data`：`file`（m4a/aac、ogg/opus、wav、mp3，≤ 20 MB，≤ 5 分钟）、可选 `language`（默认 `zh`）。

```json
{"text": "明天下午三点提醒我开会", "language": "zh", "durationMs": 3120}
```

- 503 `{"code":"stt_unavailable"}`：服务器没配后端。
- 413：太大。 502 `{"code":"stt_upstream"}`：后端失败。
服务器只是代理：`CHATTER_STT_URL`（OpenAI 兼容的 `/v1/audio/transcriptions`）、`CHATTER_STT_KEY`、`CHATTER_STT_MODEL`。
默认后端是 VPS 上的 `sherpa-onnx` + SenseVoice（`deploy/stt-setup.sh`），中文效果好、CPU 就够（一条 10 秒语音约 1 秒）。
客户端策略：有系统识别器时先用本机（免流量、有实时字幕）；没有（Android 12 国产 ROM 常见）就用 `/stt`。

## 4. 文件与图片

无协议变化。`GET /media/{id}?dl=1` 仍然返回 `Content-Disposition: attachment; filename=…`。
