package ink.jvm.chatter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.WsClient
import ink.jvm.chatter.media.Speech
import ink.jvm.chatter.service.Notifications
import ink.jvm.chatter.util.MediaSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Hermes gateway slash commands surfaced in the "/" menu. `arg` = the current input is appended. */
private data class BotCommand(val label: String, val command: String, val hint: String, val arg: Boolean = false)

private val BOT_COMMANDS = listOf(
    BotCommand("新对话", "/new", "清空它的上下文，本地记录保留"),
    BotCommand("停止", "/stop", "打断当前回答"),
    BotCommand("排队说", "/queue", "等它做完再处理这句", arg = true),
    BotCommand("插一句", "/steer", "不打断，下一次工具调用后插入", arg = true),
    BotCommand("顺便问", "/btw", "不影响主线的旁支提问", arg = true),
    BotCommand("状态", "/status", "模型、上下文、token"),
    BotCommand("它记住的事", "/memory", "看看它的记忆"),
    BotCommand("压缩上下文", "/compress", "上下文快满时用"),
    BotCommand("撤回上一问", "/undo", "回退一轮重问"),
    BotCommand("重试", "/retry", "上一条重发"),
    BotCommand("全部命令", "/commands", "Hermes 自己的分页列表"),
)

/**
 * The assistant's own page: everything either of you said to it and everything it answered, in one
 * place, shared by both partners. Nothing here is end-to-end encrypted (the assistant has no key), and
 * the main chat only ever shows a one-line stub per question, or nothing, depending on the setting.
 * [targetId]: scroll to this question when opening from a stub in the main chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotScreen(repo: ChatRepository, onBack: () -> Unit, targetId: String? = null, onLocation: (LocalMessage) -> Unit = {}, onFile: (LocalMessage) -> Unit = {}, onVoiceCall: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ChatterApp
    val scope = rememberCoroutineScope()
    val palette = LocalChatPalette.current
    val botNameRaw by repo.botName.collectAsStateWithLifecycle()
    val botName = botNameRaw.ifEmpty { "助手" }
    val online by repo.botOnline.collectAsStateWithLifecycle()
    val typing by repo.botTyping.collectAsStateWithLifecycle()
    val features by repo.features.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()
    val tick by repo.botTick.collectAsStateWithLifecycle()
    val reactions by repo.reactions.collectAsStateWithLifecycle()
    val uploadProgress by repo.uploadProgress.collectAsStateWithLifecycle()
    val playingId by repo.voice.playing.collectAsStateWithLifecycle()
    val playProgress by repo.voice.progress.collectAsStateWithLifecycle()
    val voiceSpeed by repo.voice.speed.collectAsStateWithLifecycle()
    var emojiTarget by remember { mutableStateOf<LocalMessage?>(null) }
    val uploading by repo.uploading.collectAsStateWithLifecycle()
    val sharedMedia by repo.sharedMedia.collectAsStateWithLifecycle()
    val pickedFile by repo.pickedFile.collectAsStateWithLifecycle()
    val quick by repo.quickCommands.collectAsStateWithLifecycle()
    val stickerFavs by repo.stickerFavorites.collectAsStateWithLifecycle()
    val transcripts by repo.transcripts.collectAsStateWithLifecycle()
    var limit by remember { mutableIntStateOf(80) }
    var messages by remember { mutableStateOf<List<LocalMessage>>(emptyList()) }
    LaunchedEffect(tick, limit) { messages = repo.botHistory(limit) }
    var input by rememberSaveable { mutableStateOf(repo.prefs.botDraft) }
    LaunchedEffect(input) { repo.prefs.botDraft = input }
    var bigText by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<LocalMessage?>(null) }
    var editing by remember { mutableStateOf<LocalMessage?>(null) }
    var preview by remember { mutableStateOf<List<Uri>?>(null) }
    val systemCamera = rememberSystemCamera { uri -> preview = listOf(uri) }
    var capturing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.setMarkUnreadBot(false) }
    var deleteTarget by remember { mutableStateOf<LocalMessage?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var video by remember { mutableStateOf<MediaInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stickerPanel by remember { mutableStateOf(false) }
    var slashMenu by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf(false) }
    var driveMode by remember { mutableStateOf(repo.prefs.botDriveMode) }
    var transcribing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { favIds = repo.favorites().map { it.id }.toSet() }
    val me = repo.me
    val peerName = repo.prefs.peerName.ifEmpty { "对方" }
    val connected = conn == WsClient.State.CONNECTED
    val listState = rememberLazyListState()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) dictating = true }
    fun fail(e: Throwable) = Toast.makeText(ctx, "发送失败：${e.message}", Toast.LENGTH_LONG).show()
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        repo.sendText(t, replyTo?.id, toBot = true)
        replyTo = null
    }
    var pendingCall by remember { mutableStateOf(false) }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val go = pendingCall
        pendingCall = false
        if (go && hasCallAudioPerms(ctx, false)) onVoiceCall()
        else if (go) Toast.makeText(ctx, "需要麦克风权限才能通话", Toast.LENGTH_LONG).show()
    }
    fun dialAssistant() {
        if (!connected) {
            Toast.makeText(ctx, "未连接到服务器", Toast.LENGTH_SHORT).show()
            return
        }
        if (!online) {
            Toast.makeText(ctx, "助手不在线", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasCallAudioPerms(ctx, false)) onVoiceCall()
        else {
            pendingCall = true
            callPerms.launch(callPermissions(false))
        }
    }

    LaunchedEffect(sharedMedia) { if (sharedMedia.isNotEmpty()) { preview = sharedMedia; repo.sharedMedia.value = emptyList() } }
    LaunchedEffect(pickedFile) {
        val uri = pickedFile ?: return@LaunchedEffect
        repo.pickedFile.value = null
        busy = true
        try { repo.sendFile(uri, toBot = true) } catch (e: Exception) { fail(e) } finally { busy = false }
    }
    val pickedSticker by repo.pickedSticker.collectAsStateWithLifecycle()
    LaunchedEffect(pickedSticker) {
        val uri = pickedSticker ?: return@LaunchedEffect
        repo.pickedSticker.value = null
        runCatching { repo.addCustomSticker(uri) }.onFailure { Toast.makeText(ctx, "添加失败：${it.message}", Toast.LENGTH_SHORT).show() }
            .onSuccess { Toast.makeText(ctx, "已添加到表情收藏", Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(playingId) {
        while (playingId != null) {
            repo.voice.tick()
            delay(200)
        }
    }

    // While this page is in front, assistant replies are read at once and never notify.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        fun show() {
            repo.botVisible = true
            repo.markBotRead()
            Notifications.cancelBot(ctx)
        }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) show()
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> show()
                Lifecycle.Event.ON_PAUSE -> { repo.botVisible = false; repo.voice.stop() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            repo.botVisible = false
            repo.voice.stop()
            app.stopSpeaking()
        }
    }
    LaunchedEffect(tick) { if (repo.botVisible) repo.markBotRead() }

    val newestFirst = remember(messages) { messages.asReversed() }
    var lastNewestId by remember { mutableStateOf<String?>(null) }
    var unreadWhileAway by remember { mutableIntStateOf(0) }
    LaunchedEffect(newestFirst) {
        val newest = newestFirst.firstOrNull() ?: return@LaunchedEffect
        if (newest.id == lastNewestId) return@LaunchedEffect
        lastNewestId = newest.id
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
        else if (newest.fromBot) unreadWhileAway++
    }
    // Opened from a stub in the main chat: land on that question.
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(newestFirst, targetId) {
        if (jumped || targetId == null) return@LaunchedEffect
        val idx = newestFirst.indexOfFirst { it.id == targetId }
        if (idx >= 0) { jumped = true; listState.scrollToItem(idx) } else if (messages.size >= limit) limit += 80
    }
    // Older history comes straight from the phone's copy; widen the window when the top is near.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }.distinctUntilChanged().collect { near -> if (near && messages.size >= limit) limit += 80 }
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    LaunchedEffect(showJump) { if (!showJump) unreadWhileAway = 0 }

    val status = when {
        !connected -> "连不上服务器"
        typing -> "正在回复…"
        online -> "在线 · 两人共享 · 不加密"
        else -> "离线 · NAS 上的 Hermes 没有连上"
    }
    val statusColor = when {
        !connected -> MaterialTheme.colorScheme.error
        typing -> MaterialTheme.colorScheme.primary
        online -> palette.online
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(Modifier.fillMaxSize().background(palette.canvas)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = MaterialTheme.colorScheme.onSurface),
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                        title = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_bot), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(botName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { dialAssistant() }, enabled = connected) {
                                Icon(Icons.Default.Call, contentDescription = "和助手打电话", tint = if (connected && online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { driveMode = !driveMode; repo.prefs.botDriveMode = driveMode; if (!driveMode) app.stopSpeaking() else app.checkChineseTts { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }; Toast.makeText(ctx, if (driveMode) "开车模式：新回复自动朗读" else "已关闭自动朗读", Toast.LENGTH_SHORT).show() }) {
                                Icon(painterResource(R.drawable.ic_volume), contentDescription = "自动朗读", tint = if (driveMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { send("/stop"); app.stopSpeaking() }, enabled = connected) {
                                Icon(painterResource(R.drawable.ic_stop), contentDescription = "停止", tint = if (connected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { send("/new") }, enabled = connected) {
                                Icon(painterResource(R.drawable.ic_new_chat), contentDescription = "新对话", tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            }
                        },
                    )
                    if (connected && !online) {
                        Text(
                            "$botName 现在不在线，发出的问题会在它上线后收到。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    }
                }
            },
            bottomBar = {
                Column {
                    if (quick.isNotEmpty() && input.isBlank() && editing == null) {
                        LazyRow(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(quick) { q ->
                                Text(
                                    q, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable { if (q.endsWith("：") || q.endsWith(":")) input = q else send(q) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    InputBar(
                        value = input,
                        onValueChange = { input = it; if (it == "/") slashMenu = true },
                        onSend = {
                            val e = editing
                            if (e != null) {
                                repo.editText(e.id, input)
                                editing = null
                            } else {
                                send(input)
                            }
                            input = ""
                        },
                        onPickImage = { (ctx as? MainActivity)?.pickImages() },
                        onPickFile = { (ctx as? MainActivity)?.pickFile() },
                        onVoice = { f, d ->
                            scope.launch {
                                // Voice → text when the phone can, so Hermes needs no STT of its own.
                                if (repo.prefs.botVoiceToText) {
                                    val t = Speech.transcribe(ctx, repo, f)
                                    if (!t.isNullOrEmpty()) { send(t); return@launch }
                                }
                                runCatching { repo.sendVoice(f, d, toBot = true) }.onFailure { fail(it) }
                            }
                        },
                        onNeedMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        busy = busy || uploading > 0,
                        replyTo = replyTo,
                        replyAuthor = { if (it == me) "我" else if (it == LocalMessage.BOT_ID) botName else peerName },
                        onCancelReply = { replyTo = null },
                        editing = editing,
                        onCancelEdit = { editing = null; input = "" },
                        placeholder = "问 $botName…",
                        onSticker = { stickerPanel = !stickerPanel },
                        stickerOpen = stickerPanel,
                        onSlash = { slashMenu = true },
                        transcribe = { f -> Speech.transcribe(ctx, repo, f) },
                        onSendText = { t -> send(t) },
                        onCapture = { capturing = true },
                        onCaptureLong = systemCamera,
                        onDictate = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dictating = true
                            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        below = {
                            if (stickerPanel) {
                                StickerPanel(
                                    catalog = repo.stickers, serverUrl = repo.prefs.serverUrl, favorites = stickerFavs,
                                    onPick = { ref -> repo.sendSticker(ref, toBot = true, replyTo = replyTo?.id); replyTo = null },
                                    onAddCustom = { (ctx as? MainActivity)?.pickSticker() },
                                    onRemoveFavorite = { ref -> scope.launch { runCatching { repo.removeStickerFavorite(ref) } } },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                                )
                            }
                        },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                if (messages.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_bot), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(38.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(botName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "在这里直接问，图片、文件、语音、表情都可以。\n这一页你们俩共享，不做端到端加密；\n主聊天里没 @ 它的话它看不到。\n输入 / 或点左下角的 / 看命令。",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        itemsIndexed(newestFirst, key = { _, m -> m.id }) { i, m ->
                            val older = newestFirst.getOrNull(i + 1)
                            val dayStart = older == null || dayOf(older.ts) != dayOf(m.ts)
                            val grouped = !dayStart && older != null && older.from == m.from && m.ts - older.ts < 3 * 60_000L
                            Column {
                                if (dayStart) DayChip(m.ts)
                                Spacer(Modifier.height(if (grouped) 3.dp else 10.dp))
                                if (m.kind == "recall") {
                                    SystemLine(if (m.from == me) "你撤回了一条消息" else if (m.fromBot) "$botName 撤回了一条消息" else "$peerName 撤回了一条消息")
                                    return@Column
                                }
                                Bubble(
                                    m = m,
                                    mine = m.from == me,
                                    me = me,
                                    read = m.seq != null,
                                    peerName = peerName,
                                    botName = botName,
                                    label = if (m.from != me && !m.fromBot) peerName else null,
                                    showBotTag = false,
                                    reactions = reactions[m.id].orEmpty(),
                                    uploadProgress = uploadProgress[m.id],
                                    playing = playingId == m.id,
                                    playProgress = playProgress,
                                    mediaUrl = repo.api::mediaUrl,
                                    onImageClick = { viewer = it.id },
                                    onVideoClick = { video = it },
                                    onFileClick = { mm -> scope.launch { runCatching { MediaSaver.open(ctx, repo, mm) }.onFailure { Toast.makeText(ctx, "打不开：${it.message}", Toast.LENGTH_SHORT).show() } } },
                                    onFileDownload = { startDownload(ctx, repo, it) },
                                    onVoiceClick = { msg -> repo.markPlayed(msg.id); msg.media?.let { repo.voice.toggle(msg.id, repo.api.mediaUrl(it.id), it.size) } },
                                    quoteText = { repo.quoteText(it) },
                                    onEdit = { msg -> editing = msg; replyTo = null; input = msg.text ?: "" },
                                    onQuoteClick = { id -> val idx = newestFirst.indexOfFirst { it.id == id }; if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) } },
                                    onReply = { replyTo = it },
                                    onDelete = { deleteTarget = it },
                                    onSave = { mm -> scope.launch { val msg = runCatching { MediaSaver.saveToGallery(ctx, repo, mm) }.getOrElse { "保存失败：${it.message}" }; Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } },
                                    onShare = { mm -> scope.launch { runCatching { MediaSaver.share(ctx, repo, mm) }.onFailure { Toast.makeText(ctx, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() } } },
                                    onRetry = { repo.retry(it.id) },
                                    onCancelUpload = { repo.cancelUpload(it.id) },
                                    onReact = { msg, e -> repo.toggleReaction(msg.id, e) },
                                    onRedial = {},
                                    extras = BubbleExtras(
                                        onLocationClick = { onLocation(it) },
                                        onOpenFile = { onFile(it) },
                                        tileUrl = if (features.tiles) repo.api::tileUrl else null,
                                        tileDatum = features.tileDatum ?: "wgs84",
                                        onRecall = { repo.recall(it.id) },
                                        onBigText = { bigText = it.text },
                                        isFavorite = m.id in favIds,
                                        onFavorite = { msg ->
                                            val on = repo.toggleFavorite(msg)
                                            favIds = if (on) favIds + msg.id else favIds - msg.id
                                            Toast.makeText(ctx, if (on) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                                        },
                                        onSpeak = if (m.fromBot) ({ app.speak(it) }) else null,
                                        transcript = transcripts[m.id],
                                        transcribing = m.id in transcribing,
                                        onTranscribe = { msg ->
                                            transcribing = transcribing + msg.id
                                            scope.launch {
                                                val t = runCatching { repo.transcribe(msg) }.getOrNull()
                                                transcribing = transcribing - msg.id
                                                if (t == null) Toast.makeText(ctx, Speech.lastError ?: "没有识别到内容", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        linkHttp = if (repo.prefs.linkPreview) repo.http else null,
                                        serverUrl = repo.prefs.serverUrl,
                                        speed = voiceSpeed,
                                        onCycleSpeed = { repo.prefs.voiceSpeed = repo.voice.cycleSpeed() },
                                        onSeek = { repo.voice.seek(it) },
                                        onMoreEmoji = { emojiTarget = it },
                                    ),
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = showJump, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Box {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = Modifier.size(42.dp).clickable { scope.launch { listState.animateScrollToItem(0) } }) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部", tint = MaterialTheme.colorScheme.primary) }
                        }
                        if (unreadWhileAway > 0) Text(
                            unreadWhileAway.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }

    if (slashMenu) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { slashMenu = false }, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
            Text("命令", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            BOT_COMMANDS.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        slashMenu = false
                        val body = input.trim().removePrefix("/").trim()
                        if (c.arg) {
                            if (body.isEmpty()) input = c.command + " " else { send(c.command + " " + body); input = "" }
                        } else {
                            send(c.command); input = ""
                        }
                    }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.command, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text(c.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (dictating) {
        ServerDictateDialog(repo = repo, app = app, driveMode = driveMode, replying = typing, onClose = { dictating = false }, onSend = { t -> if (t.isNotBlank()) send(t) })
    }

    viewer?.let { id ->
        val images = remember(messages) { messages.filter { it.kind == "image" && it.media?.id?.isNotEmpty() == true } }
        if (images.any { it.id == id }) MediaViewer(repo, images, id, onClose = { viewer = null }) else viewer = null
    }
    video?.let { VideoPlayerDialog(repo, it, onClose = { video = null }) }
    bigText?.let { BigTextDialog(it) { bigText = null } }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条消息？") },
            text = { Text("「双方删除」会把它从服务器、对方手机和助手那里一并移除。") },
            confirmButton = { TextButton(onClick = { repo.deleteForBoth(target.id); deleteTarget = null }) { Text("双方删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                    TextButton(onClick = { repo.deleteLocal(target.id); deleteTarget = null }) { Text("仅本机") }
                }
            },
        )
    }
    if (capturing) CaptureScreen(onDone = { uri -> capturing = false; preview = listOf(uri) }, onCancel = { capturing = false })
    preview?.let { uris ->
        SendPreviewDialog(uris, onSend = { list, cap, orig, _ -> repo.sendMedia(list, cap, orig, toBot = true); preview = null }, onCancel = { preview = null }, allowOnce = false)
    }
    emojiTarget?.let { target ->
        EmojiPickerDialog(
            recent = EmojiRecents.get(ctx),
            onPick = { e -> EmojiRecents.touch(ctx, e); repo.toggleReaction(target.id, e) },
            onClose = { emojiTarget = null },
        )
    }
}

