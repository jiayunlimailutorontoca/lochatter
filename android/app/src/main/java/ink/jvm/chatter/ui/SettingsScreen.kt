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
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
fun SettingsScreen(repo: ChatRepository, onBack: () -> Unit, onFontScale: (Float) -> Unit, onOpenNotes: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalChatPalette.current
    val ttl by repo.ttlSeconds.collectAsStateWithLifecycle()
    val botName by repo.botName.collectAsStateWithLifecycle()
    var botMode by remember { mutableStateOf(repo.prefs.botInMain) }
    var verified by remember { mutableStateOf(repo.prefs.e2eVerified) }
    var quietVersion by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<ReleaseInfo?>(null) }
    var route by remember { mutableStateOf(SettingsRoute.Home) }
    var routeForward by remember { mutableStateOf(true) }
    val routeStack = remember { androidx.compose.runtime.mutableStateListOf<SettingsRoute>() }
    fun pushRoute(next: SettingsRoute) {
        if (next == route) return
        routeForward = true
        routeStack.add(route)
        route = next
    }
    fun popRoute() {
        if (routeStack.isEmpty()) return
        routeForward = false
        route = routeStack.removeAt(routeStack.lastIndex)
    }
    androidx.activity.compose.BackHandler(enabled = routeStack.isNotEmpty()) { popRoute() }
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
                title = { Text(route.label) },
                navigationIcon = {
                    IconButton(onClick = { if (routeStack.isNotEmpty()) popRoute() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        AnimatedContent(
            targetState = route,
            modifier = Modifier.fillMaxSize().background(palette.canvas).padding(pad),
            transitionSpec = { Motion.pages(routeForward) },
            label = "settings",
        ) { dest ->
            SettingsPane(
                dest = dest,
                repo = repo,
                onDialog = { dialog = it },
                onFontScale = onFontScale,
                onOpenNotes = onOpenNotes,
                onOpen = { pushRoute(it) },
                onUpdate = { found = it },
                quietTick = quietVersion,
            )
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
internal fun Item(title: String, hint: String, icon: androidx.compose.ui.graphics.painter.Painter? = null, tint: Color = MaterialTheme.colorScheme.primary, more: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (more) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SwitchItem(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
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
        text = { Text("要在这台手机上读消息，请从那台手机「设置 → 加密与安全 → 密钥 → 导出密钥」把密钥粘贴到这里。也可以改用本机的新密钥，但那台手机将无法解密新消息。") },
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
private fun ModelDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val current by ink.jvm.chatter.media.LocalSummary.choice.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("整理模型") },
        text = {
            Column {
                Text(
                    "Qwen3 0.6B、1.7B、4B 在这台手机的 GPU 上运行，不上传。第一次整理时才载入，界面会写明正在载入，载好后留在内存里。选云端接口时，要整理的文字会发到你填的地址。换型号不会自动下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ink.jvm.chatter.media.LocalSummary.options().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            ink.jvm.chatter.media.LocalSummary.select(ctx, option.id)
                            onClose()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = option.id == current,
                            onClick = {
                                ink.jvm.chatter.media.LocalSummary.select(ctx, option.id)
                                onClose()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(option.title, style = MaterialTheme.typography.bodyLarge)
                            Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

@Composable
private fun BotModeDialog(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val options = listOf("collapsed" to ("折叠成一行" to "主聊天里每个 @ 只占一行，显示是否已回复"), "hidden" to ("不显示" to "主聊天里完全没有助手的痕迹，未读记在会话列表里"))
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
