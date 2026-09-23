# Android 12（API 31）兼容检查（1.6.0）

用户的手机是 Android 12 的国产 ROM，没有 Google 服务。逐项核对 `android/` 里所有 `Build.VERSION.SDK_INT` 分支和需要分支的 API：

| 项目 | 结论 |
|---|---|
| 系统语音识别 `SpeechRecognizer` | 国产 ROM 常常没有任何识别服务。听写不再用它：默认在手机上跑 sherpa-onnx SenseVoice。云端地址在设置 → 语音识别里另开。 |
| 语音条转文字 | 不再走 API 33 的 `EXTRA_AUDIO_SOURCE`。语音在手机上解成 16 kHz，用本机 SenseVoice 识别；设置里打开云端后才上传。 |
| 开车模式朗读 `TextToSpeech` | 没有中文引擎时 `speak()` 静默失败。1.6 打开开车模式时 `checkChineseTts()` 检查并提示装引擎；免提听写靠 `speaking` 状态等朗读结束。 |
| 前台服务从后台启动 | 31+ 会抛 `ForegroundServiceStartNotAllowedException`；`ChatService.start()` / `setScreenShare()` 已 try/catch。 |
| `PendingIntent` 可变性 | 31+ 必须显式 `FLAG_IMMUTABLE` / `FLAG_MUTABLE`；`Notifications.kt` 五处都已��。 |
| 精确闹钟 `SCHEDULE_EXACT_ALARM` | 31+ 需要授权；`ScheduledSendReceiver` / `WatchdogReceiver` 在 `canScheduleExactAlarms()` 为 false 时退到 `setAndAllowWhileIdle`。 |
| `registerReceiver` 导出标记 | 只影响 33+（`UpdateChecker` 已带 `RECEIVER_EXPORTED`）；电量广播是系统广播，31 上无需标记。 |
| `POST_NOTIFICATIONS` | 33+ 才存在，`ChatScreen` 里已按版本判断，31 上不申请。 |
| `getParcelableExtra(name, Class)` | 33+ 的重载，`MainActivity` 三处都有 `< 33` 的旧路径。 |
| `Geocoder.getFromLocation(listener)` | 33+，`Locator.geocode` 在 31 上走同步旧接口（IO 线程）。 |
| 屏幕共享前台服务类型 | 34+ 的 `mediaProjection` 类型；`ChatService` 在 29~33 上用无类型的旧路径。 |
| 蓝牙 `BLUETOOTH_CONNECT` | 31+ 运行时权限，通话页已按版本申请；30 及以下走 manifest 的 `BLUETOOTH`。 |
| 画中画 `setAutoEnterEnabled` | 31+ 才有，已判断。 |
| `MediaRecorder(Context)` | 31+ 构造函数，`VoiceRecorder` 已判断。 |
| `MediaStore` 保存 | 29+ 用 `MediaStore.Downloads` / `RELATIVE_PATH`，无需存储权限；28 及以下才申请 `WRITE_EXTERNAL_STORAGE`。 |
| 地图 App 可见性 | 30+ 需要 manifest `<queries>`，1.6 已加（高德 / 百度 / 腾讯 / Google + `geo:`）。 |

没有发现会在 Android 12 上崩溃的调用。1.5 在这台手机上的两个「不能用」都是语音识别的可用性问题：1.6 先改成服务器转文字，现在默认在手机上用 SenseVoice。
