# 协议增量 1.6.0

相对 1.5.0。服务端版本 1.6.0。schema 保持为 7。

## 1. 密文判定

1.5.0 客户端在双方都拥有前向密钥后，将文本加密为 `e2e2:<uid>:<se>.<re>:…`。1.5.0 服务端只把前缀 `e2e:` 视为密文，因此需要校验明文格式的 `location`、`react` 与 `sticker` 被 `bad_request` 拒绝，位置消息无法发送。`text` 的引用预览也被截断到 120 字，对端无法解密该预览。

1.6.0 起，`Hub.IsE2e` 与 `MessageRepo.IsE2e` 将 `e2e:` 与 `e2e2:` 都视为密文。位置密文上限由 512 调整为 1024 个字符。`tools/e2e.py` 包含对应用例。

## 2. hello.features

```json
"features": {"stt": true, "geo": true, "tiles": true}
```

- `stt`：`POST /stt` 可用，即已配置转写后端。
- `geo`：`/geo/*` 可用，即已配置地图 Web 服务密钥。
- `tiles`：`/tiles/` 可用，即 nginx 已配置瓦片反代。

缺省均为 false。旧客户端忽略该对象。1.7 在此对象上增加 `tileDatum`。

## 3. 位置与地理接口

线上位置格式不变：`lat,lng|accuracy|address|live`。坐标一律为 WGS-84，即设备 GPS 的原始坐标。服务端与地图供应商之间的 GCJ-02 转换在服务端完成。

### 3.1 瓦片

`GET /tiles/{z}/{x}/{y}.png` 由 nginx 提供，不要求认证。格式为 XYZ（Web Mercator，256 像素，`z` 最大 19）。上游为 OpenStreetMap，`proxy_cache` 30 日，请求带 User-Agent。更换上游只修改 nginx，不要求发布新客户端。

### 3.2 逆地理

`GET /geo/regeo?lat=&lng=`，要求认证。返回该点的地址与附近地点。

```json
{
  "address": "示例路 1 号",
  "name": "示例地点",
  "pois": [
    {"name": "示例地点", "address": "示例路 1 号", "lat": 0.0, "lng": 0.0, "distance": 12, "type": "地名"}
  ]
}
```

`pois` 最多 20 条，按距离排序。返回的 `lat` 与 `lng` 已转回 WGS-84。无结果时 `address` 为 `"纬度 x，经度 y"`，`pois` 为空数组。

### 3.3 附近与城市检索

`GET /geo/around?lat=&lng=&q=&page=`，要求认证。半径 2 km，每页 20 条，`page` 从 1 起。`q` 为空时返回附近地点。响应元素与 `pois` 相同。

`GET /geo/search?q=&lat=&lng=`，要求认证。按城市检索；`lat` 与 `lng` 用于确定城市与排序。响应元素与 `pois` 相同。

### 3.4 错误

- 503 `{"code":"geo_unavailable"}`：未配置 `CHATTER_AMAP_KEY`。
- 502 `{"code":"geo_upstream"}`：上游返回错误，`message` 含上游信息。
- 400：参数越界，例如纬度不在 ±90 内。

地理接口沿用 1.5 的发送速率桶。客户端拖动地图时，两次 `regeo` 的间隔不得小于 600 ms。

## 4. 转写

`POST /stt`，要求认证。正文为 `multipart/form-data`：字段 `file`（m4a/aac、ogg/opus、wav 或 mp3，不超过 20 MB，不超过 5 分钟），可选 `language`，默认 `zh`。

```json
{"text": "转写结果", "language": "zh", "durationMs": 3120}
```

- 503 `{"code":"stt_unavailable"}`：未配置后端。
- 413：体积超限。
- 502 `{"code":"stt_upstream"}`：后端失败。

服务端只做代理。相关变量为 `CHATTER_STT_URL`（OpenAI 兼容的 `/v1/audio/transcriptions`）、`CHATTER_STT_KEY` 与 `CHATTER_STT_MODEL`。`deploy/stt-setup.sh` 安装的默认后端为本机 `sherpa-onnx` 与 SenseVoice，监听 `127.0.0.1:5090`。

客户端策略：设备具备本地识别器时优先在设备上识别；不具备时调用 `/stt`。后续版本将设备上的 SenseVoice 作为默认路径，云端识别改为设置项。

## 5. 文件与图像

本版本不改变媒体协议。`GET /media/{id}?dl=1` 仍返回 `Content-Disposition: attachment`，并带文件名。
