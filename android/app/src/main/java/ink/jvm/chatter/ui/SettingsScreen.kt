package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.BuildConfig
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.PushPref
import ink.jvm.chatter.data.DeviceInfo
import ink.jvm.chatter.data.ReleaseInfo
import ink.jvm.chatter.util.ChatExport
import ink.jvm.chatter.util.Diag
import ink.jvm.chatter.util.MediaSaver
import ink.jvm.chatter.util.UpdateChecker
import kotlinx.coroutines.launch

/** Full-screen settings: encryption, account, chat, appearance, storage, diagnostics, about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: ChatRepository, onBack: () -> Unit, onFontScale: (Float) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalChatPalette.current
    val e2e by repo.e2eState.collectAsStateWithLifecycle()
    val ttl by repo.ttlSeconds.collectAsStateWithLifecycle()
    val botName by repo.botName.collectAsStateWithLifecycle()
    val botOnline by repo.botOnline.collectAsStateWithLifecycle()
    val botTtl by repo.botTtl.collectAsStateWithLifecycle()
    var botMode by remember { mutableStateOf(repo.prefs.botInMain) }
    var botNotify by remember { mutableStateOf(repo.prefs.botNotify) }
    var botDrive by remember { mutableStateOf(repo.prefs.botDriveMode) }
    var botVoice by remember { mutableStateOf(repo.prefs.botVoiceToText) }
    var cloudStt by remember { mutableStateOf(repo.prefs.cloudStt) }
    var cloudUrl by remember { mutableStateOf(repo.prefs.cloudSttUrl) }
    var callSummary by remember { mutableStateOf(repo.prefs.callSummary) }
    var summaryReq by remember { mutableStateOf(0) }
    var pushProvider by remember { mutableStateOf("off") }
    var pushSecret by remember { mutableStateOf("") }
    var pushEvery by remember { mutableStateOf("60") }
    var pushStyle by remember { mutableStateOf("text") }
    LaunchedEffect(Unit) {
        runCatching { repo.api.pushPref() }.onSuccess {
            pushProvider = it.provider
            pushSecret = it.secret
            pushEvery = it.intervalSec.toString()
            pushStyle = it.style
        }
    }
    var exporting by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lock by remember { mutableStateOf(repo.prefs.appLock) }
    var secure by remember { mutableStateOf(repo.prefs.secureScreen) }
    var verified by remember { mutableStateOf(repo.prefs.e2eVerified) }
    var scale by remember { mutableStateOf(repo.prefs.fontScale) }
    var linkPreview by remember { mutableStateOf(repo.prefs.linkPreview) }
    var wifiOnly by remember { mutableStateOf(repo.prefs.wifiOnlyMedia) }
    var notifyQuote by remember { mutableStateOf(repo.prefs.notifyQuote) }
    var quietVersion by remember { mutableStateOf(0) }
    val ringtoneChanged by MainActivity.ringtoneChanged
    var dialog by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<ReleaseInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) { cacheBytes = MediaSaver.cacheBytes(ctx) }
    val pickedWallpaper by repo.pickedWallpaper.collectAsStateWithLifecycle()
    LaunchedEffect(pickedWallpaper) {
        val uri = pickedWallpaper ?: return@LaunchedEffect
        repo.pickedWallpaper.value = null
        runCatching {
            val dir = java.io.File(ctx.filesDir, "wallpaper").apply { mkdirs() }
            val f = java.io.File(dir, "bg.jpg")
            ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            repo.prefs.chatBg = "file:" + f.absolutePath
            MainActivity.themePrefs.value = Triple(repo.prefs.accent, repo.prefs.chatBg, repo.prefs.bubbleStyle)
        }.onFailure { Toast.makeText(ctx, "设置背景失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    found?.let { r -> UpdateDialog(r, onDownload = { UpdateChecker.download(ctx, r, null); found = null }, onSkip = { found = null }, onLater = { found = null }) }
    when (dialog) {
        "safety" -> SafetyNumberDialog(repo, verified, onVerified = { verified = it; repo.setVerified(it) }, onClose = { dialog = null })
        "export" -> KeyExportDialog(repo, onClose = { dialog = null })
        "import" -> KeyImportDialog(repo, onClose = { dialog = null })
        "password" -> PasswordDialog(repo, onClose = { dialog = null })
        "devices" -> DevicesDialog(repo, onClose = { dialog = null })
        "web_login" -> WebLoginDialog(repo, onClose = { dialog = null })
        "ttl" -> TtlDialog(ttl, onPick = { repo.setTtl(it); dialog = null }, onClose = { dialog = null })
        "bot" -> BotNameDialog(repo, botName, onClose = { dialog = null })
        "botmode" -> BotModeDialog(botMode, onPick = { botMode = it; repo.prefs.botInMain = it; dialog = null }, onClose = { dialog = null })
        "quick" -> QuickCommandsDialog(repo, onClose = { dialog = null })
        "calls" -> CallStatsDialog(repo, onClose = { dialog = null })
        "quiet" -> QuietHoursDialog(repo, onClose = { dialog = null }, onChanged = { quietVersion++ })
        "appearance" -> AppearanceDialog(repo, onClose = { dialog = null }, onPickWallpaper = { (ctx as? MainActivity)?.pickWallpaper() })
        "migrate_show" -> MigrateShowDialog(payload = repo.migrationPayload(), onClose = { dialog = null })
        "migrate_scan" -> MigrateScanDialog(
            onResult = { payload ->
                dialog = null
                scope.launch {
                    val ok = runCatching { repo.importMigration(payload) }.getOrDefault(false)
                    Toast.makeText(ctx, if (ok) "已导入旧手机的密钥，正在重新同步" else "二维码内容不对", Toast.LENGTH_LONG).show()
                }
            },
            onClose = { dialog = null },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().background(palette.canvas).padding(pad).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("端到端加密") {
                val (title, hint) = when (e2e) {
                    ChatRepository.E2eState.OFF -> "未启用" to "等对方升级到新版并上线后自动开启"
                    ChatRepository.E2eState.ON -> (if (verified) "已启用 · 安全码已核对" else "已启用 · 安全码未核对") to "消息、图片、文件、通话信令都只在两台手机上解密"
                    ChatRepository.E2eState.PEER_KEY_CHANGED -> "对方的密钥变了" to "对方换了手机或重装了应用，请重新核对安全码"
                }
                Item(title, hint, painterResource(R.drawable.ic_shield), tint = if (e2e == ChatRepository.E2eState.PEER_KEY_CHANGED) MaterialTheme.colorScheme.error else palette.online) {
                    if (e2e != ChatRepository.E2eState.OFF) dialog = "safety"
                }
                Item("查看安全码", "两人当面对一下这 60 位数字，一致就没人能窃听") { if (repo.safetyNumber() != null) dialog = "safety" else Toast.makeText(ctx, "对方还没有密钥", Toast.LENGTH_SHORT).show() }
                Item("迁移到新手机", "新手机扫这里的二维码，密钥和账号一起过去") { dialog = "migrate_show" }
                Item("从旧手机导入", "扫旧手机上的二维码并输入它显示的 6 位数字") { dialog = "migrate_scan" }
                val ring = repo.keyRingInfo()
                Item("密钥轮换", if (ring.epoch == 0) "还没开始（等对方也升级到 1.5）" else "第 ${ring.epoch} 期 · 每周自动换一把会话密钥；对方第 ${ring.peerEpoch} 期" + (if (ring.lastRotationAt > 0) " · 上次 ${fmtTime(ring.lastRotationAt)}" else "")) {
                    scope.launch { runCatching { repo.rotateNow() }.onSuccess { Toast.makeText(ctx, "已换到新一期密钥", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(ctx, "轮换失败：${it.message}", Toast.LENGTH_SHORT).show() } }
                }
                Item("导出密钥", "换手机时把这串密钥带过去，旧消息才能解密（二维码迁移更方便）") { dialog = "export" }
                Item("导入密钥", "粘贴另一台手机导出的密钥，并重新同步历史") { dialog = "import" }
            }
            Section("账号") {
                Item("修改密码", "需要输入当前密码") { dialog = "password" }
                Item("登录设备", "查看并踢出其他登录的手机") { dialog = "devices" }
                Item("登录网页版", "扫电脑网页上的二维码。密钥只留在那一页，刷新后要重新扫") { dialog = "web_login" }
            }
            Section("聊天") {
                Item("消息定时销毁", "当前：${ChatExport.ttlLabel(ttl)}，对双方都生效") { dialog = "ttl" }
                Item("外观", "主题色、聊天背景、气泡样式") { dialog = "appearance" }
                SwitchItem("链接预览", "文字里有网址时显示标题和缩略图（手机会去访问那个网站）", linkPreview) { linkPreview = it; repo.prefs.linkPreview = it }
                SwitchItem("仅 Wi-Fi 下载原图和文件", "移动数据下打开大图、视频、文件前先问一下", wifiOnly) { wifiOnly = it; repo.prefs.wifiOnlyMedia = it }
                Item("导出聊天记录", "文本文件，图片和文件只保留名字") { scope.launch { runCatching { ChatExport.share(ctx, repo) }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() } } }
                Item("导出聊天记录（含图片和文件）", exporting?.let { (d, t) -> "正在打包 $d / $t…" } ?: "zip 包：文字、网页版、解密后的图片和文件") {
                    if (exporting != null) return@Item
                    exporting = 0 to 0
                    scope.launch {
                        runCatching { ChatExport.shareZip(ctx, repo) { d, t -> exporting = d to t } }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
                        exporting = null
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("字体大小", style = MaterialTheme.typography.bodyLarge)
                        Text("聊天与界面文字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    listOf(0.9f to "小", 1f to "标准", 1.15f to "大", 1.3f to "特大").forEach { (v, label) ->
                        val on = kotlin.math.abs(scale - v) < 0.01f
                        Text(
                            label, style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { scale = v; repo.prefs.fontScale = v; onFontScale(v) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Section("通知与通话") {
                val ringName = remember(ringtoneChanged) {
                    repo.prefs.ringtoneUri?.let { u -> runCatching { android.media.RingtoneManager.getRingtone(ctx, android.net.Uri.parse(u))?.getTitle(ctx) }.getOrNull() } ?: "系统默认"
                }
                Item("来电铃声", "当前：$ringName") { (ctx as? MainActivity)?.pickRingtone() }
                val quietHint = remember(quietVersion) {
                    if (repo.prefs.quietEnabled) "%02d:%02d – %02d:%02d，来电只震动，消息静默".format(repo.prefs.quietStart / 60, repo.prefs.quietStart % 60, repo.prefs.quietEnd / 60, repo.prefs.quietEnd % 60) else "关闭"
                }
                Item("免打扰时段", quietHint) { dialog = "quiet" }
                SwitchItem("引用我的消息单独提示", "对方回复了你的某条消息时用不同的提示音和震动", notifyQuote) { notifyQuote = it; repo.prefs.notifyQuote = it }
                Item("本月通话统计", "次数、时长、流量") { dialog = "calls" }
            }
            Section("离线通知") {
                Text(
                    "对方没有连着的时候推到你的手机。带上原文时，服务器用手机交上来的会话密钥解开文字，不会把密文发出去。图片仍只发一句提示。这条设置跟这个账号走。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("off" to "关闭", "serverchan" to "Server酱", "meow" to "MeoW").forEach { (id, label) ->
                        val on = pushProvider == id
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { pushProvider = id }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                if (pushProvider != "off") {
                    OutlinedTextField(
                        value = pushSecret,
                        onValueChange = { pushSecret = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true,
                        label = { Text(if (pushProvider == "meow") "MeoW 昵称" else "Server酱 SendKey") },
                        placeholder = { Text(if (pushProvider == "meow") "在 MeoW 里看到的昵称" else "sctp…t…") },
                        visualTransformation = if (pushProvider == "serverchan") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                Text(
                    when (pushStyle) {
                        "hint" -> "lochatter 小明向您发送了消息"
                        "count" -> "lochatter 小明向您发送了 3 条消息"
                        else -> "lochatter 小明向您发送了晚饭好了"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("hint" to "只说有消息", "text" to "带上原文", "count" to "只报条数").forEach { (id, label) ->
                        val on = pushStyle == id
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { pushStyle = id }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = pushEvery,
                    onValueChange = { pushEvery = it.filter { ch -> ch.isDigit() }.take(5) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("最少间隔（秒）") },
                    supportingText = { Text("0 表示每条都推。默认 60。最多一天。") },
                )
                Button(
                    onClick = {
                        val sec = pushEvery.toIntOrNull()
                        if (sec == null || sec !in 0..86400) {
                            Toast.makeText(ctx, "间隔要在 0 到 86400 秒之间", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            runCatching { repo.api.setPushPref(PushPref(pushProvider, pushSecret.trim(), sec, pushStyle)) }
                                .onSuccess {
                                    pushEvery = it.intervalSec.toString()
                                    pushStyle = it.style.ifBlank { pushStyle }
                                    Toast.makeText(ctx, "离线通知已保存", Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { Toast.makeText(ctx, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
                        }
                    },
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp),
                ) { Text("保存") }
            }
            Section("语音识别") {
                val modelStatus by ink.jvm.chatter.media.LocalStt.status.collectAsStateWithLifecycle()
                val modelReady = ink.jvm.chatter.media.LocalStt.ready(ctx)
                Item(
                    if (modelReady) "本机模型已就绪" else "下载本机模型",
                    modelStatus ?: if (modelReady) "SenseVoice，转文字和听写都在这台手机上完成" else "约 230 MB，点这里下载。第一次转文字时，Wi-Fi 下也会自动下载",
                ) {
                    if (!modelReady && modelStatus == null) {
                        scope.launch {
                            runCatching { ink.jvm.chatter.media.LocalStt.ensure(ctx, allowMobile = true) }
                                .onSuccess { Toast.makeText(ctx, "语音模型已就绪", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(ctx, it.message ?: "下载失败", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                SwitchItem("使用云端识别", "默认关。打开后，转文字和听写改把语音发到下面的地址。", cloudStt) {
                    cloudStt = it
                    repo.prefs.cloudStt = it
                }
                if (cloudStt) {
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it; repo.prefs.cloudSttUrl = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text("云端地址") },
                        placeholder = { Text("https://example.com/v1/audio/transcriptions") },
                    )
                    Text(
                        "填完整的识别接口。自己的聊天服务器就填 https://域名/stt。地址空着时仍用本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            Section("通话纪要") {
                val summaryStatus by ink.jvm.chatter.media.LocalSummary.status.collectAsStateWithLifecycle()
                val summaryReady = ink.jvm.chatter.media.LocalSummary.ready(ctx)
                SwitchItem(
                    "挂断后整理纪要",
                    summaryStatus ?: if (summaryReady) {
                        "默认关。用本机的 Gemma 3 1B 整理这台手机听到的话，不上传。模型已在手机上。"
                    } else {
                        "默认关。打开后下载 Gemma 3 1B（约 550 MB），只在这台手机上整理，不上传。"
                    },
                    callSummary,
                ) { on ->
                    if (!on) {
                        summaryReq += 1
                        callSummary = false
                        repo.prefs.callSummary = false
                    } else if (ink.jvm.chatter.media.LocalSummary.ready(ctx)) {
                        callSummary = true
                        repo.prefs.callSummary = true
                    } else {
                        val req = summaryReq + 1
                        summaryReq = req
                        scope.launch {
                            runCatching { ink.jvm.chatter.media.LocalSummary.ensure(ctx) }
                                .onSuccess {
                                    if (summaryReq != req) return@launch
                                    callSummary = true
                                    repo.prefs.callSummary = true
                                    Toast.makeText(ctx, "纪要模型已就绪", Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    if (summaryReq != req) return@launch
                                    callSummary = false
                                    repo.prefs.callSummary = false
                                    Toast.makeText(ctx, it.message ?: "下载失败", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                }
            }
            Section("助手") {
                if (botName.isEmpty()) {
                    Item("未启用", "服务器还没升级到带助手的版本", painterResource(R.drawable.ic_bot), tint = MaterialTheme.colorScheme.onSurfaceVariant) {}
                } else {
                    Item(
                        "名字：$botName",
                        if (botOnline) "在线 · 聊天页右上角的小机器人进它的页面" else "离线 · NAS 上的 Hermes 没有连到服务器",
                        painterResource(R.drawable.ic_bot), tint = if (botOnline) palette.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { dialog = "bot" }
                    Item(
                        "在主聊天里",
                        if (botMode == "hidden") "不显示助手的任何消息，只靠右上角的小红点提醒" else "每次 @ 它折叠成一行，点一下去它的页面看回复",
                    ) { dialog = "botmode" }
                    Item("快捷指令", "助手页输入框上方的一排按钮，两人共用") { dialog = "quick" }
                    SwitchItem("回复通知", "它答完了通知发问的那个人（对方问的不通知你）", botNotify) { botNotify = it; repo.prefs.botNotify = it }
                    SwitchItem("助手页跟随定时销毁", "开着时，问它的和它答的也按「消息定时销毁」到期删除（对双方生效）", botTtl) { v ->
                        scope.launch { runCatching { repo.setBotTtl(v) }.onFailure { Toast.makeText(ctx, "设置失败：${it.message}", Toast.LENGTH_SHORT).show() } }
                    }
                    SwitchItem("开车模式", "它的新回复自动朗读（系统语音）", botDrive) { botDrive = it; repo.prefs.botDriveMode = it }
                    SwitchItem("语音先转文字再问", "助手页发语音时先识别成文字，识别不了就发语音", botVoice) { botVoice = it; repo.prefs.botVoiceToText = it }
                    Text(
                        "助手那一页你们俩共享，不做端到端加密；没 @ 它、也不在它页面里说的话它看不到。销毁只清聊天记录，它自己的会话上下文要用「新对话」清。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            Section("安全") {
                SwitchItem("应用锁", "打开应用时用指纹 / 锁屏密码验证", lock) {
                    lock = it; repo.prefs.appLock = it
                    if (!it) MainActivity.locked.value = false
                }
                SwitchItem("防截屏", "禁止截图录屏，最近任务里不显示内容", secure) {
                    secure = it; repo.prefs.secureScreen = it
                    (ctx as? MainActivity)?.applySecureFlag()
                }
            }
            Section("存储与诊断") {
                Item("清理缓存", if (cacheBytes >= 0) "图片、语音、分享、链接预览缓存：${fmtSize(cacheBytes)}" else "计算中…") {
                    MediaSaver.clearCache(ctx)
                    ink.jvm.chatter.util.LinkPreviews.clearCache(ctx)
                    cacheBytes = MediaSaver.cacheBytes(ctx)
                    Toast.makeText(ctx, "已清理", Toast.LENGTH_SHORT).show()
                }
                Item("导出诊断信息", "崩溃记录、连接日志，发给开发者定位问题") { runCatching { Diag.share(ctx) }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() } }
            }
            Section("关于") {
                Item("版本 ${BuildConfig.VERSION_NAME}", updateNote ?: (if (checking) "检查中…" else "点击检查更新")) {
                    if (checking) return@Item
                    checking = true
                    scope.launch {
                        val r = UpdateChecker.check(repo, force = true)
                        checking = false
                        if (r != null) found = r else updateNote = "已是最新版本"
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun Item(title: String, hint: String, icon: androidx.compose.ui.graphics.painter.Painter? = null, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchItem(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
fun SafetyNumberDialog(repo: ChatRepository, verified: Boolean, onVerified: (Boolean) -> Unit, onClose: () -> Unit) {
    val number = repo.safetyNumber() ?: "对方还没有密钥"
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("安全码") },
        text = {
            Column {
                Text("两台手机上显示的数字应完全一致。当面或打电话核对一遍，一致就说明服务器没有掉包密钥。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), contentAlignment = Alignment.Center) {
                    Text(number, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.5f)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已核对，一致", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = verified, onCheckedChange = onVerified)
                }
            }
        },
        confirmButton = { TextButton(onClick = { repo.acknowledgePeerKey(); onClose() }) { Text("好") } },
    )
}

@Composable
private fun KeyExportDialog(repo: ChatRepository, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val key = repo.exportKey() ?: "尚未生成密钥"
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("导出密钥") },
        text = {
            Column {
                Text("这是你的私钥，谁拿到它就能读你的消息。只通过安全的方式转到自己的新手机，不要发给别人。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(key)); Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show(); onClose() }) { Text("复制") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

@Composable
private fun KeyImportDialog(repo: ChatRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("导入密钥") },
        text = {
            Column {
                Text("粘贴另一台手机「导出密钥」得到的内容。导入后会重新拉取全部历史并解密。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), placeholder = { Text("MIGHAgEAMBMGByqGSM49…") })
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val ok = repo.importKey(text)
                    busy = false
                    Toast.makeText(ctx, if (ok) "已导入，正在重新同步" else "密钥格式不对", Toast.LENGTH_SHORT).show()
                    if (ok) onClose()
                }
            }) { Text(if (busy) "导入中…" else "导入") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** Server holds a different key for me (another phone published first). */
@Composable
fun KeyConflictDialog(repo: ChatRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    if (importing) {
        KeyImportDialog(repo, onClose = { importing = false })
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("这个账号已在另一台手机上启用加密") },
        text = { Text("要在这台手机上读消息，请从那台手机「设置 → 导出密钥」把密钥粘贴到这里。也可以改用本机的新密钥，但那台手机将无法解密新消息。") },
        confirmButton = { TextButton(onClick = { importing = true }) { Text("导入那台手机的密钥") } },
        dismissButton = {
            TextButton(onClick = { scope.launch { repo.overwriteKey(); Toast.makeText(ctx, "已改用本机密钥", Toast.LENGTH_SHORT).show() } }) { Text("改用本机密钥", color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun PasswordDialog(repo: ChatRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var others by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = old, onValueChange = { old = it }, label = { Text("当前密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = new1, onValueChange = { new1 = it }, label = { Text("新密码（至少 6 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = new2, onValueChange = { new2 = it }, label = { Text("再输一次") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = others, onCheckedChange = { others = it })
                    Spacer(Modifier.width(8.dp))
                    Text("同时退出其他设备", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && old.isNotEmpty() && new1.length >= 6, onClick = {
                if (new1 != new2) { error = "两次输入不一致"; return@TextButton }
                busy = true
                scope.launch {
                    try {
                        val n = repo.api.changePassword(old, new1, others)
                        Toast.makeText(ctx, if (n > 0) "密码已修改，其他 $n 台设备已退出" else "密码已修改", Toast.LENGTH_SHORT).show()
                        onClose()
                    } catch (e: Exception) {
                        error = e.message
                    } finally { busy = false }
                }
            }) { Text(if (busy) "提交中…" else "修改") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun DevicesDialog(repo: ChatRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() { scope.launch { runCatching { list = repo.api.devices() }.onFailure { error = it.message } } }
    LaunchedEffect(Unit) { load() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("登录设备") },
        text = {
            Column {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val l = list
                if (l == null && error == null) Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                l?.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (d.device == "web") "网页版" else d.device ?: "未知设备") + if (d.current) "（本机）" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = if (d.current) FontWeight.SemiBold else FontWeight.Normal)
                            Text("登录 ${fmtTime(d.createdAt)}" + (d.lastSeen?.let { " · 最近 ${fmtTime(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!d.current) TextButton(onClick = {
                            scope.launch {
                                runCatching { repo.api.revokeDevice(d.id) }.onFailure { Toast.makeText(ctx, "失败：${it.message}", Toast.LENGTH_SHORT).show() }
                                load()
                            }
                        }) { Text("踢出", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

@Composable
private fun BotNameDialog(repo: ChatRepository, current: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("给助手起名字") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("名字（1-24 个字）") }, singleLine = true)
                Text("两个人看到的是同一个名字，聊天里点「@名字」再发消息就是在问它。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.trim().isNotEmpty() && name.trim() != current, onClick = {
                busy = true
                scope.launch {
                    try {
                        repo.renameBot(name.trim())
                        Toast.makeText(ctx, "助手现在叫「${name.trim()}」", Toast.LENGTH_SHORT).show()
                        onClose()
                    } catch (e: Exception) {
                        error = e.message ?: "改名失败"
                        busy = false
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun BotModeDialog(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val options = listOf("collapsed" to ("折叠成一行" to "主聊天里每个 @ 只占一行，显示是否已回复"), "hidden" to ("不显示" to "主聊天里完全没有助手的痕迹，只有右上角的小红点"))
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("主聊天里的助手消息") },
        text = {
            Column {
                options.forEach { (key, labels) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = key == current, onClick = { onPick(key) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(labels.first, style = MaterialTheme.typography.bodyLarge)
                            Text(labels.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
}

@Composable
private fun TtlDialog(current: Long, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val options = listOf(0L to "关闭", 3600L to "1 小时", 86400L to "1 天", 7 * 86400L to "7 天", 30 * 86400L to "30 天")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("消息定时销毁") },
        text = {
            Column {
                Text("开启后，从现在起发出的消息（双方）在设定时间后自动从服务器和两台手机上删除。之前的消息不受影响。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                options.forEach { (secs, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(secs) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = secs == current, onClick = { onPick(secs) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
