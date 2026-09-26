package ink.jvm.chatter.ui

import android.Manifest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.R
import ink.jvm.chatter.call.CallManager
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.WsClient
import ink.jvm.chatter.util.ChatExport
import ink.jvm.chatter.util.MediaSaver
import android.net.Uri
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Where the main chat can navigate to (pages owned by MainActivity's Root). */
class ChatNav(
    val onSettings: () -> Unit,
    /** Open the assistant page, optionally scrolled to a question. */
    val onBot: (String?) -> Unit,
    val onGallery: () -> Unit,
    val onFavorites: () -> Unit,
    val onAnniversaries: () -> Unit,
    /** 1.6: open the in-app map for a location message. */
    val onLocation: (LocalMessage) -> Unit = {},
    /** 1.6: WeChat-like picker page; true when the pick goes to the assistant. */
    val onPickLocation: (Boolean) -> Unit = {},
    /** 1.6: the file page. */
    val onFile: (LocalMessage) -> Unit = {},
    /** 1.7: back to the home tabs (the chat is no longer the root). */
    val onBack: (() -> Unit)? = null,
    /** 1.8: peer profile and the shared album. */
    val onProfile: () -> Unit = {},
    val onAlbum: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(repo: ChatRepository, calls: CallManager, onLogout: () -> Unit, nav: ChatNav) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ink.jvm.chatter.ChatterApp
    val scope = rememberCoroutineScope()
    val palette = LocalChatPalette.current
    val callState by calls.state.collectAsStateWithLifecycle()
    val inCall = callState !is CallManager.State.Idle
    val messages by repo.messages.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()
    val online by repo.peerOnline.collectAsStateWithLifecycle()
    val lastSeen by repo.peerLastSeen.collectAsStateWithLifecycle()
    val typing by repo.peerTyping.collectAsStateWithLifecycle()
    val readUpto by repo.peerReadUpto.collectAsStateWithLifecycle()
    val reactions by repo.reactions.collectAsStateWithLifecycle()
    val uploadProgress by repo.uploadProgress.collectAsStateWithLifecycle()
    val playingId by repo.voice.playing.collectAsStateWithLifecycle()
    val playProgress by repo.voice.progress.collectAsStateWithLifecycle()
    val voiceSpeed by repo.voice.speed.collectAsStateWithLifecycle()
    val voiceFinished by repo.voice.finished.collectAsStateWithLifecycle()
    var emojiTarget by remember { mutableStateOf<LocalMessage?>(null) }
    var selecting by remember { mutableStateOf<Set<String>?>(null) }
    var forwardItems by remember { mutableStateOf<List<LocalMessage>?>(null) }
    var bigText by remember { mutableStateOf<String?>(null) }
    var deleteMany by remember { mutableStateOf<Set<String>?>(null) }
    val voicePlayed by repo.voicePlayed.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = selecting != null) { selecting = null }
    val features by repo.features.collectAsStateWithLifecycle()
    var migrateDialog by remember { mutableStateOf(false) }
    val peerBattery by repo.peerBattery.collectAsStateWithLifecycle()
    val peerCharging by repo.peerCharging.collectAsStateWithLifecycle()
    val anniversaries by repo.anniversaries.collectAsStateWithLifecycle()
    val scheduled by repo.scheduledList.collectAsStateWithLifecycle()
    val transcripts by repo.transcripts.collectAsStateWithLifecycle()
    val stickerFavs by repo.stickerFavorites.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(repo.prefs.draft) }
    var editing by remember { mutableStateOf<LocalMessage?>(null) }
    var preview by remember { mutableStateOf<List<Uri>?>(null) }
    val systemCamera = rememberSystemCamera { uri -> preview = listOf(uri) }
    val e2e by repo.e2eState.collectAsStateWithLifecycle()
    val keyConflict by repo.keyConflict.collectAsStateWithLifecycle()
    val sharedText by repo.sharedText.collectAsStateWithLifecycle()
    val sharedMedia by repo.sharedMedia.collectAsStateWithLifecycle()
    val botNameRaw by repo.botName.collectAsStateWithLifecycle()
    val botName = botNameRaw.ifEmpty { "助手" }
    val botReplied by repo.botReplied.collectAsStateWithLifecycle()
    // "collapsed": one line per question; "hidden": the main chat shows nothing of the assistant.
    val botMode = repo.prefs.botInMain
    var toBot by rememberSaveable { mutableStateOf(false) }
    val pendingAction by MainActivity.pendingAction
    LaunchedEffect(input) { repo.prefs.draft = if (editing == null) input else repo.prefs.draft }
    LaunchedEffect(sharedText) { sharedText?.let { input = if (input.isBlank()) it else input + "\n" + it; repo.sharedText.value = null } }
    LaunchedEffect(sharedMedia) { if (sharedMedia.isNotEmpty()) { preview = sharedMedia; repo.sharedMedia.value = emptyList() } }
    val hasOlder by repo.hasOlder.collectAsStateWithLifecycle()
    val loadingOlder by repo.loadingOlder.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    val uploading by repo.uploading.collectAsStateWithLifecycle()
    val sendBusy = busy || uploading > 0
    var bgWarn by remember { mutableStateOf(bgWarning(ctx, repo)) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var video by remember { mutableStateOf<MediaInfo?>(null) }
    var menu by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<LocalMessage?>(null) }
    // Everything sent while the chip is lit, or while replying to the assistant, goes to it in the clear.
    val askBot = toBot || replyTo?.fromBot == true
    var deleteTarget by remember { mutableStateOf<LocalMessage?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    var safetyDialog by remember { mutableStateOf(false) }
    var bgDialog by remember { mutableStateOf(false) }
    var assist by remember { mutableStateOf<AssistRequest?>(null) }
    var summaryCount by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var stickerPanel by remember { mutableStateOf(false) }
    var askTarget by remember { mutableStateOf<LocalMessage?>(null) }
    var scheduleDialog by remember { mutableStateOf(false) }
    var remindTarget by remember { mutableStateOf<LocalMessage?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var mobileConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var transcribing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { favIds = repo.favorites().map { it.id }.toSet() }
    val listState = rememberLazyListState()
    val me = repo.me
    setQuoteSelf(me, botName)
    val peerName = repo.prefs.peerName.ifEmpty { "对方" }
    val connected = conn == WsClient.State.CONNECTED
    // Unread divider: peer messages above this seq were unread when the screen opened.
    var readAtOpen by remember { mutableLongStateOf(repo.myReadUpto) }
    var unreadWhileAway by remember { mutableIntStateOf(0) }

    LaunchedEffect(playingId) {
        while (playingId != null) {
            repo.voice.tick()
            delay(200)
        }
    }
    // A peer voice note finished: play the next unheard one after it, like a walkie-talkie.
    LaunchedEffect(voiceFinished) {
        val doneId = voiceFinished ?: return@LaunchedEffect
        repo.voice.finished.value = null
        val list = repo.messages.value
        val i = list.indexOfFirst { it.id == doneId }
        if (i < 0 || list[i].from == me) return@LaunchedEffect
        val next = list.drop(i + 1).firstOrNull { it.kind == "audio" && it.from != me && it.media?.id?.isNotEmpty() == true && (it.seq ?: Long.MAX_VALUE) > repo.myReadUpto - 200 }
            ?: return@LaunchedEffect
        repo.voice.chime()
        delay(280)
        next.media?.let { repo.voice.toggle(next.id, repo.api.mediaUrl(it.id), it.size) }
    }

    // Read marks only while this screen is actually in front of the user.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        fun show() {
            readAtOpen = repo.myReadUpto
            repo.chatVisible = true
            repo.markRead()
            repo.setMarkUnreadPeer(false)
        }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) show()
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    show()
                    bgWarn = bgWarning(ctx, repo)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    repo.chatVisible = false
                    repo.voice.stop()
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            repo.chatVisible = false
            repo.voice.stop()
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(ctx, if (ok) "再长按麦克风开始录音" else "需要麦克风权限才能发语音", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!repo.prefs.bgGuideShown) bgDialog = true
    }

    // Calls: ask for mic (and camera) right before dialing.
    var pendingVideoCall by remember { mutableStateOf<Boolean?>(null) }
    val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val v = pendingVideoCall
        pendingVideoCall = null
        if (v != null) {
            if (hasCallAudioPerms(ctx, v)) calls.start(v)
            else Toast.makeText(ctx, "需要麦克风权限才能通话", Toast.LENGTH_LONG).show()
        }
    }
    fun dial(v: Boolean) {
        if (inCall) return
        if (!connected) {
            Toast.makeText(ctx, "未连接到服务器", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasCallAudioPerms(ctx, v)) calls.start(v) else {
            pendingVideoCall = v
            callPermLauncher.launch(callPermissions(v))
        }
    }

    fun fail(e: Throwable) = Toast.makeText(ctx, "发送失败：${e.message}", Toast.LENGTH_LONG).show()
    // The system pickers are registered on the Activity: during a call the picker counts as leaving the app,
    // and a composable-scoped launcher would be torn down (and its result lost) if the call went to PiP.
    val pickedFile by repo.pickedFile.collectAsStateWithLifecycle()
    LaunchedEffect(pickedFile) {
        val uri = pickedFile ?: return@LaunchedEffect
        repo.pickedFile.value = null
        busy = true
        try { repo.sendFile(uri, askBot) } catch (e: Exception) { fail(e) } finally { busy = false }
    }
    // A picture picked for "add to stickers".
    val pickedSticker by repo.pickedSticker.collectAsStateWithLifecycle()
    LaunchedEffect(pickedSticker) {
        val uri = pickedSticker ?: return@LaunchedEffect
        repo.pickedSticker.value = null
        runCatching { repo.addCustomSticker(uri) }.onFailure { Toast.makeText(ctx, "添加失败：${it.message}", Toast.LENGTH_SHORT).show() }
            .onSuccess { Toast.makeText(ctx, "已添加到表情收藏", Toast.LENGTH_SHORT).show() }
    }

    // Saving files to Downloads needs the legacy storage permission only on Android 8/9.
    var pendingDownload by remember { mutableStateOf<MediaInfo?>(null) }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = pendingDownload
        pendingDownload = null
        if (ok && m != null) startDownload(ctx, repo, m) else if (m != null) Toast.makeText(ctx, "没有存储权限", Toast.LENGTH_SHORT).show()
    }
    /** "Wi-Fi only" guard: on mobile data, big downloads ask first. */
    fun guarded(size: Long, block: () -> Unit) {
        if (repo.prefs.wifiOnlyMedia && repo.onMobileData() && size > 300_000) mobileConfirm = block else block()
    }
    fun download(m: MediaInfo) = guarded(m.size) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = m
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(ctx, repo, m)
        }
    }
    fun openFile(m: MediaInfo) = guarded(m.size) {
        scope.launch { runCatching { MediaSaver.open(ctx, repo, m) }.onFailure { Toast.makeText(ctx, "打不开：${it.message}", Toast.LENGTH_SHORT).show() } }
    }
    fun saveMedia(m: MediaInfo) = guarded(m.size) {
        scope.launch {
            val msg = runCatching { MediaSaver.saveToGallery(ctx, repo, m) }.getOrElse { "保存失败：${it.message}" }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
    fun shareMedia(m: MediaInfo) = guarded(m.size) {
        scope.launch { runCatching { MediaSaver.share(ctx, repo, m) }.onFailure { Toast.makeText(ctx, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() } }
    }
    fun openVideo(m: LocalMessage, media: MediaInfo) = guarded(media.size) {
        repo.viewOnceOpened(m)
        video = media
    }
    fun openImage(m: LocalMessage) {
        repo.viewOnceOpened(m)
        viewer = m.id
    }

    LaunchedEffect(pendingAction, connected) {
        val a = pendingAction ?: return@LaunchedEffect
        if (!a.startsWith("call_")) return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        MainActivity.pendingAction.value = null
        when (a) { "call_voice" -> dial(false); "call_video" -> dial(true) }
    }

    // The assistant's answers never render here; its questions become one-line stubs, or vanish in "hidden" mode.
    val newestFirst = remember(messages, botMode) {
        messages.filter { !it.fromBot && !it.isBotCommand && !(botMode == "hidden" && it.toBot) }.asReversed()
    }
    fun jumpTo(id: String) {
        val idx = newestFirst.indexOfFirst { it.id == id }
        if (idx >= 0) {
            scope.launch { listState.animateScrollToItem(idx) }
            return
        }
        scope.launch {
            if (repo.reveal(id)) {
                val i = repo.messages.value.asReversed().indexOfFirst { it.id == id }
                if (i >= 0) listState.animateScrollToItem(i)
            } else {
                Toast.makeText(ctx, "原消息不在本机记录里", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(pendingAction) {
        val a = pendingAction ?: return@LaunchedEffect
        if (a.startsWith("jump:")) { MainActivity.pendingAction.value = null; jumpTo(a.removePrefix("jump:")) }
    }

    var lastNewestId by remember { mutableStateOf(newestFirst.firstOrNull()?.id) }
    LaunchedEffect(messages) {
        val newest = newestFirst.firstOrNull()
        if (newest?.id == lastNewestId) return@LaunchedEffect // status change or older page, not a new message
        lastNewestId = newest?.id
        if (newest == null) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
        else if (newest.from != me && !newest.fromBot) unreadWhileAway++
    }
    LaunchedEffect(messages) { if (repo.chatVisible) repo.markRead() }
    LaunchedEffect(listState, hasOlder, loadingOlder) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && last >= total - 4
        }.distinctUntilChanged().collect { nearTop ->
            if (nearTop && hasOlder && !loadingOlder) repo.loadOlder()
        }
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    LaunchedEffect(showJump) { if (!showJump) unreadWhileAway = 0 }

    val subtitle = when {
        conn == WsClient.State.CONNECTING -> "连接中…"
        conn == WsClient.State.DISCONNECTED -> "连不上服务器"
        typing -> "正在输入…"
        online -> "在线"
        else -> lastSeen?.let { "最后在线 " + fmtTime(it) } ?: "离线"
    }
    val subtitleColor = when {
        !connected -> MaterialTheme.colorScheme.error
        typing -> MaterialTheme.colorScheme.primary
        online -> palette.online
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val firstUnreadId = remember(newestFirst, readAtOpen) {
        if (readAtOpen <= 0) null else newestFirst.lastOrNull { it.from != me && !it.fromBot && it.seq != null && it.seq > readAtOpen }?.id
    }
    val todayAnniv = remember(anniversaries) { anniversaries.filter { it.daysLeft() == 0L } }
    val earpiece by repo.voice.earpiece.collectAsStateWithLifecycle()
    val avatarId by repo.peerAvatarId.collectAsStateWithLifecycle()
    val soonAnniv = remember(anniversaries) { anniversaries.filter { it.daysLeft() in 1..3 }.minByOrNull { it.daysLeft() } }

    Box(Modifier.fillMaxSize().background(palette.canvas)) {
    palette.wallpaper?.let { path ->
        AsyncImage(model = java.io.File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.9f)
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Column {
                    if (selecting != null) {
                        SelectionTopBar(
                            count = selecting!!.size, onClose = { selecting = null },
                            onForward = { forwardItems = newestFirst.filter { it.id in selecting!! }.asReversed() },
                            onFavorite = { scope.launch { newestFirst.filter { it.id in selecting!! && it.id !in favIds && it.kind != "call" && it.kind != "pat" }.forEach { if (repo.toggleFavorite(it)) favIds = favIds + it.id }; Toast.makeText(ctx, "已收藏", Toast.LENGTH_SHORT).show(); selecting = null } },
                            onSave = { val list = newestFirst.filter { it.id in selecting!! && it.media?.id?.isNotEmpty() == true && !it.once }; scope.launch { var ok = 0; list.forEach { m2 -> m2.media?.let { md -> if (runCatching { MediaSaver.save(ctx, repo, md) }.isSuccess) ok++ } }; Toast.makeText(ctx, "已保存 $ok 项", Toast.LENGTH_SHORT).show(); selecting = null } },
                            onDelete = { deleteMany = selecting },
                        )
                    } else if (searching) {
                        SearchBar(query, onQuery = { query = it }, onClose = { searching = false; query = "" })
                    } else {
                    TopAppBar(
                        navigationIcon = { nav.onBack?.let { back -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = MaterialTheme.colorScheme.onSurface),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // Double-tap the name or avatar: 拍一拍. weight keeps this text out of the buttons on the right.
                                modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { nav.onProfile() },
                                        onDoubleTap = {
                                            if (connected) { repo.sendPat(); app.vibrate(longArrayOf(0, 40)) }
                                        },
                                    )
                                },
                            ) {
                                Avatar(peerName, size = 40.dp, image = avatarId?.let { repo.api.mediaUrl(it) })
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        peerBattery?.let { b ->
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                painterResource(if (peerCharging == true) R.drawable.ic_battery_charging else R.drawable.ic_battery),
                                                contentDescription = "电量", modifier = Modifier.size(13.dp),
                                                tint = when { peerCharging == true -> palette.online; b <= 20 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                                            )
                                            Text("$b%", style = MaterialTheme.typography.labelSmall, color = if (b <= 20 && peerCharging != true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (online && connected && !typing) {
                                            Box(Modifier.size(7.dp).clip(CircleShape).background(palette.online))
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "菜单") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("搜索消息") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, onClick = { menu = false; searching = true })
                                DropdownMenuItem(text = { Text("后台运行设置") }, leadingIcon = { Icon(painterResource(R.drawable.ic_shield), contentDescription = null) }, onClick = { menu = false; bgDialog = true })
                                DropdownMenuItem(text = { Text("清空聊天记录") }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }, onClick = { menu = false; clearDialog = true })
                                DropdownMenuItem(text = { Text("退出登录") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) }, onClick = { menu = false; onLogout() })
                            }
                        },
                    )
                    }
                    if (e2e == ChatRepository.E2eState.PEER_KEY_CHANGED) {
                        Banner("对方的安全码变了（换了手机或重装）。请重新核对安全码。", "核对", error = true) { safetyDialog = true }
                    }
                    bgWarn?.let { warn -> Banner(warn, "去设置", error = true) { bgDialog = true } }
                    todayAnniv.forEach { a ->
                        val yrs = a.years()
                        Banner("今天是「${a.title}」" + if (a.yearly && yrs > 0) "，$yrs 周年 🎉" else " 🎉", "看看", error = false) { nav.onAnniversaries() }
                    }
                    if (todayAnniv.isEmpty()) soonAnniv?.let { a ->
                        Banner("还有 ${a.daysLeft()} 天就是「${a.title}」", "看看", error = false) { nav.onAnniversaries() }
                    }
                    if (earpiece) {
                        Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF8E8E93)).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text("听筒模式", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!searching && selecting == null) InputBar(
                value = input,
                onValueChange = { input = it; repo.sendTyping() },
                onSend = {
                    val e = editing
                    if (e != null) {
                        repo.editText(e.id, input)
                        editing = null
                        input = repo.prefs.draft
                    } else {
                        repo.sendText(input, replyTo?.id, askBot)
                        input = ""
                        repo.prefs.draft = ""
                        replyTo = null
                    }
                },
                editing = editing,
                onCancelEdit = { editing = null; input = repo.prefs.draft },
                encrypted = e2e != ChatRepository.E2eState.OFF,
                toBot = askBot,
                onPickImage = { (ctx as? MainActivity)?.pickImages() },
                onPickFile = { (ctx as? MainActivity)?.pickFile() },
                onVoice = { f, d -> scope.launch { runCatching { repo.sendVoice(f, d, askBot) }.onFailure { fail(it) } } },
                onNeedMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                busy = sendBusy,
                replyTo = replyTo,
                replyAuthor = { if (it == me) "我" else if (it == LocalMessage.BOT_ID) botName else peerName },
                onCancelReply = { replyTo = null },
                onSticker = { stickerPanel = !stickerPanel },
                stickerOpen = stickerPanel,
                onSchedule = { if (input.isNotBlank()) scheduleDialog = true },
                onLocation = { nav.onPickLocation(askBot) },
                onCall = { v -> dial(v) },
                onAssistant = if (botNameRaw.isNotEmpty()) ({ nav.onBot(null) }) else null,
                assistantLabel = botName,
                onFavorites = { nav.onFavorites() },
                onCapture = { capturing = true },
                onCaptureLong = systemCamera,
                onSummarizeChat = { summaryCount = true },
                onPolish = {
                    if (input.isBlank()) Toast.makeText(ctx, "先在输入框里写好要润色的话", Toast.LENGTH_SHORT).show()
                    else assist = AssistRequest.Polish
                },
                onSuggest = { assist = AssistRequest.Suggest },
                transcribe = { f -> ink.jvm.chatter.media.Speech.transcribe(ctx, repo, f) },
                onSendText = { t -> repo.sendText(t, replyTo?.id, askBot); replyTo = null },
                banner = if (scheduled.isNotEmpty()) ({
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_schedule), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        val s = scheduled.first()
                        Text("${scheduled.size} 条定时消息，最近 ${fmtTime(s.at)}：${s.text.take(20)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { repo.cancelScheduled(s.id) }) { Text("取消") }
                    }
                }) else null,
                below = {
                    StickerPanel(
                        catalog = repo.stickers, serverUrl = repo.prefs.serverUrl, favorites = stickerFavs,
                        onPick = { ref -> repo.sendSticker(ref, askBot, replyTo?.id); replyTo = null },
                        onAddCustom = { (ctx as? MainActivity)?.pickSticker() },
                        onRemoveFavorite = { ref -> scope.launch { runCatching { repo.removeStickerFavorite(ref) } } },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                    )
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            if (searching) {
                SearchResults(repo, query, me, peerName, botName) { m -> searching = false; query = ""; if (m.fromBot || m.toBot) nav.onBot(m.id) else jumpTo(m.id) }
            } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().pointerInput(stickerPanel) { detectTapGestures { if (stickerPanel) stickerPanel = false } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                itemsIndexed(newestFirst, key = { _, m -> m.id }) { i, m ->
                    val older = newestFirst.getOrNull(i + 1)
                    val dayStart = older == null || m.ts - older.ts > 5 * 60_000L
                    val grouped = !dayStart && older != null && older.from == m.from && m.ts - older.ts < 3 * 60_000L
                    Column {
                        if (dayStart) TimeChip(m.ts)
                        if (m.id == firstUnreadId) UnreadDivider()
                        if (m.kind == "ttl") {
                            SystemLine((if (m.from == me) "你" else peerName) + "把消息定时销毁设为「" + ChatExport.ttlLabel(m.text?.toLongOrNull() ?: 0) + "」")
                            return@Column
                        }
                        if (m.kind == "recall") {
                            val again = m.from == me && repo.recalledTexts.containsKey(m.id)
                            SystemLine(if (m.from == me) "你撤回了一条消息" + (if (again) " · 重新编辑" else "") else "$peerName 撤回了一条消息", onClick = if (again) ({ input = repo.recalledTexts[m.id] ?: "" }) else null)
                            return@Column
                        }
                        if (m.kind == "pat") {
                            SystemLine(if (m.from == me) "你拍了拍 $peerName" else "$peerName 拍了拍你", onClick = { if (connected) repo.sendPat() })
                            return@Column
                        }
                        if (m.toBot) {
                            Spacer(Modifier.height(if (grouped) 3.dp else 10.dp))
                            BotMentionRow(m, me, peerName, botName, replied = m.id in botReplied, onOpen = { nav.onBot(m.id) }, onLongPress = { deleteTarget = m })
                            return@Column
                        }
                        Spacer(Modifier.height(if (grouped) 3.dp else 10.dp))
                        Bubble(
                            m = m,
                            mine = m.from == me,
                            me = me,
                            read = m.seq != null && m.seq <= readUpto,
                            peerName = peerName,
                            botName = botName,
                            reactions = reactions[m.id].orEmpty(),
                            uploadProgress = uploadProgress[m.id],
                            playing = playingId == m.id,
                            playProgress = playProgress,
                            mediaUrl = repo.api::mediaUrl,
                            onImageClick = { openImage(it) },
                            onVideoClick = { openVideo(m, it) },
                            onFileClick = { openFile(it) },
                            onFileDownload = { download(it) },
                            onVoiceClick = { msg -> repo.markPlayed(msg.id); msg.media?.let { repo.voice.toggle(msg.id, repo.api.mediaUrl(it.id), it.size) } },
                            quoteText = { repo.quoteText(it) },
                            onEdit = { msg -> editing = msg; replyTo = null; input = msg.text ?: "" },
                            onQuoteClick = { jumpTo(it) },
                            onReply = { replyTo = it },
                            onDelete = { deleteTarget = it },
                            onSave = { saveMedia(it) },
                            onShare = { shareMedia(it) },
                            onRetry = { repo.retry(it.id) },
                            onCancelUpload = { repo.cancelUpload(it.id) },
                            onReact = { msg, e -> repo.toggleReaction(msg.id, e) },
                            onRedial = { text -> dial(text.orEmpty().startsWith("video:")) },
                            extras = BubbleExtras(
                                onLocationClick = { nav.onLocation(it) },
                                onOpenFile = { nav.onFile(it) },
                                tileUrl = if (features.tiles) repo.api::tileUrl else null,
                                tileDatum = features.tileDatum ?: "wgs84",
                                onForward = { forwardItems = listOf(it) },
                                onSelect = { msg -> val cur = selecting; selecting = if (cur == null) setOf(msg.id) else if (msg.id in cur) (cur - msg.id).ifEmpty { null } else cur + msg.id },
                                onRecall = { repo.recall(it.id) },
                                onBigText = { bigText = it.text },
                                selected = selecting?.let { m.id in it },
                                voiceUnread = m.kind == "audio" && m.from != me && m.id !in voicePlayed,
                                isFavorite = m.id in favIds,
                                onFavorite = { msg ->
                                    val on = repo.toggleFavorite(msg)
                                    favIds = if (on) favIds + msg.id else favIds - msg.id
                                    Toast.makeText(ctx, if (on) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                                },
                                onAskBot = if (botNameRaw.isNotEmpty()) ({ askTarget = it }) else null,
                                transcript = transcripts[m.id],
                                transcribing = m.id in transcribing,
                                onTranscribe = { msg ->
                                    transcribing = transcribing + msg.id
                                    scope.launch {
                                        val t = runCatching { repo.transcribe(msg) }.getOrNull()
                                        transcribing = transcribing - msg.id
                                        if (t == null) Toast.makeText(ctx, ink.jvm.chatter.media.Speech.lastError ?: "没有识别到内容", Toast.LENGTH_LONG).show()
                                    }
                                },
                                onAddSticker = { msg -> msg.media?.let { md ->
                                    scope.launch {
                                        runCatching {
                                            val f = MediaSaver.fetch(ctx, repo, md)
                                            repo.addCustomSticker(Uri.fromFile(f))
                                        }.onFailure { Toast.makeText(ctx, "添加失败：${it.message}", Toast.LENGTH_SHORT).show() }
                                            .onSuccess { Toast.makeText(ctx, "已添加到表情收藏", Toast.LENGTH_SHORT).show() }
                                    }
                                } },
                                linkHttp = if (repo.prefs.linkPreview) repo.http else null,
                                serverUrl = repo.prefs.serverUrl,
                                onceSeen = m.once && repo.onceSeen(m.id),
                                speed = voiceSpeed,
                                onCycleSpeed = { repo.prefs.voiceSpeed = repo.voice.cycleSpeed() },
                                onSeek = { repo.voice.seek(it) },
                                onMoreEmoji = { emojiTarget = it },
                                onRemind = { remindTarget = it },
                                onLiveStop = if (m.from == me && m.kind == "location") ({ repo.stopLiveLocation() }) else null,
                            ),
                        )
                    }
                }
                if (loadingOlder) {
                    item(key = "history-loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (messages.isEmpty() && !loadingOlder) {
                Column(modifier = Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(peerName, size = 84.dp, textStyle = MaterialTheme.typography.displaySmall, image = avatarId?.let { repo.api.mediaUrl(it) })
                    Spacer(Modifier.height(16.dp))
                    Text(peerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("还没有消息，打个招呼吧", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(
                visible = showJump,
                enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Box {
                    Surface(
                        shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp,
                        modifier = Modifier.size(42.dp).clickable { scope.launch { listState.animateScrollToItem(0) } },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部", tint = MaterialTheme.colorScheme.primary) }
                    }
                    if (unreadWhileAway > 0) {
                        Text(
                            unreadWhileAway.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            }
        }
    }
    }

    viewer?.let { id ->
        val target = messages.firstOrNull { it.id == id }
        val images = remember(messages, id) {
            if (target?.once == true) listOfNotNull(target) else messages.filter { (it.kind == "image" || it.kind == "album") && it.media?.id?.isNotEmpty() == true && !it.once }
        }
        if (images.any { it.id == id }) MediaViewer(repo, images, id, onClose = { viewer = null }, viewOnce = target?.once == true, onForward = { forwardItems = listOf(it) }, onFavorite = { m2 -> if (repo.toggleFavorite(m2)) { favIds = favIds + m2.id; Toast.makeText(ctx, "已收藏", Toast.LENGTH_SHORT).show() } else favIds = favIds - m2.id }, onGallery = { viewer = null; nav.onGallery() }) else viewer = null
    }
    video?.let { VideoPlayerDialog(repo, it, onClose = { video = null }) }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条消息？") },
            text = { Text("「双方删除」会把它从服务器和对方手机上一并移除。") },
            confirmButton = { TextButton(onClick = { repo.deleteForBoth(target.id); deleteTarget = null }) { Text("双方删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                    TextButton(onClick = { repo.deleteLocal(target.id); deleteTarget = null }) { Text("仅本机") }
                }
            },
        )
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清空聊天记录？") },
            text = { Text("「双方清空」会删除服务器上的全部消息和文件，对方手机上的记录也会被清除，无法恢复。") },
            confirmButton = { TextButton(onClick = { repo.clearForBoth(); clearDialog = false }) { Text("双方清空", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { clearDialog = false }) { Text("取消") }
                    TextButton(onClick = { repo.clearLocal(); clearDialog = false }) { Text("仅本机") }
                }
            },
        )
    }
    if (bgDialog) {
        BackgroundDialog(repo = repo, onClose = {
            repo.prefs.bgGuideShown = true
            bgDialog = false
            bgWarn = bgWarning(ctx, repo)
        })
    }
    if (summaryCount) {
        AlertDialog(
            onDismissRequest = { summaryCount = false },
            title = { Text("总结最近的消息") },
            text = { Text("用这台手机上的模型看双方最近的文字。不会上传。") },
            confirmButton = {
                Row {
                    TextButton(onClick = { summaryCount = false; assist = AssistRequest.Summary(20) }) { Text("20 条") }
                    TextButton(onClick = { summaryCount = false; assist = AssistRequest.Summary(50) }) { Text("50 条") }
                    TextButton(onClick = { summaryCount = false; assist = AssistRequest.Summary(100) }) { Text("100 条") }
                }
            },
            dismissButton = { TextButton(onClick = { summaryCount = false }) { Text("取消") } },
        )
    }
    ChatAssistDialog(repo, assist, input, onDraft = { input = it }, onClose = { assist = null })
    if (safetyDialog) SafetyNumberDialog(repo, repo.prefs.e2eVerified, onVerified = { repo.setVerified(it) }, onClose = { safetyDialog = false })
    keyConflict?.let { KeyConflictDialog(repo) }
    preview?.let { uris ->
        SendPreviewDialog(uris, onSend = { list, cap, orig, once -> repo.sendMedia(list, cap, orig, askBot, once); preview = null }, onCancel = { preview = null }, allowOnce = !askBot)
    }
    emojiTarget?.let { target ->
        EmojiPickerDialog(
            recent = ink.jvm.chatter.ui.EmojiRecents.get(ctx),
            onPick = { e -> ink.jvm.chatter.ui.EmojiRecents.touch(ctx, e); repo.toggleReaction(target.id, e) },
            onClose = { emojiTarget = null },
        )
    }
    askTarget?.let { target ->
        AskBotDialog(target, botName, onClose = { askTarget = null }) { q ->
            askTarget = null
            scope.launch {
                runCatching { repo.askBotAbout(target, q) }.onFailure { fail(it) }
                nav.onBot(null)
            }
        }
    }
    if (capturing) {
        CaptureScreen(onDone = { uri -> capturing = false; preview = listOf(uri) }, onCancel = { capturing = false })
    }
    remindTarget?.let { target ->
        ScheduleDialog(onClose = { remindTarget = null }, title = "提醒我", hint = "到点这台手机会通知你，不用发给对方。") { at ->
            repo.remind(ChatRepository.previewOf(target), at)
            remindTarget = null
            Toast.makeText(ctx, "到点会提醒你", Toast.LENGTH_SHORT).show()
        }
    }
    if (scheduleDialog) {
        ScheduleDialog(onClose = { scheduleDialog = false }) { at ->
            repo.schedule(input, at, askBot)
            input = ""; repo.prefs.draft = ""; replyTo = null
            scheduleDialog = false
            Toast.makeText(ctx, "将在 ${fmtTime(at)} 发出（手机需保持开机）", Toast.LENGTH_LONG).show()
        }
    }
    forwardItems?.let { items ->
        ForwardDialog(count = items.size, peerName = peerName, botName = botNameRaw.takeIf { it.isNotEmpty() }, onClose = { forwardItems = null }) { toBot ->
            forwardItems = null; selecting = null
            scope.launch {
                items.forEach { runCatching { repo.forward(it, toBot) }.onFailure { fail(it) } }
                Toast.makeText(ctx, "已转发", Toast.LENGTH_SHORT).show()
                if (toBot) nav.onBot(null)
            }
        }
    }
    bigText?.let { BigTextDialog(it) { bigText = null } }
    deleteMany?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleteMany = null },
            title = { Text("删除 ${ids.size} 条消息？") },
            text = { Text("「双方删除」会把它们从服务器和对方手机上一并移除。") },
            confirmButton = { TextButton(onClick = { ids.forEach { repo.deleteForBoth(it) }; deleteMany = null; selecting = null }) { Text("双方删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteMany = null }) { Text("取消") }
                    TextButton(onClick = { ids.forEach { repo.deleteLocal(it) }; deleteMany = null; selecting = null }) { Text("仅本机") }
                }
            },
        )
    }
    mobileConfirm?.let { go ->
        AlertDialog(
            onDismissRequest = { mobileConfirm = null },
            title = { Text("正在使用移动数据") },
            text = { Text("设置里开了「仅 Wi-Fi 下载原图和文件」。现在下载吗？") },
            confirmButton = { TextButton(onClick = { mobileConfirm = null; go() }) { Text("下载") } },
            dismissButton = { TextButton(onClick = { mobileConfirm = null }) { Text("取消") } },
        )
    }
}
