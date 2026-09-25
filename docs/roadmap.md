# 版本记录

记录截至 2.3.4 已经发布的行为。未实现的原计划单独列出，不记入对应版本的交付范围。

| 版本 | 交付内容 |
|---|---|
| 1.3.0 | 消除助手回复重复入库。增加快捷指令、表情库、拍一拍、阅后即焚与共享配置。见 [protocol-1.3.md](protocol-1.3.md)、[plan-1.3.0.md](plan-1.3.0.md) |
| 1.5.0 | 连接限流、未被引用媒体的清理、位置消息、屏幕共享信令、按周轮换的会话密钥（`e2e2:` / `LCE2`）。见 [protocol-1.5.md](protocol-1.5.md) |
| 1.6.0 | 服务端将 `e2e2:` 识别为密文，从而修复位置消息被拒绝的缺陷。增加地图预览、地名检索、云端转写，并完成 Android 12 兼容核对。见 [protocol-1.6.md](protocol-1.6.md)、[android-12.md](android-12.md) |
| 1.7.0 | 长按菜单、多选、撤回、按住说话时的滑动取消与转写、首页三个分页、约 5 分钟的时间分隔。高德瓦片由 `CHATTER_TILES=amap` 打开。未做主题预设，也未做助手页底栏。见 [protocol-1.7.md](protocol-1.7.md)、[plan-1.7.md](plan-1.7.md) |
| 1.8.0 | 拍摄、多图发送、通话小窗与锁屏接听、按类型检索、会话置顶、收藏分类、资料页、相册消息 `album`。见 [protocol-1.8.md](protocol-1.8.md)、[plan-1.8.md](plan-1.8.md) |
| 1.9 | 客户端数据层拆分并补充测试。Web 客户端支持文本与图像，密钥通过手机迁移二维码导入。见 [protocol-1.9.md](protocol-1.9.md) |
| 2.0.0 | 用户可与助手建立语音通话，带字幕，播放期间可被用户语音打断。登出时清除内存中的头像、签名与置顶状态。见 [protocol-2.0.md](protocol-2.0.md)、[plan-2.0.md](plan-2.0.md) |
| 2.1、2.1.2 | 离线通知发往 Server酱³ 或 MeoW。头像与签名改为每名用户独立持有。未实现助手加入双人通话。见 [protocol-2.1.md](protocol-2.1.md) |
| 2.2.0 | 无协议变更。服务进程与 Android 默认服务器地址均由环境变量配置。见 [protocol-2.2.md](protocol-2.2.md) |
| 2.2.1 | 无协议变更，服务端仍为 2.2.0。Android `versionCode` 37。表情面板在搜索框取得焦点后保持打开，并位于输入法上方 |
| 2.2.2 | 无新帧、无新 `kind`，schema 仍为 9。网页改为手机扫码登录，令牌与密钥环只留在该次页面内存中。Android `versionCode` 38，服务端 `2.2.2`。见 [protocol-2.2.md](protocol-2.2.md) |
| 2.3.0 | 无新帧、无新 `kind`，schema 仍为 9。Android `versionCode` 40，服务端 `2.3.0`。人类通话在中转接通后继续尝试直连。通话字幕与纪要只在本机处理，纪要默认关闭。网页可播放语音和视频并下载文件。助手通话未改。见 [protocol-2.3.md](protocol-2.3.md) |
| 2.3.1 | 无协议变更，服务端仍为 2.3.0。Android `versionCode` 41。纪要模型在设置中单独下载。打开「挂断后整理纪要」不开始下载。见 [protocol-2.3.md](protocol-2.3.md) |
| 2.3.2 | 无协议变更，服务端仍为 2.3.0。Android `versionCode` 42。纪要模型改从魔搭社区下载。见 [protocol-2.3.md](protocol-2.3.md) |
| 2.3.3 | 无协议变更，服务端仍为 2.3.0。Android `versionCode` 43。通话字幕仍由本机麦克风生成。录音拷贝不再使用会在 Android 16 上退出进程的 `ByteBuffer.array()`。见 [protocol-2.3.md](protocol-2.3.md) |
| 2.3.4 | 无协议变更，服务端仍为 2.3.0。Android `versionCode` 44。通话字幕保留。每次读取录音前将缓冲区位置回到起点，拷贝不改动 WebRTC 正在使用的缓冲区。关闭本机硬件回声消除与降噪。见 [protocol-2.3.md](protocol-2.3.md) |

## 未实现

- 双人通话进行中邀请助手，并在结束后生成摘要。该项曾列入 2.1 计划，未交付。
- 端到端实时语音模型。该项曾列入 2.2 计划。2.2.0 未实现该项，只调整配置来源。
- Web 客户端上的通话与助手操作。
- 主题预设，以及助手页的底栏菜单。

## 发布检查

发布前执行 `bash tools/check.sh --e2e`，完成本机构建，并在两台设备上安装验证。其中一台应为 Android 12。

版本号修改三处：`android/app/build.gradle.kts` 的 `versionCode` 与 `versionName`，`server/Chatter.Server/Chatter.Server.csproj` 的 `Version`，以及本文件与 README。

服务端保留上一份 `/opt/chatter/rel.old`。上一版安装包在新版本确认可用前不删除。

1.5.0 只完成了服务端测试与编译，未在设备上操作，因此位置消息发送失败到上线后才发现。自该版本起，位置、通话、加密消息与登录后的密钥交换均须在真机上验证。
