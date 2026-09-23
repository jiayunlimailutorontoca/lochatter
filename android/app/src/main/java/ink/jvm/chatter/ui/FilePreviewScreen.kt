package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.util.FileType
import ink.jvm.chatter.util.MediaSaver
import ink.jvm.chatter.util.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WeChat-like file page (1.6): big type icon, name, size, who sent it and when; the file downloads as soon as the
 * page opens (with a progress bar) and then offers 用其他应用打开 / 保存到手机 / 分享. Small text files and pictures
 * preview inline. View-once files only open. The Wi-Fi-only setting asks before pulling large files on mobile data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(repo: ChatRepository, message: LocalMessage, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = message.media
    if (media == null || media.id.isEmpty()) {
        LaunchedEffect(Unit) { Toast.makeText(ctx, "这个文件还没上传完", Toast.LENGTH_SHORT).show(); onBack() }
        return
    }
    val type = remember(media.name, media.mime) { FileType.of(media.name, media.mime) }
    val badge = remember(media.name, media.mime) { FileType.badge(media.name, media.mime) }
    var file by remember { mutableStateOf<File?>(MediaSaver.cachedFile(ctx, media)) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askMobile by remember { mutableStateOf(file == null && repo.prefs.wifiOnlyMedia && repo.onMobileData() && media.size > 300_000) }
    var textPreview by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saved = SavedMedia.isSaved(ctx, media.id)
    val sender = if (message.from == repo.me) "我" else if (message.fromBot) repo.prefs.botName.ifEmpty { "助手" } else repo.prefs.peerName.ifEmpty { "对方" }

    fun download() {
        if (downloading) return
        downloading = true; error = null; askMobile = false
        scope.launch {
            runCatching { MediaSaver.fetch(ctx, repo, media) { p -> progress = p } }
                .onSuccess { file = it }
                .onFailure { error = it.message ?: "下载失败" }
            downloading = false
        }
    }
    LaunchedEffect(Unit) { if (file == null && !askMobile) download() }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        if (FileType.isTextPreviewable(media.name, media.mime, f.length())) {
            textPreview = withContext(Dispatchers.IO) { runCatching { f.readText().take(20_000) }.getOrNull() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(media.name ?: "文件", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            val f = file
            if (f != null && type == FileType.IMAGE) {
                AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)).background(Color(type.colorArgb)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(media.name ?: media.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(fmtSize(if (media.size > 0) media.size else f?.length() ?: 0L), "$sender · ${fmtTime(message.ts)}").joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            when {
                askMobile -> {
                    Text("设置里开了「仅 Wi-Fi 下载原图和文件」，现在是移动数据。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { download() }) { Text("仍然下载（${fmtSize(media.size)}）") }
                }
                downloading -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (media.size > 0) "正在下载 ${(progress * 100).toInt()}% · ${fmtSize((media.size * progress).toLong())} / ${fmtSize(media.size)}" else "正在下载…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error != null -> {
                    Text("下载失败：$error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { download() }) { Text("重试") }
                }
                f != null -> {
                    Button(
                        onClick = { scope.launch { runCatching { MediaSaver.open(ctx, repo, media) }.onFailure { Toast.makeText(ctx, "打不开：${it.message}", Toast.LENGTH_SHORT).show() } } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("用其他应用打开") }
                    if (!message.once) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                enabled = !saved && !saving,
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        val msg = runCatching { MediaSaver.save(ctx, repo, media) }.getOrElse { "保存失败：${it.message}" }
                                        saving = false
                                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(painterResource(if (saved) R.drawable.ic_check else R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (saved) "已保存" else if (type == FileType.IMAGE || type == FileType.VIDEO) "保存到相册" else "保存到手机")
                            }
                            OutlinedButton(
                                onClick = { scope.launch { runCatching { MediaSaver.share(ctx, repo, media) }.onFailure { Toast.makeText(ctx, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() } } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("分享")
                            }
                        }
                        if (saved) Text("在「下载/lochatter」或相册里", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text("阅后即焚的文件不能保存或分享", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    textPreview?.let { t ->
                        Spacer(Modifier.height(20.dp))
                        Text(
                            t, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
