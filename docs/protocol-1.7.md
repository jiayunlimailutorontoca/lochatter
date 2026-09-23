# 协议增量 1.7（相对 1.6）

服务器 1.7.0，schema 不变（7）。

## 1. 撤回 `recall`

`msg.send` 新 kind `recall`，`text` = 自己某条消息的客户端 id，发出后 2 分钟内有效；人和助手都能撤回自己的。
服务器把目标行改成 `kind = "recall"`、清空 `text` / `mediaId`（媒体文件随即删除），然后把这条 `recall` 控制消息像 `del` 一样广播；
晚同步的客户端在 `msg.batch` 里看到的目标行就是 `recall`。客户端把它渲染成居中灰字「xx 撤回了一条消息」，自己的文本撤回可「重新编辑」。
撤回别人的、超过 2 分钟的、`call` / `pat` 的：`bad_request`。助手适配器把 `recall` 当 `del` 处理（清本地媒体缓存）。

## 2. 瓦片坐标系

`hello.features` 增加 `tileDatum`：`"wgs84"`（`/tiles/`，OpenStreetMap）或 `"gcj02"`（`/amap-tiles/`，高德栅格；服务器 `CHATTER_TILES=amap`）。
线上位置坐标始终 WGS-84；`gcj02` 时客户端画图时转换（`Gcj02.kt`），拖动 / 缩放后再转回。

## 3. 无变化

转发在客户端完成（重新发送 / 重新上传）；多选、语音已听、草稿都是本地状态。
