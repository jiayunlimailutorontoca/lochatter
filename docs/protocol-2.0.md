# 协议增量 2.0.0

服务端与 Android 客户端的版本均为 2.0.0。双人通话的帧保持不变。本版本增加用户与助手之间的通话。

## 1. call.invite

`call.invite` 增加可选字段 `bot`（布尔）。值为 true 时：

- 帧只发给助手连接，不发给另一名人类用户。
- 助手不在线时返回 `call.reject {reason:"offline"}`。已存在一路助手通话时返回 `busy`。
- `video` 为 true 时直接返回 `voice_only`，不建立通话。本版本的助手通话只有音频。
- 此后的 `call.accept`、`reject`、`hangup`、`sdp`、`ice`、`media` 只在发起通话的用户与助手之间转发。
- 挂断之后，同一 `callId` 上后续到达的帧丢弃，不进入双人通话。

助手不能发送 `call.invite`，否则返回 `unsupported`。助手可以发送 `accept`、`sdp`、`ice`、`media`、`hangup`，也可以 `turn.get`，凭据与人类客户端相同。媒体为 WebRTC，优先直连，失败时使用已配置的 coturn。聊天服务不解码媒体。

与助手交换的 SDP 与 ICE 为明文。助手不持有双人会话密钥。音频仍由 DTLS-SRTP 保护。双人通话的 SDP 与 ICE 仍为密文。

## 2. call.caption

```json
{"t":"call.caption","callId":"...","who":"user","text":"文本","state":"final","phase":"thinking"}
```

| 字段 | 约束 |
|---|---|
| who | `user` 或 `assistant` |
| state | `partial` 或 `final` |
| phase | 可省略。取值为 `listening`、`thinking`、`speaking`、`idle`、`error` |
| text | 最长 2000 个字符。可以为空，此时只更新 `phase` |

服务端确认该通话仍存在，且发送者是通话的一方，然后只转发给另一方，并填写 `from`。过期的 `callId` 忽略。`who` 或 `state` 非法时返回 `bad_request`。

客户端不显示其他 `callId` 的字幕，不显示挂断之后到达的字幕，也不显示比当前句更早的字幕。

## 3. 助手侧处理

插件 `hermes/lochatter/voice_call.py` 以 aiortc 作为 WebRTC 对端。人声由能量 VAD 分段，整段 wav 提交到模型服务的 `POST /v1/audio/transcriptions`。文本进入 Hermes 既有的处理流程。回复按句切分，再请求 `POST /v1/audio/speech`，PCM 送回通话。播放期间若检测到人声，则停止播放并取消本轮，已取消的轮次不得再次执行。转写失败时，同一段可以重试一次。

模型标识不在源码中固定。运行环境设置 `LOCHATTER_STT_MODEL` 与 `LOCHATTER_TTS_MODEL`，值必须是模型服务实际列出的标识。密钥使用 `LOCHATTER_STT_KEY`；未设置时读取 `OPENAI_API_KEY`。未安装 aiortc 时，助手返回 `unavailable`，客户端改为使用按住说话。

接通后，助手先发送固定开场句，不等待模型完成本轮推理。

## 4. 登出

登出时清除内存中的头像、签名、聊天置顶、助手置顶与助手未读，并将共享资料缓存置空。下一次 `hello` 只恢复当前账号的共享资料。
