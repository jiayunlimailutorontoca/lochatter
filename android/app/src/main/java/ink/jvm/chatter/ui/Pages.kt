package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.jvm.chatter.R
import ink.jvm.chatter.data.Anniversary
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.Favorite
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.StickerRef
import kotlinx.coroutines.launch
import java.util.UUID

/** Bookmarked messages (this phone only). Tap → jump to it in the chat; long-press → remove. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(repo: ChatRepository, onBack: () -> Unit, onJump: (String) -> Unit) {
    val palette = LocalChatPalette.current
    val ctx = LocalContext.current
    val botName = repo.prefs.botName.ifEmpty { "助手" }
    val peerName = repo.prefs.peerName.ifEmpty { "对方" }
    var list by remember { mutableStateOf<List<Favorite>>(emptyList()) }
    var remove by remember { mutableStateOf<Favorite?>(null) }
    var menu by remember { mutableStateOf<Favorite?>(null) }
    var viewer by remember { mutableStateOf<Favorite?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { list = repo.favorites() }
    fun bucket(f: Favorite) = when (f.kind) {
        "image", "video", "album" -> 1
        "audio" -> 2
        "location" -> 3
        "file" -> 4
        "text", "card" -> 5
        else -> 0
    }
    val counts = remember(list) { IntArray(6).also { a -> list.forEach { a[0]++; val b = bucket(it); if (b in 1..5) a[b]++ } } }
    val shown = remember(list, tab) { if (tab == 0) list else list.filter { bucket(it) == tab } }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("收藏") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().background(palette.canvas).padding(pad)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "图片", "语音", "位置", "文件", "文字").forEachIndexed { i, label ->
                    val on = tab == i
                    Text(
                        "$label ${counts.getOrElse(i) { 0 }}",
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface).clickable { tab = i }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (shown.isEmpty()) {
                Text("长按消息 → 收藏，就会出现在这里。\n只保存在这台手机上。长按收藏可以转发。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { f ->
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if ((f.kind == "image" || f.kind == "album" || f.kind == "video") && f.media != null) viewer = f else onJump(f.id) }, onLongClick = { menu = f }),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val thumb = f.media?.takeIf { f.kind == "image" || f.kind == "album" || f.kind == "video" }?.let { it.thumbId ?: it.id }
                            if (thumb != null) {
                                AsyncImage(model = repo.api.mediaUrl(thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(12.dp))
                            } else if (f.kind == "sticker") {
                                StickerRef.parse(f.text)?.let { StickerImage(it, repo.prefs.serverUrl, modifier = Modifier.size(56.dp)) }
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(if (f.from == repo.me) "我" else if (f.from == LocalMessage.BOT_ID) botName else peerName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text(fmtTime(f.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val body = when (f.kind) {
                                    "text", "card" -> stripMarkdown(f.text ?: "")
                                    "image" -> "[图片]" + (f.text?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
                                    "album" -> "[相册]"; "video" -> "[视频]"; "audio" -> "[语音]"; "sticker" -> "[表情]"
                                    "location" -> "[位置]"; "file" -> "[文件] " + (f.media?.name ?: "")
                                    else -> f.text ?: ""
                                }
                                Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    viewer?.let { f ->
        val m = LocalMessage(f.id, null, f.from, f.kind, f.text, f.media, f.ts, LocalMessage.SENT)
        MediaViewer(repo, listOf(m), f.id, onClose = { viewer = null })
    }
    menu?.let { f ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text("这条收藏") },
            text = {
                Column {
                    Text("转发", modifier = Modifier.fillMaxWidth().clickable {
                        val m = LocalMessage(f.id, null, f.from, f.kind, f.text, f.media, f.ts, LocalMessage.SENT)
                        menu = null
                        scope.launch {
                            runCatching { repo.forward(m, toBot = false) }
                                .onFailure { Toast.makeText(ctx, "转发失败：${it.message}", Toast.LENGTH_SHORT).show() }
                                .onSuccess { Toast.makeText(ctx, "已转发", Toast.LENGTH_SHORT).show() }
                        }
                    }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                    Text("取消收藏", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().clickable { remove = f; menu = null }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("关闭") } },
        )
    }
    remove?.let { f ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("取消收藏？") },
            confirmButton = { TextButton(onClick = { repo.removeFavorite(f.id); list = repo.favorites(); remove = null; Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show() }) { Text("取消收藏", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("留着") } },
        )
    }
}

/** Shared anniversaries with a countdown; stored encrypted on the server, both of you can edit. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AnniversaryScreen(repo: ChatRepository, onBack: () -> Unit) {
    val palette = LocalChatPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by repo.anniversaries.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Anniversary?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun save(next: List<Anniversary>) = scope.launch {
        runCatching { repo.setAnniversaries(next.sortedBy { it.daysLeft() }) }.onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("纪念日") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = "添加") } },
    ) { pad ->
        Box(Modifier.fillMaxSize().background(palette.canvas).padding(pad)) {
            if (list.isEmpty()) {
                Text("加一个日子，两个人一起倒数。\n在一起的日子、生日、下次见面…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(32.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.sortedBy { it.daysLeft() }, key = { it.id }) { a ->
                    val days = a.daysLeft()
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable { editing = a }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_heart), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(a.date + if (a.yearly) " · 每年" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                when {
                                    days == 0L -> Text("就是今天", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    days < 0 -> Text("${-days} 天前", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else -> Text("$days 天", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                                if (a.yearly && a.years() > 0) Text("${a.years()} 周年", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
    if (adding || editing != null) {
        AnniversaryDialog(
            initial = editing,
            onClose = { adding = false; editing = null },
            onSave = { a -> save(list.filter { it.id != a.id } + a); adding = false; editing = null },
            onDelete = editing?.let { e -> { save(list.filter { it.id != e.id }); editing = null } },
        )
    }
}

@Composable
private fun AnniversaryDialog(initial: Anniversary?, onClose: () -> Unit, onSave: (Anniversary) -> Unit, onDelete: (() -> Unit)?) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var date by remember { mutableStateOf(initial?.date ?: java.time.LocalDate.now().toString()) }
    var yearly by remember { mutableStateOf(initial?.yearly ?: true) }
    val valid = title.isNotBlank() && runCatching { java.time.LocalDate.parse(date.trim()) }.isSuccess
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "添加纪念日" else "编辑纪念日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(30) }, label = { Text("名字") }, singleLine = true, placeholder = { Text("在一起") })
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("日期（yyyy-MM-dd）") }, singleLine = true, isError = date.isNotBlank() && !valid && title.isNotBlank())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每年重复", modifier = Modifier.weight(1f))
                    Switch(checked = yearly, onCheckedChange = { yearly = it })
                }
                Text("两个人都能看到和修改；内容端到端加密后存在服务器上。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(Anniversary(initial?.id ?: UUID.randomUUID().toString(), title.trim(), date.trim(), yearly)) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)); Text(" 删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
}

/** Editable list of the assistant's quick commands (shared, plaintext). */
@Composable
fun QuickCommandsDialog(repo: ChatRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by repo.quickCommands.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf(current) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("助手快捷指令") },
        text = {
            Column {
                Text("显示在助手页输入框上方，点一下直接发；以「：」结尾的会填进输入框等你补充。两个人共用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                list.forEachIndexed { i, q ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(q, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { list = list.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it.take(80) }, placeholder = { Text("新指令…") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (draft.isNotBlank() && list.size < 12) { list = list + draft.trim(); draft = "" } }) { Icon(Icons.Default.Add, contentDescription = "添加") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { repo.setQuickCommands(list) }.onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
                    busy = false
                    onClose()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** This month's calls: count, minutes, data. */
@Composable
fun CallStatsDialog(repo: ChatRepository, onClose: () -> Unit) {
    val stats = remember { repo.callStatsThisMonth() }
    val secs = stats.sumOf { it.seconds }
    val bytes = stats.sumOf { it.bytes }
    val video = stats.count { it.video }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("本月通话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${stats.size} 次（视频 $video 次，语音 ${stats.size - video} 次）", style = MaterialTheme.typography.bodyLarge)
                Text("时长 ${secs / 3600} 小时 ${(secs % 3600) / 60} 分钟", style = MaterialTheme.typography.bodyLarge)
                Text("流量约 ${fmtSize(bytes)}（上下行合计，只统计这台手机接通的通话）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (stats.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    stats.takeLast(5).asReversed().forEach { s ->
                        Text("${fmtTime(s.ts)} · ${if (s.video) "视频" else "语音"} ${s.seconds / 60}:${"%02d".format(s.seconds % 60)} · ${fmtSize(s.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

/** Quiet hours: start / end in HH:mm. */
@Composable
fun QuietHoursDialog(repo: ChatRepository, onClose: () -> Unit, onChanged: () -> Unit) {
    var enabled by remember { mutableStateOf(repo.prefs.quietEnabled) }
    var start by remember { mutableStateOf("%02d:%02d".format(repo.prefs.quietStart / 60, repo.prefs.quietStart % 60)) }
    var end by remember { mutableStateOf("%02d:%02d".format(repo.prefs.quietEnd / 60, repo.prefs.quietEnd % 60)) }
    fun parse(s: String): Int? {
        val p = s.trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
    val ok = parse(start) != null && parse(end) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("免打扰时段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开启", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("从") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("到") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("时段内来电只震动不响铃，新消息静默显示；跨过午夜也可以。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                repo.prefs.quietEnabled = enabled
                repo.prefs.quietStart = parse(start)!!
                repo.prefs.quietEnd = parse(end)!!
                onChanged(); onClose()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** Wallpaper, accent colour and bubble shape. */
@Composable
fun AppearanceDialog(repo: ChatRepository, onClose: () -> Unit, onPickWallpaper: () -> Unit) {
    val palette = LocalChatPalette.current
    var accent by remember { mutableStateOf(repo.prefs.accent) }
    var bg by remember { mutableStateOf(repo.prefs.chatBg) }
    var bubble by remember { mutableStateOf(repo.prefs.bubbleStyle) }
    fun apply() {
        repo.prefs.accent = accent; repo.prefs.chatBg = bg; repo.prefs.bubbleStyle = bubble
        MainActivity.themePrefs.value = Triple(accent, bg, bubble)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("外观") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("主题色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCENT_CHOICES.forEach { (key, label, color) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { accent = key; apply() }) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(color).then(if (accent == key) Modifier.padding(0.dp) else Modifier), contentAlignment = Alignment.Center) {
                                if (accent == key) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White)
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("聊天背景", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CHAT_BACKGROUNDS.forEach { (key, label) ->
                        val on = bg == key
                        Text(
                            label, style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { bg = key; apply() }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPickWallpaper) { Text("从相册选一张") }
                    if (bg.startsWith("file:")) TextButton(onClick = { bg = "rose"; apply() }) { Text("去掉图片", color = MaterialTheme.colorScheme.error) }
                }
                Text("气泡", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("round" to "圆润", "square" to "方正").forEach { (key, label) ->
                        val on = bubble == key
                        Text(
                            label, style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { bubble = key; apply() }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp)).background(palette.canvas), contentAlignment = Alignment.Center) {
                    Text("预览", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
}
