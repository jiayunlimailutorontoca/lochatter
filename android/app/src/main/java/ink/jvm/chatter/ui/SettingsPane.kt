package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.BuildConfig
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.PushPref
import ink.jvm.chatter.media.DailySummary
import ink.jvm.chatter.media.LocalStt
import ink.jvm.chatter.media.LocalSummary
import ink.jvm.chatter.util.ChatExport
import ink.jvm.chatter.util.Diag
import ink.jvm.chatter.util.MediaSaver
import ink.jvm.chatter.util.UpdateChecker
import kotlinx.coroutines.launch

internal enum class SettingsRoute(val label: String) {
    Home("设置"),
    Chat("聊天"),
    Look("外观与字体"),
    Links("链接与流量"),
    Export("导出"),
    Call("通话"),
    Security("加密与安全"),
    Keys("密钥"),
    Device("本机能力"),
    Speech("语音转文字"),
    Model("整理模型"),
    Daily("每日摘要"),
    Assistant("助手"),
    AssistName("名字与显示"),
    AssistNotify("通知与朗读"),
    Account("账号"),
    Push("离线通知"),
    About("关于"),
}

@Composable
internal fun SettingsPane(
    dest: SettingsRoute,
    repo: ChatRepository,
    onDialog: (String) -> Unit,
    onFontScale: (Float) -> Unit,
    onOpenNotes: () -> Unit,
    onOpen: (SettingsRoute) -> Unit,
    onUpdate: (ink.jvm.chatter.data.ReleaseInfo) -> Unit,
    quietTick: Int,
) {
    when (dest) {
        SettingsRoute.Home -> HomePane(repo, onOpen)
        SettingsRoute.Chat -> ChatPane(repo, onDialog, onOpen)
        SettingsRoute.Look -> LookPane(repo, onFontScale)
        SettingsRoute.Links -> LinksPane(repo)
        SettingsRoute.Export -> ExportPane(repo)
        SettingsRoute.Call -> CallPane(repo, onDialog, onOpenNotes, quietTick)
        SettingsRoute.Security -> SecurityPane(repo, onDialog, onOpen)
        SettingsRoute.Keys -> KeysPane(repo, onDialog)
        SettingsRoute.Device -> DevicePane(repo, onOpen)
        SettingsRoute.Speech -> SpeechPane(repo)
        SettingsRoute.Model -> ModelPane()
        SettingsRoute.Daily -> DailyPane(repo)
        SettingsRoute.Assistant -> AssistantPane(repo, onDialog, onOpen)
        SettingsRoute.AssistName -> AssistNamePane(repo, onDialog)
        SettingsRoute.AssistNotify -> AssistNotifyPane(repo)
        SettingsRoute.Account -> AccountPane(repo, onDialog, onOpen)
        SettingsRoute.Push -> PushPane(repo)
        SettingsRoute.About -> AboutPane(repo, onUpdate)
    }
}

@Composable
private fun Pane(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = {
            content()
            Spacer(Modifier.height(24.dp))
        },
    )
}

@Composable
private fun Lead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun HomePane(repo: ChatRepository, onOpen: (SettingsRoute) -> Unit) {
    val ttl by repo.ttlSeconds.collectAsStateWithLifecycle()
    val e2e by repo.e2eState.collectAsStateWithLifecycle()
    val botName by repo.botName.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LocalSummary.bind(ctx)
    val modelId by LocalSummary.choice.collectAsStateWithLifecycle()
    val model = LocalSummary.option(modelId)
    var daily by remember { mutableStateOf(repo.prefs.dailySummary) }
    val sec = when (e2e) {
        ChatRepository.E2eState.OFF -> "还没启用"
        ChatRepository.E2eState.ON -> if (repo.prefs.e2eVerified) "已启用，安全码对过" else "已启用，安全码还没对"
        ChatRepository.E2eState.PEER_KEY_CHANGED -> "对方的密钥变了"
    }
    val quiet = if (repo.prefs.quietEnabled) "免打扰开着" else "免打扰关着"
    Pane {
        RiseIn(0) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Column {
                    Item("聊天", "定时销毁 ${ChatExport.ttlLabel(ttl)}", more = true) { onOpen(SettingsRoute.Chat) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    Item("通话", quiet, more = true) { onOpen(SettingsRoute.Call) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    Item("加密与安全", sec, more = true) { onOpen(SettingsRoute.Security) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    Item("本机能力", model.title + if (daily) " · 每日摘要开" else " · 每日摘要关", more = true) { onOpen(SettingsRoute.Device) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    Item("助手", if (botName.isEmpty()) "还没启用" else botName, more = true) { onOpen(SettingsRoute.Assistant) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    Item("账号", "密码、设备和网页版", more = true) { onOpen(SettingsRoute.Account) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    Item("关于", "版本 ${BuildConfig.VERSION_NAME}", more = true) { onOpen(SettingsRoute.About) }
                }
            }
        }
    }
}

@Composable
private fun ChatPane(repo: ChatRepository, onDialog: (String) -> Unit, onOpen: (SettingsRoute) -> Unit) {
    val ttl by repo.ttlSeconds.collectAsStateWithLifecycle()
    val preview = if (repo.prefs.linkPreview) "链接预览开" else "链接预览关"
    Pane {
        Lead("定时销毁、外观、链接和导出。")
        RiseIn(0) {
            Section("聊天") {
                Item("定时销毁", ChatExport.ttlLabel(ttl)) { onDialog("ttl") }
                Item("外观与字体", "主题色、背景、气泡、字号", more = true) { onOpen(SettingsRoute.Look) }
                Item("链接与流量", preview, more = true) { onOpen(SettingsRoute.Links) }
                Item("导出", "文字，或连同图片和文件", more = true) { onOpen(SettingsRoute.Export) }
            }
        }
    }
}

@Composable
private fun LookPane(repo: ChatRepository, onFontScale: (Float) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(repo.prefs.fontScale) }
    Pane {
        Lead("主题色、背景和气泡马上生效。字体同时改聊天和界面。")
        RiseIn(0) {
            Section("字体") {
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
        }
        RiseIn(1) {
            Section("主题色、背景、气泡") {
                Column(Modifier.padding(16.dp)) {
                    AppearanceControls(repo) { (ctx as? MainActivity)?.pickWallpaper() }
                }
            }
        }
    }
}

@Composable
private fun LinksPane(repo: ChatRepository) {
    var linkPreview by remember { mutableStateOf(repo.prefs.linkPreview) }
    var wifiOnly by remember { mutableStateOf(repo.prefs.wifiOnlyMedia) }
    Pane {
        Lead("链接预览会让手机访问那个网站。移动数据下可以先问再下载大文件。")
        RiseIn(0) {
            Section("链接与流量") {
                SwitchItem("链接预览", "文字里有网址时显示标题和缩略图", linkPreview) { linkPreview = it; repo.prefs.linkPreview = it }
                SwitchItem("仅 Wi-Fi 下载原图和文件", "移动数据下打开大图、视频、文件前先问一下", wifiOnly) { wifiOnly = it; repo.prefs.wifiOnlyMedia = it }
            }
        }
    }
}

@Composable
private fun ExportPane(repo: ChatRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Pane {
        Lead("导出的是这台手机上已经解密的记录。")
        RiseIn(0) {
            Section("导出") {
                Item("导出聊天记录", "文本文件，图片和文件只保留名字") {
                    scope.launch { runCatching { ChatExport.share(ctx, repo) }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() } }
                }
                Item("导出聊天记录（含图片和文件）", exporting?.let { (d, t) -> "正在打包 $d / $t…" } ?: "zip 包：文字和已经解密的图片、文件") {
                    if (exporting != null) return@Item
                    exporting = 0 to 0
                    scope.launch {
                        runCatching { ChatExport.shareZip(ctx, repo) { d, t -> exporting = d to t } }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
                        exporting = null
                    }
                }
            }
        }
    }
}

@Composable
private fun CallPane(repo: ChatRepository, onDialog: (String) -> Unit, onOpenNotes: () -> Unit, quietTick: Int) {
    val ctx = LocalContext.current
    val ringtoneChanged by MainActivity.ringtoneChanged
    var notifyQuote by remember { mutableStateOf(repo.prefs.notifyQuote) }
    val ringName = remember(ringtoneChanged) {
        repo.prefs.ringtoneUri?.let { u -> runCatching { android.media.RingtoneManager.getRingtone(ctx, android.net.Uri.parse(u))?.getTitle(ctx) }.getOrNull() } ?: "系统默认"
    }
    val quietHint = remember(quietTick) {
        if (repo.prefs.quietEnabled) "%02d:%02d – %02d:%02d，来电只震动，消息静默".format(repo.prefs.quietStart / 60, repo.prefs.quietStart % 60, repo.prefs.quietEnd / 60, repo.prefs.quietEnd % 60) else "关闭"
    }
    Pane {
        Lead("铃声、免打扰，以及挂断后的文字记录。")
        RiseIn(0) {
            Section("通话") {
                Item("来电铃声", ringName) { (ctx as? MainActivity)?.pickRingtone() }
                Item("免打扰时段", quietHint) { onDialog("quiet") }
                SwitchItem("引用我的消息单独提示", "对方回复了你的某条消息时换一种提示", notifyQuote) { notifyQuote = it; repo.prefs.notifyQuote = it }
                Item("本月通话统计", "次数、时长、流量") { onDialog("calls") }
                Item("通话文字记录", "挂断后按时间列出。点「总结摘要」才整理") { onOpenNotes() }
            }
        }
    }
}

@Composable
private fun SecurityPane(repo: ChatRepository, onDialog: (String) -> Unit, onOpen: (SettingsRoute) -> Unit) {
    val ctx = LocalContext.current
    val e2e by repo.e2eState.collectAsStateWithLifecycle()
    var lock by remember { mutableStateOf(repo.prefs.appLock) }
    var secure by remember { mutableStateOf(repo.prefs.secureScreen) }
    val (title, hint) = when (e2e) {
        ChatRepository.E2eState.OFF -> "未启用" to "等对方升级到新版并上线后自动开启"
        ChatRepository.E2eState.ON -> (if (repo.prefs.e2eVerified) "已启用 · 安全码已核对" else "已启用 · 安全码未核对") to "消息只在两台手机上解密"
        ChatRepository.E2eState.PEER_KEY_CHANGED -> "对方的密钥变了" to "请重新核对安全码"
    }
    Pane {
        Lead("消息、图片、文件和通话信令只在两台手机上解密。")
        RiseIn(0) {
            Section("加密与安全") {
                Item(title, hint) { if (e2e != ChatRepository.E2eState.OFF) onDialog("safety") }
                Item("查看安全码", "两人当面对一下这 60 位数字") {
                    if (repo.safetyNumber() != null) onDialog("safety") else Toast.makeText(ctx, "对方还没有密钥", Toast.LENGTH_SHORT).show()
                }
                Item("密钥", "轮换、迁移、导出和导入", more = true) { onOpen(SettingsRoute.Keys) }
                SwitchItem("应用锁", "打开应用时用指纹或锁屏密码", lock) {
                    lock = it; repo.prefs.appLock = it
                    if (!it) MainActivity.locked.value = false
                }
                SwitchItem("防截屏", "禁止截图录屏，最近任务里不显示内容", secure) {
                    secure = it; repo.prefs.secureScreen = it
                    (ctx as? MainActivity)?.applySecureFlag()
                }
            }
        }
    }
}

@Composable
private fun KeysPane(repo: ChatRepository, onDialog: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ring = repo.keyRingInfo()
    val hint = if (ring.epoch == 0) "还没开始" else "第 ${ring.epoch} 期 · 对方第 ${ring.peerEpoch} 期" +
        (if (ring.lastRotationAt > 0) " · 上次 ${fmtTime(ring.lastRotationAt)}" else "")
    Pane {
        Lead("换手机时用二维码把密钥带过去。导出的一串密钥也能用。")
        RiseIn(0) {
            Section("密钥") {
                Item("密钥轮换", hint) {
                    scope.launch {
                        runCatching { repo.rotateNow() }
                            .onSuccess { Toast.makeText(ctx, "已换到新一期密钥", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, "轮换失败：${it.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
                Item("迁移到新手机", "新手机扫这里的二维码") { onDialog("migrate_show") }
                Item("从旧手机导入", "扫旧手机上的二维码并输入 6 位数字") { onDialog("migrate_scan") }
                Item("导出密钥", "把这串密钥带到另一台手机") { onDialog("export") }
                Item("导入密钥", "粘贴另一台手机导出的密钥") { onDialog("import") }
            }
        }
    }
}

@Composable
private fun DevicePane(repo: ChatRepository, onOpen: (SettingsRoute) -> Unit) {
    val ctx = LocalContext.current
    LocalSummary.bind(ctx)
    val modelId by LocalSummary.choice.collectAsStateWithLifecycle()
    val model = LocalSummary.option(modelId)
    val sttReady = LocalStt.ready(ctx)
    var daily by remember { mutableStateOf(repo.prefs.dailySummary) }
    Pane {
        Lead("语音转文字和整理可以各自选本机或云端。打开某一项不会开始下载。")
        RiseIn(0) {
            Section("本机能力") {
                Item("语音转文字", if (repo.prefs.cloudStt) "云端识别" else if (sttReady) "本机模型已就绪" else "本机模型还没下载", more = true) { onOpen(SettingsRoute.Speech) }
                Item("整理模型", model.title, more = true) { onOpen(SettingsRoute.Model) }
                Item("每日摘要", if (daily) "开着，跟整理用同一个渠道" else "关着", more = true) { onOpen(SettingsRoute.Daily) }
            }
        }
    }
}

@Composable
private fun SpeechPane(repo: ChatRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelStatus by LocalStt.status.collectAsStateWithLifecycle()
    val modelReady = LocalStt.ready(ctx)
    var cloudStt by remember { mutableStateOf(repo.prefs.cloudStt) }
    var cloudUrl by remember { mutableStateOf(repo.prefs.cloudSttUrl) }
    Pane {
        Lead("默认在这台手机上转写。打开云端识别后，语音发到你填的地址。")
        RiseIn(0) {
            Section("语音转文字") {
                Item(
                    if (modelReady) "本机模型已就绪" else "下载本机模型",
                    modelStatus ?: if (modelReady) "SenseVoice，转文字和听写都在这台手机上" else "约 230 MB。点这里才下载",
                ) {
                    if (!modelReady && modelStatus == null) {
                        scope.launch {
                            runCatching { LocalStt.ensure(ctx, allowMobile = true) }
                                .onSuccess { Toast.makeText(ctx, "语音模型已就绪", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(ctx, it.message ?: "下载失败", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                SwitchItem("使用云端识别", "打开后，转文字和听写改走下面的地址", cloudStt) {
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
                        "地址空着时仍用本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPane() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LocalSummary.bind(ctx)
    val modelId by LocalSummary.choice.collectAsStateWithLifecycle()
    val model = LocalSummary.option(modelId)
    val summaryStatus by LocalSummary.status.collectAsStateWithLifecycle()
    val accelNote by LocalSummary.accelNote.collectAsStateWithLifecycle()
    val held by LocalSummary.resident.collectAsStateWithLifecycle()
    val summaryReady = LocalSummary.ready(ctx)
    var cloudLlmUrl by remember { mutableStateOf(repoCloud(ctx, "url")) }
    var cloudLlmKey by remember { mutableStateOf(repoCloud(ctx, "key")) }
    var cloudLlmModel by remember { mutableStateOf(repoCloud(ctx, "model")) }
    val heldNote = when {
        model.cloud -> ""
        held -> " 已在内存里，下次不用再载。"
        else -> " 还没载入。第一次整理时才会载入。"
    }
    Pane {
        Lead("总结、润色、建议回复和每日摘要都用这里选中的一项。换型号不会自动下载。")
        RiseIn(0) {
            Section("整理模型") {
                LocalSummary.options().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { LocalSummary.select(ctx, option.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.id == modelId, onClick = { LocalSummary.select(ctx, option.id) })
                        Column(Modifier.weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.bodyLarge)
                            Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    model.title + "。" + model.detail + heldNote + (accelNote ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (model.cloud) {
                    OutlinedTextField(
                        value = cloudLlmUrl,
                        onValueChange = { cloudLlmUrl = it; ink.jvm.chatter.data.Prefs(ctx).cloudLlmUrl = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text("接口地址") },
                        placeholder = { Text("https://example.com/v1") },
                    )
                    OutlinedTextField(
                        value = cloudLlmKey,
                        onValueChange = { cloudLlmKey = it; ink.jvm.chatter.data.Prefs(ctx).cloudLlmKey = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text("密钥") },
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("可以留空") },
                    )
                    OutlinedTextField(
                        value = cloudLlmModel,
                        onValueChange = { cloudLlmModel = it; ink.jvm.chatter.data.Prefs(ctx).cloudLlmModel = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text("模型名") },
                        placeholder = { Text("服务商给出的模型名") },
                    )
                    Text(
                        if (summaryReady) "文字会发到这个地址。通话录音不会发送。" else "地址须以 http 开头，并填写模型名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                } else {
                    Item(
                        if (summaryReady) "这个模型已就绪" else "下载这个模型",
                        summaryStatus ?: if (summaryReady) "第一次整理会载入并提示，载好后留在内存里。" else model.downloadHint,
                    ) {
                        if (!summaryReady && summaryStatus == null) {
                            scope.launch {
                                runCatching { LocalSummary.ensure(ctx) }
                                    .onSuccess { Toast.makeText(ctx, "纪要模型已就绪", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, it.message ?: "下载失败", Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun repoCloud(ctx: android.content.Context, which: String): String {
    val p = ink.jvm.chatter.data.Prefs(ctx)
    return when (which) {
        "url" -> p.cloudLlmUrl
        "key" -> p.cloudLlmKey
        else -> p.cloudLlmModel
    }
}

@Composable
private fun DailyPane(repo: ChatRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by DailySummary.notes.collectAsStateWithLifecycle()
    val busy by DailySummary.runningNow.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(repo.prefs.dailySummary) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    LocalSummary.bind(ctx)
    val modelId by LocalSummary.choice.collectAsStateWithLifecycle()
    val model = LocalSummary.option(modelId)
    val ready = LocalSummary.ready(ctx)
    LaunchedEffect(Unit) { DailySummary.refresh(ctx) }
    pendingDelete?.let { date ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这一天") },
            text = { Text("这一天的记录会从这台手机去掉。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { DailySummary.delete(ctx, date) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    Pane {
        Lead("跟整理用同一个渠道：${model.title}。没就绪时不会跑，也不会为此下载。")
        RiseIn(0) {
            Section("每日摘要") {
                SwitchItem("打开每日摘要", if (on) "每个自然日最多整理一次" else "关着。已有的记录还在", on) { checked ->
                    on = checked
                    DailySummary.setEnabled(ctx, checked)
                    if (checked) scope.launch { DailySummary.maybeRun(ctx, repo, MainActivity.callBusy.value) }
                }
                if (on && !ready) {
                    Text(
                        LocalSummary.unavailable(ctx) ?: "还没就绪",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (busy) {
                    Text(
                        "正在整理今天…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        notes.forEachIndexed { index, note ->
            RiseIn(index + 1) {
                Section(note.date) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(note.talk, style = MaterialTheme.typography.bodyLarge)
                        Text("心情：${note.mood}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (note.memories.isEmpty()) "可记住的事：" else "可记住的事：" + note.memories.joinToString("；"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (note.channel.isNotBlank()) {
                            Text("用的是${note.channel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { pendingDelete = note.date }) { Text("删除这一天") }
                    }
                }
            }
        }
        if (notes.isNotEmpty()) {
            TextButton(onClick = { scope.launch { DailySummary.clear(ctx) } }, modifier = Modifier.padding(start = 8.dp)) { Text("清空全部记录") }
        }
    }
}

@Composable
private fun AssistantPane(repo: ChatRepository, onDialog: (String) -> Unit, onOpen: (SettingsRoute) -> Unit) {
    val botName by repo.botName.collectAsStateWithLifecycle()
    val botOnline by repo.botOnline.collectAsStateWithLifecycle()
    Pane {
        Lead("助手页你们俩共享。没 @ 它、也不在它页面里说的话，它看不到。")
        RiseIn(0) {
            Section("助手") {
                if (botName.isEmpty()) {
                    Item("未启用", "服务器还没带上助手") {}
                } else {
                    Item("名字与显示", if (botOnline) "$botName · 在线" else "$botName · 离线", more = true) { onOpen(SettingsRoute.AssistName) }
                    Item("通知与朗读", "回复通知、定时销毁、开车模式", more = true) { onOpen(SettingsRoute.AssistNotify) }
                    Item("快捷指令", "助手页输入框上方的一排按钮") { onDialog("quick") }
                }
            }
        }
    }
}

@Composable
private fun AssistNamePane(repo: ChatRepository, onDialog: (String) -> Unit) {
    val botName by repo.botName.collectAsStateWithLifecycle()
    val mode = if (repo.prefs.botInMain == "hidden") "主聊天里不显示" else "主聊天里折叠成一行"
    Pane {
        Lead("名字两边共用。主聊天里怎么显示，只影响这一台手机。")
        RiseIn(0) {
            Section("名字与显示") {
                Item("名字", botName.ifBlank { "未启用" }) { if (botName.isNotEmpty()) onDialog("bot") }
                Item("在主聊天里", mode) { onDialog("botmode") }
            }
        }
    }
}

@Composable
private fun AssistNotifyPane(repo: ChatRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val botTtl by repo.botTtl.collectAsStateWithLifecycle()
    var botNotify by remember { mutableStateOf(repo.prefs.botNotify) }
    var botDrive by remember { mutableStateOf(repo.prefs.botDriveMode) }
    var botVoice by remember { mutableStateOf(repo.prefs.botVoiceToText) }
    Pane {
        Lead("通知只发给提问的那个人。开车模式用系统语音读出新回复。")
        RiseIn(0) {
            Section("通知与朗读") {
                SwitchItem("回复通知", "它答完了通知发问的那个人", botNotify) { botNotify = it; repo.prefs.botNotify = it }
                SwitchItem("助手页跟随定时销毁", "问它的和它答的也按时删除", botTtl) { v ->
                    scope.launch { runCatching { repo.setBotTtl(v) }.onFailure { Toast.makeText(ctx, "设置失败：${it.message}", Toast.LENGTH_SHORT).show() } }
                }
                SwitchItem("开车模式", "新回复自动朗读", botDrive) { botDrive = it; repo.prefs.botDriveMode = it }
                SwitchItem("语音先转文字再问", "识别不了就仍发语音", botVoice) { botVoice = it; repo.prefs.botVoiceToText = it }
            }
        }
    }
}

@Composable
private fun AccountPane(repo: ChatRepository, onDialog: (String) -> Unit, onOpen: (SettingsRoute) -> Unit) {
    Pane {
        Lead("密码、已登录的手机，以及网页版扫码。")
        RiseIn(0) {
            Section("账号") {
                Item("修改密码", "需要输入当前密码") { onDialog("password") }
                Item("登录设备", "查看并踢出其他手机") { onDialog("devices") }
                Item("登录网页版", "扫电脑网页上的二维码") { onDialog("web_login") }
                Item("离线通知", "对方没连上时推到这台手机", more = true) { onOpen(SettingsRoute.Push) }
            }
        }
    }
}

@Composable
private fun PushPane(repo: ChatRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    Pane {
        Lead("对方没连上时推到这台手机。带原文时，服务器用会话密钥解开文字。图片只发一句提示。")
        RiseIn(0) {
            Section("离线通知") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("off" to "关闭", "serverchan" to "Server酱", "meow" to "MeoW").forEach { (id, label) ->
                        val selected = pushProvider == id
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
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
                        "hint" -> "只说有消息"
                        "count" -> "只报条数"
                        else -> "带上原文"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("hint" to "只说有消息", "text" to "带上原文", "count" to "只报条数").forEach { (id, label) ->
                        val selected = pushStyle == id
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
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
                    supportingText = { Text("0 表示每条都推。默认 60。") },
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
        }
    }
}

@Composable
private fun AboutPane(repo: ChatRepository, onUpdate: (ink.jvm.chatter.data.ReleaseInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) { cacheBytes = MediaSaver.cacheBytes(ctx) }
    Pane {
        RiseIn(0) {
            Section("关于") {
                Item("版本 ${BuildConfig.VERSION_NAME}", updateNote ?: (if (checking) "检查中…" else "点击检查更新")) {
                    if (checking) return@Item
                    checking = true
                    scope.launch {
                        val r = UpdateChecker.check(repo, force = true)
                        checking = false
                        if (r != null) onUpdate(r) else updateNote = "已是最新版本"
                    }
                }
                Item("清理缓存", if (cacheBytes >= 0) fmtSize(cacheBytes) else "计算中…") {
                    MediaSaver.clearCache(ctx)
                    ink.jvm.chatter.util.LinkPreviews.clearCache(ctx)
                    cacheBytes = MediaSaver.cacheBytes(ctx)
                    Toast.makeText(ctx, "已清理", Toast.LENGTH_SHORT).show()
                }
                Item("导出诊断信息", "崩溃记录和连接日志") {
                    runCatching { Diag.share(ctx) }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}
