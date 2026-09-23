package ink.jvm.chatter.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Look before you send: swipe through the picked photos / videos, drop the ones you don't want, add a caption. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendPreviewDialog(
    initial: List<Uri>,
    onSend: (uris: List<Uri>, caption: String, original: Boolean, once: Boolean) -> Unit,
    onCancel: () -> Unit,
    /** Offer 「看一次」 (view-once); off for the assistant, which has no page to burn from. */
    allowOnce: Boolean = true,
) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(initial) }
    var caption by remember { mutableStateOf("") }
    var original by remember { mutableStateOf(false) }
    var once by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Uri?>(null) }
    val pager = rememberPagerState { items.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(items.size) { if (items.isEmpty()) onCancel() }
    val isVideo: (Uri) -> Boolean = { u -> ctx.contentResolver.getType(u)?.startsWith("video/") == true }
    editing?.let { src ->
        ImageEditorDialog(
            source = src,
            onDone = { out -> items = items.map { if (it == src) out else it }; editing = null },
            onCancel = { editing = null },
        )
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.White) }
                Text(
                    if (items.size > 1) "${pager.currentPage + 1} / ${items.size}" else if (items.firstOrNull()?.let(isVideo) == true) "发送视频" else "发送图片",
                    color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                items.getOrNull(pager.currentPage)?.takeIf { !isVideo(it) }?.let { cur ->
                    TextButton(onClick = { editing = cur }) { Text("编辑", color = Color.White) }
                }
                IconButton(onClick = {
                    val cur = pager.currentPage
                    items = items.filterIndexed { i, _ -> i != cur }
                }) { Icon(Icons.Default.Delete, contentDescription = "移除这张", tint = Color.White) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                val uri = items.getOrNull(page) ?: return@HorizontalPager
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    if (isVideo(uri)) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }
            if (items.size > 1) {
                LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    itemsIndexed(items) { i, uri ->
                        AsyncImage(
                            model = uri, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(2.dp, if (i == pager.currentPage) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = original, onCheckedChange = { original = it })
                Text("原图（不压缩）", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (allowOnce) {
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = once, onCheckedChange = { once = it })
                    Text("看一次 🔥", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (once) Text("对方打开后 10 秒，双方手机上都会删除。", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = caption, onValueChange = { caption = it }, maxLines = 3,
                    placeholder = { Text("添加说明…", color = Color.White.copy(alpha = 0.6f)) },
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.14f), unfocusedContainerColor = Color.White.copy(alpha = 0.14f),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(bottom = 4.dp).size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable { onSend(items, caption, original, once) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(22.dp).padding(start = 2.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
