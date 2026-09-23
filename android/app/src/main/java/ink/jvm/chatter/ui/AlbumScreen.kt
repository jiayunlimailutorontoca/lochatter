package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Photos and videos the two of you have sent, grouped by month. New uploads land here on their own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(repo: ChatRepository, onBack: () -> Unit, onOpen: (LocalMessage) -> Unit) {
    val ctx = LocalContext.current
    val tick by repo.messages.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<LocalMessage>>(emptyList()) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick.size, generation) { items = repo.albumMessages() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        repo.sendMedia(uris, caption = null, original = false, album = true)
        generation++
        Toast.makeText(ctx, "正在放进相册（${uris.size}）", Toast.LENGTH_SHORT).show()
    }
    val groups = remember(items) {
        val fmt = DateTimeFormatter.ofPattern("yyyy年M月")
        items.groupBy { m ->
            Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(fmt)
        }.toList()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("我们的相册") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }) { Icon(Icons.Default.Add, contentDescription = "添加") }
        },
    ) { pad ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("聊天里发的照片和视频会出现在这里。\n右下角也可以直接往相册里放。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.first }) { (label, month) ->
                    val cover = month.first()
                    Text("$label · ${month.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    AsyncImage(
                        model = cover.media?.let { repo.api.mediaUrl(it.thumbId ?: it.id) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(cover) },
                    )
                    Spacer(Modifier.height(6.dp))
                    month.drop(1).chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { m ->
                                AsyncImage(
                                    model = m.media?.let { repo.api.mediaUrl(it.thumbId ?: it.id) },
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onOpen(m) },
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
