package ink.jvm.chatter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.R
import kotlinx.coroutines.launch
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage

/**
 * WeChat-like home (1.7): three tabs. 聊天 lists the two conversations (the other person, the assistant) with the
 * last message, time, unread badge and draft; 相册 is the media library; 我 is the profile with the settings
 * entries. [gallery] is the media library page supplied by the activity (it owns the viewers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: ChatRepository,
    onOpenChat: () -> Unit,
    onOpenBot: () -> Unit,
    onSettings: () -> Unit,
    onFavorites: () -> Unit,
    onAnniversaries: () -> Unit,
    onProfile: () -> Unit = {},
    onPeerProfile: () -> Unit = {},
    onAlbum: () -> Unit = {},
    gallery: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val messages by repo.messages.collectAsStateWithLifecycle()
    val botUnread by repo.botUnread.collectAsStateWithLifecycle()
    val botName by repo.botName.collectAsStateWithLifecycle()
    val online by repo.peerOnline.collectAsStateWithLifecycle()
    val typing by repo.peerTyping.collectAsStateWithLifecycle()
    val botTyping by repo.botTyping.collectAsStateWithLifecycle()
    val me = repo.me
    val peerName = repo.prefs.peerName.ifEmpty { "对方" }
    val readUpto = repo.myReadUpto
    val peerUnread = messages.count { it.from != me && !it.fromBot && !it.toBot && !it.isControl && (it.seq ?: 0L) > readUpto }
    val lastPeer = messages.lastOrNull { !it.isControl && !it.toBot && !it.fromBot && it.kind != "recall" }
    val lastBot = messages.lastOrNull { (it.fromBot || it.toBot) && !it.isControl }
    val draft = repo.prefs.draft
    val botDraft = repo.prefs.botDraft
    val pinned by repo.chatPinned.collectAsStateWithLifecycle()
    val botPinned by repo.pinBot.collectAsStateWithLifecycle()
    val markPeer by repo.markUnreadPeer.collectAsStateWithLifecycle()
    val markBot by repo.markUnreadBot.collectAsStateWithLifecycle()
    val peerAvatarId by repo.peerAvatarId.collectAsStateWithLifecycle()
    val myAvatarId by repo.myAvatarId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var rowMenu by remember { mutableStateOf<String?>(null) }
    val peerBadge = if (markPeer && peerUnread == 0) 1 else peerUnread
    val botBadge = if (markBot && botUnread == 0) 1 else botUnread
    val totalUnread = peerBadge + botBadge

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(listOf("lochatter", "图片与文件", "我")[tab], fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, label = { Text("聊天") },
                    icon = {
                        BadgedBox(badge = { if (totalUnread > 0) Badge { Text(if (totalUnread > 99) "99+" else totalUnread.toString()) } }) {
                            Icon(painterResource(R.drawable.ic_new_chat), contentDescription = null)
                        }
                    },
                )
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("相册") }, icon = { Icon(painterResource(R.drawable.ic_gallery), contentDescription = null) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text("我") }, icon = { Icon(Icons.Default.Settings, contentDescription = null) })
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    val peerRow: @Composable () -> Unit = {
                        ConversationRow(
                            name = peerName, online = online, pinned = pinned,
                            preview = when {
                                typing -> "正在输入…"
                                draft.isNotBlank() -> "[草稿] $draft"
                                lastPeer != null -> stripMarkdown(ChatRepository.previewOf(lastPeer))
                                else -> "还没有消息，打个招呼吧"
                            },
                            previewColor = if (typing) MaterialTheme.colorScheme.primary else if (draft.isNotBlank()) MaterialTheme.colorScheme.error else null,
                            time = lastPeer?.ts, unread = peerBadge, onClick = onOpenChat, onLongClick = { rowMenu = "peer" },
                        ) { Avatar(peerName, size = 48.dp, image = peerAvatarId?.let { repo.api.mediaUrl(it) }) }
                    }
                    val botRow: @Composable () -> Unit = {
                        if (botName.isNotEmpty()) {
                            ConversationRow(
                                name = botName, online = null, pinned = botPinned,
                                preview = when {
                                    botTyping -> "正在回复…"
                                    botDraft.isNotBlank() -> "[草稿] $botDraft"
                                    lastBot != null -> (if (lastBot.toBot) "问：" else "") + stripMarkdown(ChatRepository.previewOf(lastBot)).lineSequence().firstOrNull().orEmpty()
                                    else -> "问它点什么"
                                },
                                previewColor = if (botTyping) MaterialTheme.colorScheme.primary else if (botDraft.isNotBlank()) MaterialTheme.colorScheme.error else null,
                                time = lastBot?.ts, unread = botBadge, muted = true, onClick = onOpenBot, onLongClick = { rowMenu = "bot" },
                            ) {
                                Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_bot), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(26.dp))
                                }
                            }
                        }
                    }
                    val botAbove = botPinned && !pinned
                    if (botAbove) { botRow(); if (botName.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
                    peerRow()
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    if (!botAbove) { botRow(); if (botName.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
                }
                1 -> gallery()
                else -> MeTab(repo, peerName, myAvatarId, onSettings, onFavorites, onAnniversaries, onProfile, onPeerProfile, onAlbum)
            }
        }
    }
    rowMenu?.let { who ->
        val isPeer = who == "peer"
        val isPinned = if (isPeer) pinned else botPinned
        val marked = if (isPeer) markPeer else markBot
        AlertDialog(
            onDismissRequest = { rowMenu = null },
            title = { Text(if (isPeer) peerName else botName.ifEmpty { "助手" }) },
            text = {
                Column {
                    Text(
                        if (isPinned) "取消置顶" else "置顶",
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (isPeer) scope.launch { runCatching { repo.setPinned(!pinned) } } else repo.setPinBot(!botPinned)
                            rowMenu = null
                        }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (marked) "标为已读" else "标为未读",
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (isPeer) repo.setMarkUnreadPeer(!markPeer) else repo.setMarkUnreadBot(!markBot)
                            rowMenu = null
                        }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { rowMenu = null }) { Text("关闭") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    name: String, online: Boolean?, preview: String, previewColor: Color?, time: Long?, unread: Int, muted: Boolean = false,
    pinned: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, avatar: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box { avatar()
            if (online == true) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape).background(LocalChatPalette.current.online))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (pinned) Text("  置顶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                time?.let { Text(fmtTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = previewColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(CircleShape).background(if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error).padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (unread > 99) "99+" else unread.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun MeTab(
    repo: ChatRepository, peerName: String, avatarId: String?,
    onSettings: () -> Unit, onFavorites: () -> Unit, onAnniversaries: () -> Unit,
    onProfile: () -> Unit, onPeerProfile: () -> Unit, onAlbum: () -> Unit,
) {
    val myName = repo.prefs.userName.ifEmpty { "我" }
    val sign by repo.mySignature.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onProfile).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(myName, size = 64.dp, textStyle = MaterialTheme.typography.headlineSmall, image = avatarId?.let { repo.api.mediaUrl(it) })
            Spacer(Modifier.width(16.dp))
            Column {
                Text(myName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(sign.ifBlank { "和 $peerName 的私密聊天" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            MeEntry("我的资料", { Icon(painterResource(R.drawable.ic_new_chat), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, onProfile)
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            MeEntry("对方资料", { Icon(painterResource(R.drawable.ic_new_chat), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, onPeerProfile)
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            MeEntry("我们的相册", { Icon(painterResource(R.drawable.ic_gallery), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, onAlbum)
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            MeEntry("收藏", { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, onFavorites)
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            MeEntry("纪念日", { Icon(painterResource(R.drawable.ic_heart), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, onAnniversaries)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            MeEntry("设置", { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, onSettings)
        }
    }
}

@Composable
private fun MeEntry(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}
