package ink.jvm.chatter.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.jvm.chatter.R
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.Reaction
import ink.jvm.chatter.data.ReplyInfo
import ink.jvm.chatter.data.StickerRef
import ink.jvm.chatter.util.LinkPreviews
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

internal val QUICK_EMOJI = listOf(
    "❤️", "👍", "😂", "😮", "😢", "🎉", "😘", "😭", "😡", "🙏", "👏", "🔥", "🌹", "💯", "🤔", "😴", "🤮", "🤣", "🥰", "👋",
)

private val URL_RE = Regex("""(https?://[^\s<>"'）】]+)|(\b1[3-9]\d{9}\b)""")

/** Text with tappable links (http/https) and phone numbers. */
@Composable
internal fun LinkedText(text: String, style: androidx.compose.ui.text.TextStyle) {
    val ctx = LocalContext.current
    val color = LocalContentColor.current
    val annotated = remember(text, color) {
        buildAnnotatedString {
            var last = 0
            for (m in URL_RE.findAll(text)) {
                append(text.substring(last, m.range.first))
                val v = m.value
                val target = if (v.startsWith("http")) v else "tel:$v"
                withLink(
                    LinkAnnotation.Url(
                        target,
                        TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)),
                    ) { link ->
                        val u = (link as LinkAnnotation.Url).url
                        runCatching { ctx.startActivity(Intent(if (u.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(u))) }
                            .onFailure { Toast.makeText(ctx, "无法打开", Toast.LENGTH_SHORT).show() }
                    },
                ) { append(v) }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(annotated, style = style)
}

/** Optional actions a screen may or may not offer on a bubble (all default to "not offered"). */
class BubbleExtras(
    val isFavorite: Boolean = false,
    val onFavorite: ((LocalMessage) -> Unit)? = null,
    val onAskBot: ((LocalMessage) -> Unit)? = null,
    /** Voice note → text; the result is shown under the clip. */
    val transcript: String? = null,
    val onTranscribe: ((LocalMessage) -> Unit)? = null,
    val transcribing: Boolean = false,
    /** Read aloud with the system TTS (assistant answers). */
    val onSpeak: ((LocalMessage) -> Unit)? = null,
    val onAddSticker: ((LocalMessage) -> Unit)? = null,
    /** OkHttp client for link cards; null turns previews off. */
    val linkHttp: OkHttpClient? = null,
    val serverUrl: String = "",
    /** View-once: the receiver already opened this one (shows the burnt placeholder). */
    val onceSeen: Boolean = false,
    val onScheduleDelete: ((LocalMessage) -> Unit)? = null,
    /** Voice notes: playback speed chip and drag-to-seek (only while this clip plays). */
    val speed: Float = 1f,
    val onCycleSpeed: (() -> Unit)? = null,
    val onSeek: ((Float) -> Unit)? = null,
    /** Reactions beyond the six quick ones. */
    val onMoreEmoji: ((LocalMessage) -> Unit)? = null,
    /** My live location share: a stop button on the bubble. */
    val onLiveStop: (() -> Unit)? = null,
    /** 1.6: a tap on a location bubble opens the in-app map; null keeps the external-map behaviour. */
    val onLocationClick: ((LocalMessage) -> Unit)? = null,
    /** 1.6: a tap on a file bubble opens the file page (needs the message for sender / time); null = open with another app. */
    val onOpenFile: ((LocalMessage) -> Unit)? = null,
    /** 1.6: map tiles for the location thumbnail; null draws the placeholder. */
    val tileUrl: ((Int, Int, Int) -> String)? = null,
    /** 1.7: "gcj02" when the tiles are Amap. */
    val tileDatum: String = "wgs84",
    /** 1.7: 转发 / 多选 / 撤回 / 双击大字. */
    val onForward: ((LocalMessage) -> Unit)? = null,
    val onSelect: ((LocalMessage) -> Unit)? = null,
    val onRecall: ((LocalMessage) -> Unit)? = null,
    val onBigText: ((LocalMessage) -> Unit)? = null,
    /** Multi-select mode: null = off; otherwise whether this bubble is picked. */
    val selected: Boolean? = null,
    /** Unheard voice note (red dot). */
    val voiceUnread: Boolean = false,
    /** 1.8: remind me about this message later (local notification). */
    val onRemind: ((LocalMessage) -> Unit)? = null,
)

/** One message bubble with all its chrome: quote, media, status, reactions, long-press menu, swipe-to-reply. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    m: LocalMessage,
    mine: Boolean,
    me: Long,
    read: Boolean,
    peerName: String,
    botName: String = "助手",
    /** Small author line above a human's bubble (the assistant page shows who asked). */
    label: String? = null,
    /** Show the "@assistant" tag on questions; off inside the assistant page where it is implied. */
    showBotTag: Boolean = true,
    reactions: List<Reaction>,
    uploadProgress: Float?,
    playing: Boolean,
    playProgress: Float,
    mediaUrl: (String) -> String,
    onImageClick: (LocalMessage) -> Unit,
    onVideoClick: (MediaInfo) -> Unit,
    onFileClick: (MediaInfo) -> Unit,
    onFileDownload: (MediaInfo) -> Unit,
    onVoiceClick: (LocalMessage) -> Unit,
    quoteText: (ReplyInfo) -> String,
    onEdit: (LocalMessage) -> Unit,
    onQuoteClick: (String) -> Unit,
    onReply: (LocalMessage) -> Unit,
    onDelete: (LocalMessage) -> Unit,
    onSave: (MediaInfo) -> Unit,
    onShare: (MediaInfo) -> Unit,
    onRetry: (LocalMessage) -> Unit,
    onCancelUpload: (LocalMessage) -> Unit,
    onReact: (LocalMessage, String) -> Unit,
    onRedial: (String?) -> Unit,
    extras: BubbleExtras = BubbleExtras(),
) {
    val palette = LocalChatPalette.current
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val textColor = if (mine) palette.onBubbleMine else if (m.fromBot) MaterialTheme.colorScheme.onTertiaryContainer else palette.onBubblePeer
    val r = palette.bubbleRadius
    val tail = if (r > 10.dp) 5.dp else 4.dp
    val shape = RoundedCornerShape(
        topStart = r, topEnd = r,
        bottomEnd = if (mine) tail else r,
        bottomStart = if (mine) r else tail,
    )
    val media = m.media
    val hasMedia = media != null && media.id.isNotEmpty()
    val isPhoto = (m.kind == "image" || m.kind == "album") && hasMedia
    val isVideo = m.kind == "video"
    val isSticker = m.kind == "sticker"
    val isCard = m.kind == "card"
    val isLocation = m.kind == "location"
    val sticker = if (isSticker) StickerRef.parse(m.text) else null
    val tight = isPhoto || (isVideo && hasMedia) || isLocation
    val uploading = uploadProgress != null && m.status == LocalMessage.PENDING && !hasMedia
    // View-once from the peer: hide the picture behind a tile until tapped; after that it is gone.
    val onceLocked = m.once && !mine && (isPhoto || (isVideo && hasMedia))
    val onceBurnt = onceLocked && extras.onceSeen

    // Swipe right (peer) / left (mine) past 56 dp quotes the message.
    var drag by remember { mutableFloatStateOf(0f) }
    val shown by animateFloatAsState(drag, label = "swipe")
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val openMenu: () -> Unit = {
        if (extras.selected != null) extras.onSelect?.invoke(m) else {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = true
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(m.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(drag) >= threshold && m.kind != "call") {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReply(m)
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, dx ->
                    val next = (drag + dx).coerceIn(-threshold * 1.4f, threshold * 1.4f)
                    if ((next > 0 && !mine) || (next < 0 && mine) || next == 0f) {
                        drag = next
                        change.consume()
                    }
                }
            }
            .offset { IntOffset(shown.roundToInt(), 0) },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        extras.selected?.let { sel ->
            SelectDot(sel) { extras.onSelect?.invoke(m) }
            if (mine) Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(8.dp))
        }
        if (!mine && shown > 8f) {
            Icon(painterResource(R.drawable.ic_reply), contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = (shown / threshold).coerceIn(0f, 1f)), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box {
            val base = Modifier
                .widthIn(max = 300.dp)
                .shadow(if (palette.bubbleShadow && !isSticker) 1.dp else 0.dp, shape, clip = false)
                .clip(shape)
            val bg = when {
                isSticker -> base
                mine -> base.background(palette.bubbleMine)
                m.fromBot -> base.background(MaterialTheme.colorScheme.tertiaryContainer)
                else -> base.background(palette.bubblePeer)
            }
            Box(
                bg.combinedClickable(
                    onClick = {
                        when {
                            extras.selected != null -> extras.onSelect?.invoke(m)
                            m.status == LocalMessage.FAILED -> onRetry(m)
                            m.kind == "call" -> onRedial(m.text)
                        }
                    },
                    onDoubleClick = if (m.kind == "text" && extras.onBigText != null && extras.selected == null) ({ extras.onBigText.invoke(m) }) else null,
                    onLongClick = openMenu,
                )
            ) {
                CompositionLocalProvider(LocalContentColor provides if (isSticker) palette.onBubblePeer.copy(alpha = 0.7f) else textColor) {
                Column(Modifier.padding(horizontal = if (tight || isSticker) 4.dp else 12.dp, vertical = if (tight || isSticker) 4.dp else 8.dp)) {
                    if (m.fromBot && !isSticker) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (tight) 6.dp else 0.dp, bottom = 3.dp)) {
                            Icon(painterResource(R.drawable.ic_bot), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isCard) "$botName · 播报" else botName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (label != null && !isSticker) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = if (tight) 6.dp else 0.dp, bottom = 2.dp))
                    } else if (m.toBot && showBotTag && !isSticker) {
                        Text("@$botName", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = if (tight) 6.dp else 0.dp, bottom = 2.dp))
                    }
                    m.reply?.let {
                        Box(Modifier.padding(horizontal = if (tight) 6.dp else 0.dp, vertical = if (tight) 4.dp else 0.dp)) {
                            Quote(it, mine, peerName, quoteText(it), onQuoteClick)
                        }
                    }
                    when {
                        isSticker -> {
                            if (sticker != null) {
                                StickerImage(sticker, extras.serverUrl, onClick = { }, onLongClick = openMenu)
                            } else {
                                Text("[表情]", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        onceBurnt -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                            Text("🔥", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isVideo) "视频已焚毁" else "图片已焚毁", style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = 0.75f))
                        }
                        onceLocked -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(textColor.copy(alpha = 0.1f))
                                .combinedClickable(onClick = { if (isVideo && media != null) onVideoClick(media) else onImageClick(m) }, onLongClick = openMenu)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text("🔥", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(if (isVideo) "阅后即焚视频" else "阅后即焚图片", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("点一下查看，打开后 10 秒双方删除", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                            }
                        }
                        isPhoto && media != null -> {
                            val ratio = ratioOf(media)
                            AsyncImage(
                                model = mediaUrl(if (media.mime == "image/gif") media.id else media.thumbId ?: media.id),
                                contentDescription = "图片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(240.dp)
                                    .aspectRatio(ratio)
                                    .clip(RoundedCornerShape(15.dp))
                                    .combinedClickable(onClick = { if (m.status == LocalMessage.FAILED) onRetry(m) else onImageClick(m) }, onLongClick = openMenu),
                            )
                            if (!m.text.isNullOrBlank()) LinkedText(m.text, MaterialTheme.typography.bodyLarge)
                        }
                        isVideo && media != null && hasMedia -> {
                            Box(
                                Modifier
                                    .width(240.dp)
                                    .aspectRatio(ratioOf(media))
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(Color.Black)
                                    .combinedClickable(onClick = { onVideoClick(media) }, onLongClick = openMenu),
                                contentAlignment = Alignment.Center,
                            ) {
                                media.thumbId?.let {
                                    AsyncImage(model = mediaUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(ratioOf(media)))
                                }
                                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(34.dp))
                                }
                                media.durationMs?.let {
                                    Text(
                                        fmtClip(it), color = Color.White, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        uploading || ((m.kind == "image" || m.kind == "album" || isVideo || m.kind == "audio") && !hasMedia) -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (m.status != LocalMessage.FAILED) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = textColor)
                                Spacer(Modifier.width(8.dp))
                            }
                            val what = when (m.kind) { "video" -> "视频"; "audio" -> "语音"; else -> "图片" }
                            Column {
                                Text(
                                    if (m.status == LocalMessage.FAILED) "$what 发送失败，点此重试" else "正在发送$what…",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                uploadProgress?.let { p ->
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(progress = { p }, modifier = Modifier.width(160.dp).height(3.dp), color = textColor, trackColor = textColor.copy(alpha = 0.25f))
                                }
                            }
                            if (m.status != LocalMessage.FAILED) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(18.dp).clickable { onCancelUpload(m) })
                            }
                        }
                        m.kind == "audio" && media != null -> Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.width((130 + 90 * minOf(media.durationMs ?: 0, 60_000) / 60_000f).dp).combinedClickable(onClick = { onVoiceClick(m) }, onLongClick = openMenu).padding(vertical = 2.dp),
                            ) {
                                if (extras.voiceUnread) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE53950))); Spacer(Modifier.width(6.dp)) }
                                Box(Modifier.size(36.dp).clip(CircleShape).background(textColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    if (playing) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(textColor))
                                    else Icon(Icons.Default.PlayArrow, contentDescription = "播放语音", modifier = Modifier.size(24.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    // Drag anywhere on the bar to seek while this clip plays.
                                    Box(
                                        Modifier.fillMaxWidth().height(14.dp).then(
                                            if (playing && extras.onSeek != null) Modifier.pointerInput(m.id) {
                                                detectHorizontalDragGestures { change, _ ->
                                                    change.consume()
                                                    extras.onSeek.invoke((change.position.x / size.width).coerceIn(0f, 1f))
                                                }
                                            } else Modifier,
                                        ),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        LinearProgressIndicator(progress = { if (playing) playProgress else 0f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = textColor, trackColor = textColor.copy(alpha = 0.25f))
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(fmtClip(media.durationMs ?: 0), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.8f))
                                        if (playing && extras.onCycleSpeed != null) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "${extras.speed}x".replace(".0x", "x"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = textColor,
                                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(textColor.copy(alpha = 0.15f)).clickable { extras.onCycleSpeed.invoke() }.padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            when {
                                extras.transcribing -> Text("正在转文字…", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
                                extras.transcript != null -> Text(extras.transcript, style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = 0.9f), modifier = Modifier.widthIn(max = 220.dp).padding(top = 4.dp))
                            }
                        }
                        m.kind == "file" && media != null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    onClick = { if (m.status == LocalMessage.FAILED || media.id.isEmpty()) onRetry(m) else extras.onOpenFile?.invoke(m) ?: onFileClick(media) },
                                    onLongClick = openMenu,
                                )
                                .padding(vertical = 4.dp),
                        ) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(textColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                if (uploadProgress != null) CircularProgressIndicator(progress = { uploadProgress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = textColor, trackColor = textColor.copy(alpha = 0.25f))
                                else Icon(painterResource(R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.widthIn(max = 190.dp)) {
                                Text(media.name ?: "文件", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (uploadProgress != null) "${(uploadProgress * 100).roundToInt()}% · ${fmtSize(media.size)}" else fmtSize(media.size),
                                    style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.65f),
                                )
                            }
                            if (!mine && uploadProgress == null && media.id.isNotEmpty() && !m.once) {
                                val savedId = ink.jvm.chatter.util.SavedMedia.isSaved(ctx, media.id)
                                val saveProgress = ink.jvm.chatter.util.SavedMedia.progress[media.id]
                                Spacer(Modifier.width(8.dp))
                                when {
                                    saveProgress != null -> CircularProgressIndicator(progress = { saveProgress }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = textColor, trackColor = textColor.copy(alpha = 0.25f))
                                    savedId -> Icon(painterResource(R.drawable.ic_check), contentDescription = "已保存", tint = textColor.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                                    else -> Icon(painterResource(R.drawable.ic_download), contentDescription = "保存到手机", tint = textColor, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onFileDownload(media) })
                                }
                            }
                            if (uploadProgress != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(18.dp).clickable { onCancelUpload(m) })
                            }
                        }
                        m.kind == "call" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(textColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (m.text.orEmpty().startsWith("video:")) painterResource(R.drawable.ic_videocam) else rememberVectorPainter(Icons.Default.Call),
                                    contentDescription = null, modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(callLabel(m.text), style = MaterialTheme.typography.bodyLarge)
                        }
                        isCard -> {
                            val body = m.text ?: ""
                            val title = markdownFirstLine(body)
                            val rest = body.lineSequence().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (rest.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                MarkdownText(rest, MaterialTheme.typography.bodyMedium)
                            }
                        }
                        m.kind == "location" -> {
                            val decoded = remember(m.text) { ink.jvm.chatter.util.Locator.decode(m.text) }
                            if (decoded != null) {
                                LocationBubble(decoded.first, decoded.second, mine, tileUrl = extras.tileUrl, datum = extras.tileDatum, onClick = extras.onLocationClick?.let { cb -> { cb(m) } }, onLongClick = openMenu)
                                if (decoded.second && mine && extras.onLiveStop != null) {
                                    Text(
                                        "停止分享", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = textColor,
                                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(textColor.copy(alpha = 0.15f)).clickable { extras.onLiveStop.invoke() }.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            } else Text("[位置]", style = MaterialTheme.typography.bodyLarge)
                        }
                        m.fromBot -> MarkdownText(m.text ?: "", MaterialTheme.typography.bodyLarge)
                        else -> {
                            LinkedText(m.text ?: "", MaterialTheme.typography.bodyLarge)
                            val http = extras.linkHttp
                            val url = remember(m.text) { if (http != null) LinkPreviews.firstUrl(m.text ?: "") else null }
                            if (http != null && url != null) {
                                Spacer(Modifier.height(6.dp))
                                LinkCard(url, http)
                            }
                        }
                    }
                    if (m.status == LocalMessage.FAILED) {
                        Text("发送失败，点击重试", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD54F), modifier = Modifier.padding(top = 2.dp))
                    }
                    Row(
                        Modifier.align(Alignment.End).padding(top = 3.dp, end = if (tight || isSticker) 6.dp else 0.dp, bottom = if (tight || isSticker) 2.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val dim = LocalContentColor.current.copy(alpha = 0.7f)
                        if (m.editedAt != null) Text("已编辑 · ", style = MaterialTheme.typography.labelSmall, color = dim)
                        if (m.once && mine) Text("🔥 ", style = MaterialTheme.typography.labelSmall, color = dim)
                        if (m.expiresAt != null) {
                            Icon(painterResource(R.drawable.ic_pending), contentDescription = "定时销毁", tint = dim, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(fmtTime(m.ts), style = MaterialTheme.typography.labelSmall, color = dim)
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            val (icon, tint) = when {
                                m.status == LocalMessage.PENDING -> R.drawable.ic_pending to dim
                                m.status == LocalMessage.FAILED -> R.drawable.ic_error to Color(0xFFFFD54F)
                                read -> R.drawable.ic_done_all to LocalContentColor.current
                                else -> R.drawable.ic_check to dim
                            }
                            Icon(painterResource(icon), contentDescription = if (read) "已读" else null, tint = tint, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                }
            }

            val quick: @Composable () -> Unit = {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    QUICK_EMOJI.forEach { e ->
                        val on = reactions.any { it.from == me && it.emoji == e }
                        Text(e, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.25f) else Color.Transparent).clickable { menu = false; onReact(m, e) }.padding(6.dp))
                    }
                    extras.onMoreEmoji?.let { more ->
                        Text("+", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).clickable { menu = false; more(m) }.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            val copyable = when (m.kind) {
                "text", "card" -> m.text
                "image" -> m.text?.takeIf { it.isNotBlank() }
                "file" -> m.media?.name
                "audio" -> extras.transcript
                "location" -> ink.jvm.chatter.util.Locator.decode(m.text)?.first?.let { f -> (f.address ?: "") + " ${f.lat},${f.lng}" }
                else -> null
            }
            val actions = buildList {
                if (m.status == LocalMessage.FAILED) add(MenuAction("重发", { Icon(Icons.Default.Refresh, null) }) { onRetry(m) })
                if (copyable != null) add(MenuAction("复制", { Icon(painterResource(R.drawable.ic_copy), null) }) {
                    clipboard.setText(AnnotatedString(if (m.fromBot) stripMarkdown(copyable) else copyable))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                })
                extras.onForward?.let { fw -> if (m.kind != "call" && m.kind != "pat" && !m.once && m.status == LocalMessage.SENT) add(MenuAction("转发", { Icon(Icons.AutoMirrored.Filled.Send, null) }) { fw(m) }) }
                if (m.kind != "call") add(MenuAction("引用", { Icon(painterResource(R.drawable.ic_reply), null) }) { onReply(m) })
                extras.onFavorite?.let { fav -> if (m.kind != "call" && m.kind != "pat") add(MenuAction(if (extras.isFavorite) "取消收藏" else "收藏", { Icon(Icons.Default.Star, null) }) { fav(m) }) }
                extras.onSelect?.let { sel -> add(MenuAction("多选", { Icon(painterResource(R.drawable.ic_done_all), null) }) { sel(m) }) }
                extras.onAskBot?.let { ask -> if (!m.fromBot && !m.toBot && m.kind != "call") add(MenuAction("问$botName", { Icon(painterResource(R.drawable.ic_bot), null) }) { ask(m) }) }
                extras.onTranscribe?.let { tr -> if (m.kind == "audio" && hasMedia && extras.transcript == null && !extras.transcribing) add(MenuAction("转文字", { Icon(painterResource(R.drawable.ic_copy), null) }) { tr(m) }) }
                extras.onRemind?.let { remind -> if (m.kind != "call" && m.kind != "pat") add(MenuAction("提醒", { Icon(painterResource(R.drawable.ic_schedule), null) }) { remind(m) }) }
                extras.onSpeak?.let { sp -> if ((m.kind == "text" || m.kind == "card") && !m.text.isNullOrBlank()) add(MenuAction("朗读", { Icon(painterResource(R.drawable.ic_volume), null) }) { sp(m) }) }
                extras.onAddSticker?.let { addSticker -> if (isPhoto && !m.once) add(MenuAction("加表情", { Icon(painterResource(R.drawable.ic_image), null) }) { addSticker(m) }) }
                if (mine && m.kind == "text" && m.status == LocalMessage.SENT) add(MenuAction("编辑", { Icon(Icons.Default.Edit, null) }) { onEdit(m) })
                media?.takeIf { it.id.isNotEmpty() && !m.once }?.let { md ->
                    if (m.kind == "image" || m.kind == "album" || m.kind == "video") add(MenuAction("保存", { Icon(painterResource(R.drawable.ic_download), null) }) { onSave(md) })
                    if (m.kind == "file") add(MenuAction("保存", { Icon(painterResource(R.drawable.ic_download), null) }) { onFileDownload(md) })
                    add(MenuAction("分享", { Icon(Icons.Default.Share, null) }) { onShare(md) })
                }
                extras.onRecall?.let { rc -> if (mine && m.status == LocalMessage.SENT && m.kind != "call" && m.kind != "pat" && System.currentTimeMillis() - m.ts < 120_000) add(MenuAction("撤回", { Icon(painterResource(R.drawable.ic_pending), null) }, danger = true) { rc(m) }) }
                add(MenuAction("删除", { Icon(Icons.Default.Delete, null) }, danger = true) { onDelete(m) })
            }
            MessageMenu(expanded = menu, onDismiss = { menu = false }, reactions = quick, actions = actions)
        }
        if (reactions.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp, start = if (mine) 0.dp else 6.dp, end = if (mine) 6.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                reactions.groupBy { it.emoji }.forEach { (emoji, who) ->
                    val minePick = who.any { it.from == me }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (minePick) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onReact(m, emoji) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(emoji, style = MaterialTheme.typography.labelLarge)
                        if (who.size > 1) Text(" ${who.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
        if (mine && shown < -8f) {
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_reply), contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = (-shown / threshold).coerceIn(0f, 1f)), modifier = Modifier.size(22.dp))
        }
    }
}

private fun ratioOf(media: MediaInfo): Float =
    if (media.width != null && media.height != null && media.height > 0) (media.width.toFloat() / media.height).coerceIn(0.5f, 2f) else 4f / 3f

@Composable
internal fun Quote(r: ReplyInfo, mine: Boolean, peerName: String, text: String, onClick: (String) -> Unit) {
    val palette = LocalChatPalette.current
    val bar = if (mine) palette.quoteBarMine else palette.quoteBarPeer
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LocalContentColor.current.copy(alpha = if (mine) 0.16f else 0.06f))
            .clickable { onClick(r.id) }
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(bar))
        Column(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
            Text(quoteAuthor(r.from, peerName), style = MaterialTheme.typography.labelSmall, color = bar, fontWeight = FontWeight.SemiBold)
            Text(stripMarkdown(text).ifBlank { "原消息" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.85f))
        }
    }
}

/** Centred grey line for pats, timer changes and the like. */
@Composable
internal fun SystemLine(text: String, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).background(LocalChatPalette.current.dayChip)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Resolved lazily against the current user because [ReplyInfo] only carries the author's id. */
private var quoteMe: Long = 0
private var quoteBot: String = "助手"
fun setQuoteSelf(userId: Long, botName: String = quoteBot) { quoteMe = userId; quoteBot = botName }
private fun quoteAuthor(from: Long, peerName: String): String = if (from == quoteMe) "我" else if (from == LocalMessage.BOT_ID) quoteBot else peerName
