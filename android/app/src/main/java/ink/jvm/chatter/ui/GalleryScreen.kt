package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.jvm.chatter.R
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.util.FileType
import ink.jvm.chatter.util.SavedMedia
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月")
private val Tabs = listOf("图片", "视频", "文件")

/**
 * All media in the chat, grouped by month: image / video grids and a file list. Long-press starts multi-select
 * (WeChat style): 全选, 保存 (pictures and videos to the gallery, files to Downloads), 定位到消息 for a single
 * pick; the overflow menu saves a whole tab in one go. [onSaveAll] does the saving and returns how many succeeded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    load: suspend () -> List<LocalMessage>,
    mediaUrl: (String) -> String,
    onBack: () -> Unit,
    onOpenImage: (LocalMessage) -> Unit,
    onOpenVideo: (MediaInfo) -> Unit,
    onOpenFile: (LocalMessage) -> Unit,
    onJumpTo: (LocalMessage) -> Unit,
    onSaveAll: suspend (List<LocalMessage>, (done: Int, total: Int) -> Unit) -> Int,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var all by remember { mutableStateOf<List<LocalMessage>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saveProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(Unit) {
        all = runCatching { load() }.getOrDefault(emptyList())
            .filter { !it.media?.id.isNullOrEmpty() && !it.once }
            .sortedByDescending { it.ts }
        loading = false
    }
    val images = remember(all) { all.filter { it.kind == "image" || it.kind == "album" } }
    val videos = remember(all) { all.filter { it.kind == "video" } }
    val files = remember(all) { all.filter { it.kind == "file" } }
    val current = when (tab) { 0 -> images; 1 -> videos; else -> files }
    val byId = remember(all) { all.associateBy { it.id } }

    fun exitSelect() { selecting = false; selected = emptySet() }
    fun toggle(m: LocalMessage) {
        selected = if (m.id in selected) selected - m.id else selected + m.id
        if (selected.isEmpty()) selecting = false
    }
    fun startSelect(m: LocalMessage) { selecting = true; selected = selected + m.id }
    fun save(list: List<LocalMessage>) {
        if (list.isEmpty() || saveJob != null) return
        saveProgress = 0 to list.size
        saveJob = scope.launch {
            val ok = runCatching { onSaveAll(list) { d, t -> saveProgress = d to t } }.getOrDefault(0)
            saveProgress = null
            saveJob = null
            Toast.makeText(ctx, "已保存 $ok 项" + if (ok < list.size) "（失败 ${list.size - ok}）" else "", Toast.LENGTH_LONG).show()
            exitSelect()
        }
    }
    androidx.activity.compose.BackHandler(enabled = selecting) { exitSelect() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${selected.size} 项" else "图片与文件") },
                navigationIcon = {
                    if (selecting) IconButton(onClick = { exitSelect() }) { Icon(Icons.Default.Close, contentDescription = "取消选择") }
                    else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { selected = if (selected.size == current.size) emptySet() else current.map { it.id }.toSet() }) { Text(if (selected.size == current.size) "全不选" else "全选") }
                        if (selected.size == 1) TextButton(onClick = { byId[selected.first()]?.let { onJumpTo(it) } }) { Text("定位") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { save(current.filter { it.id in selected }) }) { Text("保存") }
                    } else if (current.isNotEmpty()) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("选择") }, onClick = { menu = false; selecting = true })
                            DropdownMenuItem(
                                text = { Text(if (tab == 2) "全部保存到手机（${current.size}）" else "全部保存到相册（${current.size}）") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                                onClick = { menu = false; save(current) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                Tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; exitSelect() }, text = { Text(t) }) }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                tab == 0 -> MediaGrid(images, "还没有图片", mediaUrl, selecting, selected, onOpen = { if (selecting) toggle(it) else onOpenImage(it) }, onLong = { startSelect(it) })
                tab == 1 -> MediaGrid(videos, "还没有视频", mediaUrl, selecting, selected, onOpen = { m -> if (selecting) toggle(m) else m.media?.let(onOpenVideo) }, onLong = { startSelect(it) })
                else -> FileList(files, selecting, selected, onOpen = { if (selecting) toggle(it) else onOpenFile(it) }, onLong = { startSelect(it) })
            }
        }
    }
    saveProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在保存 $done / $total") },
            text = { LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { saveJob?.cancel(); saveJob = null; saveProgress = null; exitSelect() }) { Text("取消") } },
        )
    }
}

private fun monthOf(ts: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    list: List<LocalMessage>,
    empty: String,
    mediaUrl: (String) -> String,
    selecting: Boolean,
    selected: Set<String>,
    onOpen: (LocalMessage) -> Unit,
    onLong: (LocalMessage) -> Unit,
) {
    if (list.isEmpty()) { EmptyState(empty); return }
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val groups = remember(list) { list.groupBy { monthOf(it.ts) }.toSortedMap(compareByDescending { it }) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { (month, msgs) ->
            item(key = "h$month", span = { GridItemSpan(3) }) {
                Text(
                    month.format(monthFmt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            items(msgs, key = { it.id }) { m ->
                val media = m.media ?: return@items
                val picked = m.id in selected
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(
                            onClick = { onOpen(m) },
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong(m) },
                        ),
                ) {
                    val thumb = if (m.kind == "video") media.thumbId else (media.thumbId ?: media.id)
                    if (thumb != null) {
                        AsyncImage(model = mediaUrl(thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (m.kind == "video") {
                        Icon(
                            Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(32.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                        )
                        media.durationMs?.let {
                            Text(
                                fmtClip(it), color = Color.White, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (selecting) {
                        Box(Modifier.fillMaxSize().background(if (picked) Color.Black.copy(alpha = 0.25f) else Color.Transparent))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                .background(if (picked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) { if (picked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    } else if (SavedMedia.isSaved(ctx, media.id)) {
                        Icon(
                            Icons.Default.Check, contentDescription = "已保存", tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(list: List<LocalMessage>, selecting: Boolean, selected: Set<String>, onOpen: (LocalMessage) -> Unit, onLong: (LocalMessage) -> Unit) {
    if (list.isEmpty()) { EmptyState("还没有文件"); return }
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { m ->
            val media = m.media ?: return@items
            val picked = m.id in selected
            val type = FileType.of(media.name, media.mime)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                    .combinedClickable(
                        onClick = { onOpen(m) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong(m) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(Color(type.colorArgb)), contentAlignment = Alignment.Center) {
                    Text(FileType.badge(media.name, media.mime), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(media.name ?: media.id, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(fmtSize(media.size), fmtTime(m.ts), if (SavedMedia.isSaved(ctx, media.id)) "已保存" else null).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selecting) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { if (picked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                } else {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.Transparent, modifier = Modifier.size(0.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
