# 协议增量 2.0：和助手打电话

当前服务端 2.0.0，Android 2.0.0。两人之间的通话帧和以前一样。这一版多了一条只属于「人和助手」的通话。

## 谁和谁通话

`call.invite` 多一个可选字段 `bot: true`。为真时：

- 只发给助手的连接，不发给另一个人。
- 助手不在线回 `call.reject {reason:"offline"}`；已经有一路助手通话回 `busy`。
- `video: true` 直接回 `voice_only`，不建立通话。助手这一版只说话。
- 之后的 `call.accept` / `reject` / `hangup` / `sdp` / `ice` / `media` 只在打电话的那个人和助手之间转发。
- 挂断之后同一个 `callId` 再来的帧丢掉，不会漏到两个人的通话里。

助手仍然不能自己 `call.invite`（`unsupported`）。它可以回 accept、sdp、ice、media、hangup，也可以 `turn.get` 拿和手机一样的 TURN 账号。媒体走 WebRTC（P2P，不行就用现成的 coturn），不经过聊天服务器解码。

和助手的 SDP、ICE 是明文。助手没有两人那把端到端密钥。音频本身仍是 DTLS-SRTP。两人通话的 SDP / ICE 照旧加密。

## call.caption

```json
{"t":"call.caption","callId":"...","who":"user","text":"你好","state":"final","phase":"thinking"}
```

| 字段 | 含义 |
|---|---|
| who | `user` 或 `assistant` |
| state | `partial`（正在说）或 `final`（这句定了） |
| phase | 可省略。`listening` / `thinking` / `speaking` / `idle` / `error` |
| text | 最多 2000 字，可以是空的（只更新 phase） |

服务端检查这通电话还在，并且发送者是其中一方，然后只转给另一方，填上 `from`。过期的 call id 直接忽略。`who` 或 `state` 不合法回 `bad_request`。

手机上：别的 call id、挂断之后、比这句更早的字幕，都不显示。

## 助手进程里发生什么

NAS 上的插件（`hermes/lochatter/voice_call.py`）用 aiortc 当 WebRTC 的另一端。人声用能量 VAD 切段，整段 wav 交给 hub 的 `POST /v1/audio/transcriptions`，文本走 Hermes 原来的提问流程。回复按句切，再 `POST /v1/audio/speech`，PCM 送回通话。播放时检测到人声就停播并取消这一轮，不会把已经取消的那轮再跑一遍；转写失败可以同一段再试一次。

模型名不写死。NAS 上配置 `LOCHATTER_STT_MODEL`、`LOCHATTER_TTS_MODEL`（计划里的 whisper 与 mimo-v2.5-tts 要等 hub 实测后再填）。密钥用 `LOCHATTER_STT_KEY`，没有就读 `OPENAI_API_KEY`。没装 aiortc 时助手回 `unavailable`，手机提示改用按住说话。

接通后助手先说「我在，你说。」，不等模型想完。

## 登出

登出时内存里的头像、签名、聊天置顶、助手置顶、助手未读都清掉，共享资料缓存写成空。下次 hello 只恢复当前账号的共享资料。
