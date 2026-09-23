package ink.jvm.chatter.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen gallery over every image message: pinch-zoom, double-tap, swipe between pictures, share, save.
 * 1.7: drag down to close (the picture follows the finger, the backdrop fades), long-press for the WeChat menu
 * (转发 / 收藏 / 保存 / 分享 / 识别二维码 / 查看全部). [viewOnce]: no share / save, a burn hint instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(
    repo: ChatRepository,
    images: List<LocalMessage>,
    startId: String,
    onClose: () -> Unit,
    viewOnce: Boolean = false,
    onForward: ((LocalMessage) -> Unit)? = null,
    onFavorite: ((LocalMessage) -> Unit)? = null,
    onGallery: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val clipboard = LocalClipboardManager.current
    val start = images.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { images.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<String?>(null) }
    val current = images.getOrNull(pager.currentPage)

    fun save(media: MediaInfo) = scope.launch {
        val msg = runCatching { MediaSaver.saveToGallery(ctx, repo, media) }.getOrElse { "保存失败：${it.message}" }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
    fun scanQr(media: MediaInfo) = scope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val f = MediaSaver.fetch(ctx, repo, media)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1600)
                val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val source = com.google.zxing.RGBLuminanceSource(bmp.width, bmp.height, pixels)
                val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                com.google.zxing.MultiFormatReader().decode(bitmap).text
            }.getOrNull()
        }
        if (text == null) Toast.makeText(ctx, "没有识别到二维码", Toast.LENGTH_SHORT).show() else qr = text
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - dragY / (900f * density)).coerceIn(0.15f, 1f)))) {
            HorizontalPager(
                state = pager, userScrollEnabled = !zoomed && dragY == 0f,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dragY; val s = (1f - dragY / (2400f * density)).coerceIn(0.6f, 1f); scaleX = s; scaleY = s },
            ) { page ->
                val media = images[page].media ?: return@HorizontalPager
                ZoomableImage(
                    url = repo.api.mediaUrl(media.id),
                    onTap = { chrome = !chrome },
                    onZoomChanged = { zoomed = it },
                    active = pager.currentPage == page,
                    onDismissDrag = { dy -> dragY = (dragY + dy).coerceAtLeast(0f) },
                    onGestureEnd = { if (dragY > 140f * density) onClose() else dragY = 0f },
                    onLongPress = { if (!viewOnce) menu = true },
                )
            }
            if (chrome && dragY == 0f) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White) }
                    Text(if (viewOnce) "🔥 看一次 · 关闭后即焚" else "${pager.currentPage + 1} / ${images.size}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val media = current?.media
                    if (!viewOnce) {
                        IconButton(onClick = {
                            media ?: return@IconButton
                            scope.launch { runCatching { MediaSaver.share(ctx, repo, media) }.onFailure { Toast.makeText(ctx, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() } }
                        }) { Icon(Icons.Default.Share, contentDescription = "分享", tint = Color.White) }
                        IconButton(onClick = { media?.let { save(it) } }) { Icon(painterResource(R.drawable.ic_download), contentDescription = "保存", tint = Color.White) }
                    }
                }
                current?.text?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding().padding(16.dp),
                    )
                }
            }
        }
    }

    if (menu && current?.media != null) {
        val media = current.media
        AlertDialog(
            onDismissRequest = { menu = false },
            text = {
                Column {
                    onForward?.let { fw -> MenuLine("转发") { menu = false; fw(current) } }
                    onFavorite?.let { fav -> MenuLine("收藏") { menu = false; fav(current) } }
                    MenuLine("保存图片") { menu = false; save(media) }
                    MenuLine("分享") { menu = false; scope.launch { runCatching { MediaSaver.share(ctx, repo, media) } } }
                    MenuLine("识别图中二维码") { menu = false; scanQr(media) }
                    onGallery?.let { g -> MenuLine("查看全部图片") { menu = false; g() } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menu = false }) { Text("取消") } },
        )
    }
    qr?.let { text ->
        val isUrl = text.startsWith("http://") || text.startsWith("https://")
        AlertDialog(
            onDismissRequest = { qr = null },
            title = { Text("二维码内容") },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                if (isUrl) TextButton(onClick = { qr = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text))) } }) { Text("打开链接") }
                else TextButton(onClick = { qr = null; clipboard.setText(AnnotatedString(text)); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { qr = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun MenuLine(label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}

@Composable
private fun ZoomableImage(
    url: String,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    active: Boolean,
    onDismissDrag: (Float) -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero; onZoomChanged(false) } }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else {
                            scale = 2.5f
                            offset = Offset((size.width / 2 - tap.x) * 1.5f, (size.height / 2 - tap.y) * 1.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 6f)
                    if (s <= 1.01f && zoom == 1f && kotlin.math.abs(pan.y) >= kotlin.math.abs(pan.x)) {
                        // Not zoomed and dragging vertically: the whole viewer follows the finger (drag down to close).
                        onDismissDrag(pan.y)
                        return@detectTransformGestures
                    }
                    val maxX = (size.width * (s - 1)) / 2
                    val maxY = (size.height * (s - 1)) / 2
                    scale = s
                    offset = if (s <= 1.01f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                }
            }
            .pointerInput(Unit) {
                // Passive watcher: once every finger is up, let the viewer settle (close or snap back).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do { val e = awaitPointerEvent(PointerEventPass.Final) } while (e.changes.any { it.pressed })
                    onGestureEnd()
                }
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
    )
}

/** Inline video player (system VideoView + MediaController) over the authenticated media URL. */
@Composable
fun VideoPlayerDialog(repo: ChatRepository, media: MediaInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<java.io.File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(media.id) {
        runCatching { MediaSaver.fetch(ctx, repo, media) { progress = it } }.onSuccess { file = it }.onFailure { error = it.message }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val f = file
            if (f == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                    Text(error ?: (if (progress > 0f) "下载中 ${(progress * 100).toInt()}%" else "准备播放…"), color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
            } else AndroidView(
                factory = { c ->
                    android.widget.VideoView(c).apply {
                        val mc = android.widget.MediaController(c)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoPath(f.absolutePath)
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { _, _, _ -> Toast.makeText(c, "无法播放", Toast.LENGTH_SHORT).show(); true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White) }
                Text(media.name ?: "视频", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { runCatching { MediaSaver.share(ctx, repo, media) } } }) { Icon(Icons.Default.Share, contentDescription = "分享", tint = Color.White) }
                IconButton(onClick = {
                    scope.launch {
                        val msg = runCatching { MediaSaver.saveToGallery(ctx, repo, media) }.getOrElse { "保存失败：${it.message}" }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(painterResource(R.drawable.ic_download), contentDescription = "保存", tint = Color.White) }
            }
        }
    }
}

/** Tap-to-dismiss scrim used by the dialogs above. */
@Composable
internal fun Modifier.plainClickable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
