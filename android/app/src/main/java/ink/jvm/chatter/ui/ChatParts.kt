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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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

/** Pieces of the main chat screen that are not the screen itself: banners, dialogs, list rows. */

@Composable
internal fun Banner(text: String, action: String, error: Boolean, onClick: () -> Unit) {
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Icon(painterResource(if (error) R.drawable.ic_shield else R.drawable.ic_heart), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** "问助手": optional extra question before the message is forwarded in the clear. */
@Composable
internal fun AskBotDialog(m: LocalMessage, botName: String, onClose: () -> Unit, onAsk: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("问$botName") },
        text = {
            Column {
                Text("这条消息会以明文转给 $botName：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(stripMarkdown(ChatRepository.previewOf(m)), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("想问什么？（可留空）") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onAsk(q) }) { Text("发给$botName") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** Pick when a message should go out (today / tomorrow + hour:minute). Also used for 「提醒我」. */
@Composable
internal fun ScheduleDialog(onClose: () -> Unit, title: String = "定时发送", hint: String = "到点由这台手机发出，手机需要在线。", onPick: (Long) -> Unit) {
    val now = remember { java.time.LocalDateTime.now() }
    var day by remember { mutableIntStateOf(0) }
    var hour by remember { mutableIntStateOf((now.hour + 1) % 24) }
    var minute by remember { mutableIntStateOf(0) }
    val quick = listOf("30 分钟后" to 30L, "1 小时后" to 60L, "3 小时后" to 180L, "明早 8 点" to -1L)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    quick.forEach { (label, mins) ->
                        Text(
                            label, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable {
                                val at = if (mins > 0) System.currentTimeMillis() + mins * 60_000L
                                else java.time.LocalDate.now().plusDays(1).atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                onPick(at)
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("今天", "明天", "后天").forEachIndexed { i, label ->
                        Text(
                            label, style = MaterialTheme.typography.labelLarge,
                            color = if (day == i) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (day == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { day = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = hour.toString(), onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) hour = it } }, label = { Text("时") }, singleLine = true, modifier = Modifier.width(90.dp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = minute.toString().padStart(2, '0'), onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..59) minute = it } }, label = { Text("分") }, singleLine = true, modifier = Modifier.width(90.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = java.time.LocalDate.now().plusDays(day.toLong()).atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at <= System.currentTimeMillis()) return@TextButton
                onPick(at)
            }) { Text("定时") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** One line standing in for a question to the assistant; the answer lives on the assistant page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BotMentionRow(m: LocalMessage, me: Long, peerName: String, botName: String, replied: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val palette = LocalChatPalette.current
    val who = if (m.from == me) "你" else peerName
    val status = when {
        m.status == LocalMessage.FAILED -> "发送失败"
        replied -> "已回复"
        m.status == LocalMessage.PENDING -> "发送中…"
        else -> "等待回复…"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_bot), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "$who → $botName：" + ChatRepository.previewOf(m),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = if (replied) palette.online else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TtlPicker(current: Long, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val options = listOf(0L to "关闭", 3600L to "1 小时", 86400L to "1 天", 7 * 86400L to "7 天", 30 * 86400L to "30 天")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("消息定时销毁") },
        text = {
            Column {
                Text("开启后，从现在起双方发出的消息在设定时间后自动从服务器和两台手机上删除。", style = MaterialTheme.typography.bodyMedium)
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

@Composable
internal fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        TextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("搜索消息、文件名…") },
            modifier = Modifier.weight(1f).focusRequester(focus),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "清除") } },
        )
    }
}

@Composable
internal fun SearchResults(repo: ChatRepository, query: String, me: Long, peerName: String, botName: String, onOpen: (LocalMessage) -> Unit) {
    val ctx = LocalContext.current
    val tabs = listOf("全部", "图片", "文件", "链接", "位置", "日期")
    var tab by remember { mutableIntStateOf(0) }
    var dayResults by remember { mutableStateOf<List<LocalMessage>?>(null) }
    var results by remember { mutableStateOf<List<LocalMessage>>(emptyList()) }
    LaunchedEffect(query, tab) {
        if (tab == 5) return@LaunchedEffect
        delay(200)
        val q = query.trim()
        results = when (tab) {
            1 -> if (q.isEmpty()) repo.searchTyped("", listOf("image", "album", "video")) else repo.searchTyped(q, listOf("image", "album", "video"))
            2 -> repo.searchTyped(q, listOf("file"))
            3 -> repo.searchTyped(q, listOf("text", "card"), linksOnly = true)
            4 -> repo.searchTyped(q, listOf("location"))
            else -> if (q.isEmpty()) emptyList() else repo.search(q)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { i, label ->
                val on = i == tab
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            tab = i
                            if (i == 5) {
                                val now = java.util.Calendar.getInstance()
                                android.app.DatePickerDialog(ctx, { _, y, mo, d ->
                                    val zone = java.time.ZoneId.systemDefault()
                                    val start = java.time.LocalDate.of(y, mo + 1, d).atStartOfDay(zone).toInstant().toEpochMilli()
                                    val end = start + 24L * 3600_000
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                        dayResults = repo.messagesOnDay(start, end)
                                    }
                                }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        val shown = if (tab == 5) dayResults else results
        when {
            tab == 5 && shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("选一个日期，跳到那天的消息", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            tab == 0 && query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("输入关键字，或换一个分类", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            shown.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (query.isBlank()) "这里还没有" else "没有找到「$query」", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row {
                            Text(if (m.from == me) "我" else if (m.fromBot) botName else peerName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(fmtTime(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stripMarkdown(ChatRepository.previewOf(m)), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
internal fun UnreadDivider() {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        Text("  以下是新消息  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

@Composable
internal fun DayChip(ts: Long) {
    val palette = LocalChatPalette.current
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Surface(color = palette.dayChip, shape = CircleShape) {
            Text(fmtDay(ts), style = MaterialTheme.typography.labelSmall, color = palette.onDayChip, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}
