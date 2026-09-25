package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.media.CallNotes
import ink.jvm.chatter.media.LocalSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Timed captions from finished calls. The model runs only when the user taps 总结摘要. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallNotesScreen(repo: ChatRepository, startId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notes by CallNotes.items.collectAsStateWithLifecycle()
    val ready by CallNotes.ready.collectAsStateWithLifecycle()
    var current by remember(startId) { mutableStateOf(startId) }
    LaunchedEffect(Unit) { CallNotes.ensure(ctx) }
    val openedFromCall = startId != null
    BackHandler(enabled = current != null && !openedFromCall) { current = null }
    val note = notes.firstOrNull { it.id == current }
    if (current == null) {
        ListPage(notes, ready, onBack) { current = it }
    } else if (note == null) {
        if (!ready) {
            Waiting(onBack)
        } else {
            Missing(onBack)
        }
    } else {
        DetailPage(
            repo = repo,
            note = note,
            onBack = { if (openedFromCall) onBack() else current = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(notes: List<CallNotes.Note>, ready: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val day = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("通话文字记录") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            )
        },
    ) { pad ->
        if (!ready) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("正在读取…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    "打完电话后，这台手机说的话会按时间列在这里。\n点「总结摘要」才会用本机模型，不会自动整理，也不会上传。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(notes, key = { it.id }) { note ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(note.id) },
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                (if (note.video) "视频通话" else "语音通话") + "  " + Instant.ofEpochMilli(note.at).atZone(ZoneId.systemDefault()).format(day),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                note.summary.ifBlank { note.lines.firstOrNull()?.text ?: "" }.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPage(repo: ChatRepository, note: CallNotes.Note, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(note.id) { mutableStateOf(false) }
    val day = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    val whenLabel = Instant.ofEpochMilli(note.at).atZone(ZoneId.systemDefault()).format(day)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (note.video) "视频通话" else "语音通话") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            if (!LocalSummary.ready(ctx)) {
                                Toast.makeText(ctx, "先下载纪要模型", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            busy = true
                            scope.launch {
                                runCatching { LocalSummary.summarize(ctx, CallNotes.modelText(note)) }
                                    .onSuccess { CallNotes.setSummary(ctx, note.id, it) }
                                    .onFailure { Toast.makeText(ctx, it.message ?: "整理失败", Toast.LENGTH_LONG).show() }
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "整理中" else "总结摘要") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "$whenLabel · ${duration(note.seconds)} · 只记录这台手机说的话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.trimmed) {
                    Spacer(Modifier.height(6.dp))
                    Text("前面的话已略", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (note.summary.isNotBlank() || busy) {
                item {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("摘要", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (busy && note.summary.isBlank()) "正在这台手机上整理，不会上传。" else note.summary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (note.summary.isNotBlank()) {
                                TextButton(onClick = {
                                    repo.sendText("通话纪要\n${note.summary}")
                                    Toast.makeText(ctx, "已发到对话", Toast.LENGTH_SHORT).show()
                                }) { Text("发到对话") }
                            }
                        }
                    }
                }
            }
            itemsIndexed(note.lines) { _, line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(clock(line.at), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(84.dp))
                    Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            item {
                TextButton(onClick = {
                    CallNotes.delete(ctx, note.id)
                    onBack()
                }) { Text("删除这条记录") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Waiting(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通话文字记录") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Text("正在读取…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Missing(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通话文字记录") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Text("这条记录没有了", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun clock(at: Long): String {
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(z.hour, z.minute, z.second)
}

private fun duration(seconds: Long): String {
    if (seconds <= 0) return "未计时"
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}
