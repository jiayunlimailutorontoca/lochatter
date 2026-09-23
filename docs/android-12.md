# Android 12 兼容核对

核对对象为 Android 12（API 31）的无 Google 服务运行环境。范围是 `android/` 中按 `SDK_INT` 分叉的调用。结论供后续修改这些 API 时复查。

| 项目 | 结论 |
|---|---|
| 系统语音识别 | 目标运行环境通常没有识别服务。语音消息默认在设备上使用 SenseVoice。设置切换为云端后才上传 |
| 驾车模式朗读 | 没有中文引擎时 `speak()` 失败且不抛出到界面。开启驾车模式时检查引擎并提示安装 |
| 从后台启动前台服务 | API 31 起可能抛出 `ForegroundServiceStartNotAllowedException`。`ChatService.start()` 与屏幕共享路径捕获该异常 |
| `PendingIntent` | API 31 起必须指定 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`。通知相关调用已指定 |
| 精确闹钟 | 无 `SCHEDULE_EXACT_ALARM` 权限时退回到 `setAndAllowWhileIdle`。系统推迟闹钟属于预期行为 |
| `registerReceiver` | 导出标记约束 API 33 及以上。API 31 上的电量广播不需要该标记 |
| 通知权限 | `POST_NOTIFICATIONS` 自 API 33 起存在。API 31 不申请该权限 |
| `getParcelableExtra` | API 33 的新重载在 API 31 上走旧重载 |
| 逆地理编码 | API 33 的异步 `Geocoder` 在 API 31 上使用同步接口，调用位于 IO 线程 |
| 屏幕共享的前台服务类型 | `mediaProjection` 自 API 34 起要求。API 29–33 使用不带该类型的路径 |
| 蓝牙 | API 31 起在运行时申请 `BLUETOOTH_CONNECT`。API 30 及以下使用清单中的 `BLUETOOTH` |
| 画中画、`MediaRecorder(Context)` | 均为 API 31 才提供，调用前有版本判断 |
| 保存到系统相册 | API 29 起使用 `MediaStore`，不申请存储权限 |
| 打开地图应用 | API 30 起须在清单中声明 `<queries>`。已声明高德、百度、腾讯、Google 与 `geo:` |

核对中未发现会在 Android 12 上直接崩溃的调用。语音识别不可用是运行环境没有识别服务，不是进程崩溃。
