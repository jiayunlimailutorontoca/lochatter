package ink.jvm.chatter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.media.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Last known keyboard height (px, without the navigation bar) so the sticker / 「+」 panels open at the same height and the list does not jump. */
internal object PanelHeight {
    @Volatile var px: Int = 0
}

/** Height for a bottom panel: the keyboard's when known, otherwise a sensible default. */
@Composable
internal fun panelHeight(): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    val px = PanelHeight.px
    return if (px > 0) with(density) { px.toDp() } else 280.dp
}

/** One tile of the 「+」 panel. [onLongClick] is optional (拍摄 → system camera). */
private class PlusItem(val label: String, val icon: Painter, val onLongClick: (() -> Unit)? = null, val onClick: () -> Unit)

/**
 * WeChat-style composer (1.7): 语音 / 键盘 toggle on the left; in voice mode the field becomes 「按住 说话」
 * (slide up-left to cancel, up-right to turn the clip into text); 表情 and 「+」 on the right, the 「+」 becoming
 * 发送 once there is text. The 「+」 panel is a grid (相册 / 文件 / 位置 / 通话 / 收藏 / 定时 / 听写) that opens at
 * the keyboard's height so the message list stays put. Reply / edit banners and the @assistant chip are unchanged.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoice: (File, Int) -> Unit,
    onNeedMicPermission: () -> Unit,
    busy: Boolean,
    replyTo: LocalMessage?,
    replyAuthor: (Long) -> String,
    onCancelReply: () -> Unit,
    editing: LocalMessage? = null,
    onCancelEdit: () -> Unit = {},
    encrypted: Boolean = false,
    /** Assistant name when the server has one; null hides the @ chip. */
    botName: String? = null,
    toBot: Boolean = false,
    onToggleBot: () -> Unit = {},
    /** Fixed hint text (the assistant page); null derives it from the other flags. */
    placeholder: String? = null,
    /** Sticker panel toggle; null hides the button. */
    onSticker: (() -> Unit)? = null,
    stickerOpen: Boolean = false,
    /** Under the composer and above the keyboard inset. The sticker browser goes here so its search field stays on screen. */
    below: (@Composable () -> Unit)? = null,
    /** "/" command menu (assistant page). */
    onSlash: (() -> Unit)? = null,
    /** Dictation (on-device SenseVoice, or the cloud address from settings). */
    onDictate: (() -> Unit)? = null,
    /** Schedule the typed text. */
    onSchedule: (() -> Unit)? = null,
    /** A line above the composer, e.g. "对方不在线" or "1 条定时消息". */
    banner: (@Composable () -> Unit)? = null,
    // ---- 1.7 ----
    onLocation: (() -> Unit)? = null,
    onCall: ((video: Boolean) -> Unit)? = null,
    onFavorites: (() -> Unit)? = null,
    /** Voice clip → text for 「转文字」 while holding; null hides that target. */
    transcribe: (suspend (File) -> String?)? = null,
    /** Sends text that did not come from the field (a transcribed clip). Falls back to onValueChange + onSend. */
    onSendText: ((String) -> Unit)? = null,
    /** 「+」→ 拍摄. Null hides the tile. Long-press opens the system camera. */
    onCapture: (() -> Unit)? = null,
    onCaptureLong: (() -> Unit)? = null,
) {
    val palette = LocalChatPalette.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sttStatus by ink.jvm.chatter.media.LocalStt.status.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val recorder = remember { VoiceRecorder(ctx) }
    var voiceMode by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var zone by remember { mutableIntStateOf(0) } // 0 send, 1 cancel, 2 to text
    var level by remember { mutableFloatStateOf(0f) }
    var recStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var pendingClip by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var voiceText by remember { mutableStateOf<String?>(null) }
    var transcribing by remember { mutableStateOf(false) }

    // Remember the keyboard height for the 「+」 panel. The message field closes the sticker panel on focus;
    // the sticker search field is allowed to open the keyboard without dismissing the panel.
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(imeBottom) {
        val h = imeBottom - navBottom
        if (h > 200) { PanelHeight.px = h; plusOpen = false }
    }
    LaunchedEffect(recording) {
        while (recording) {
            level = recorder.level()
            now = System.currentTimeMillis()
            if (now - recStart > 60_000) {
                recorder.stop()?.let { (f, d) -> onVoice(f, d) }
                recording = false
            }
            delay(80)
        }
    }
    fun openPlus() {
        keyboard?.hide(); focus.clearFocus()
        if (stickerOpen) onSticker?.invoke()
        plusOpen = true
    }
    fun openStickers() {
        keyboard?.hide(); focus.clearFocus()
        plusOpen = false
        onSticker?.invoke()
    }
    fun startTranscribe(clip: Pair<File, Int>) {
        val tr = transcribe ?: run { onVoice(clip.first, clip.second); return }
        pendingClip = clip; transcribing = true; voiceText = null
        scope.launch {
            val t = runCatching { tr(clip.first) }.getOrNull()?.trim()
            transcribing = false
            voiceText = t ?: ""
            if (t.isNullOrEmpty()) ink.jvm.chatter.media.Speech.lastError?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        }
    }
    fun sendVoiceText(text: String) {
        if (onSendText != null) onSendText(text) else { onValueChange(text); onSend() }
    }

    Column(Modifier.navigationBarsPadding().imePadding()) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            banner?.invoke()
            if (editing != null && !recording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
                        Text("编辑消息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(editing.text ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onCancelEdit) { Icon(Icons.Default.Close, contentDescription = "取消编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (replyTo != null && editing == null && !recording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(palette.quoteBarPeer))
                    Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                        Text("回复 ${replyAuthor(replyTo.from)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(stripMarkdown(ChatRepository.previewOf(replyTo)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onCancelReply) { Icon(Icons.Default.Close, contentDescription = "取消引用", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (recording) RecordingOverlay(zone, level, (now - recStart).toInt(), transcribeAvailable = transcribe != null)
            val clip = pendingClip
            if (clip != null && !recording) {
                // Result of 「转文字」: edit, send as text, or send the original clip.
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (transcribing) {
                        Text(sttStatus ?: "正在转文字…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp))
                    } else {
                        OutlinedTextField(
                            value = voiceText ?: "", onValueChange = { voiceText = it }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5,
                            placeholder = { Text("没识别出来，可以直接发语音") },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { clip.first.delete(); pendingClip = null; voiceText = null }) { Text("取消") }
                        TextButton(onClick = { onVoice(clip.first, clip.second); pendingClip = null; voiceText = null }) { Text("发送语音") }
                        TextButton(enabled = !transcribing && !voiceText.isNullOrBlank(), onClick = {
                            sendVoiceText(voiceText!!.trim()); clip.first.delete(); pendingClip = null; voiceText = null
                        }) { Text("发送文字", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (botName != null && !recording && editing == null) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val fg = if (toBot) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.primary
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (toBot) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onToggleBot)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_bot), contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("@$botName", style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
                    }
                    if (toBot) {
                        Spacer(Modifier.width(8.dp))
                        Text(if (encrypted) "这条发给 $botName，不加密" else "这条发给 $botName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (onSlash != null) {
                    IconButton(onClick = onSlash, enabled = !busy && !recording) {
                        Text("/", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                // 语音 / 键盘 toggle (WeChat's left button).
                IconButton(onClick = {
                    voiceMode = !voiceMode
                    if (voiceMode) { keyboard?.hide(); focus.clearFocus(); plusOpen = false; if (stickerOpen) onSticker?.invoke() }
                }, enabled = !recording && editing == null) {
                    Icon(
                        painterResource(if (voiceMode) R.drawable.ic_keyboard else R.drawable.ic_mic),
                        contentDescription = if (voiceMode) "切换到键盘" else "切换到语音",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (voiceMode && editing == null) {
                    val cancelPx = with(density) { 90.dp.toPx() }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 4.dp)
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (recording) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pointerInput(transcribe != null) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        onNeedMicPermission()
                                        return@awaitEachGesture
                                    }
                                    if (runCatching { recorder.start() }.isFailure) { Toast.makeText(ctx, "录音失败", Toast.LENGTH_SHORT).show(); return@awaitEachGesture }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    recStart = System.currentTimeMillis(); now = recStart; zone = 0; recording = true
                                    val startY = down.position.y
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        ch.consume()
                                        val dy = ch.position.y - startY
                                        val z = if (dy < -cancelPx) { if (ch.position.x < size.width * 0.45f) 1 else if (transcribe != null) 2 else 1 } else 0
                                        if (z != zone) { zone = z; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                        if (!ch.pressed) break
                                    }
                                    if (!recording) return@awaitEachGesture
                                    recording = false
                                    when (zone) {
                                        1 -> recorder.cancel()
                                        2 -> recorder.stop()?.let { startTranscribe(it) } ?: Toast.makeText(ctx, "说话时间太短", Toast.LENGTH_SHORT).show()
                                        else -> recorder.stop()?.let { (f, d) -> onVoice(f, d) } ?: Toast.makeText(ctx, "说话时间太短", Toast.LENGTH_SHORT).show()
                                    }
                                    zone = 0
                                }
                            },
                    ) {
                        Text(
                            when { !recording -> "按住 说话"; zone == 1 -> "松开 取消"; zone == 2 -> "松开 转文字"; else -> "松开 发送" },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = if (zone == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) { plusOpen = false; if (stickerOpen) onSticker?.invoke() } },
                        placeholder = { Text(placeholder ?: if (toBot && botName != null) "问 $botName…" else if (encrypted) "发消息（端到端加密）…" else "发消息…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
                if (onSticker != null) {
                    IconButton(onClick = { if (stickerOpen) { onSticker(); keyboard?.show() } else openStickers() }, enabled = !recording) {
                        Icon(painterResource(if (stickerOpen) R.drawable.ic_keyboard else R.drawable.ic_sticker), contentDescription = "表情", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                val canSend = value.isNotBlank() || editing != null
                if (canSend) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp).height(36.dp).clip(RoundedCornerShape(8.dp)).background(palette.accent)
                            .combinedClickable(
                                enabled = value.isNotBlank(),
                                onClick = onSend,
                                onLongClick = if (onSchedule != null && editing == null) ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSchedule() }) else null,
                            )
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(if (editing != null) "保存" else "发送", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    IconButton(onClick = { if (plusOpen) { plusOpen = false; keyboard?.show() } else openPlus() }, enabled = !recording) {
                        Icon(Icons.Default.Add, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    }
                }
            }
            if (plusOpen && !recording) {
                val items = buildList {
                    add(PlusItem("相册", painterResource(R.drawable.ic_gallery)) { plusOpen = false; onPickImage() })
                    onCapture?.let { shot ->
                        add(PlusItem("拍摄", painterResource(R.drawable.ic_image), onClick = { plusOpen = false; shot() }, onLongClick = onCaptureLong?.let { sys -> ({ plusOpen = false; sys() }) }))
                    }
                    add(PlusItem("文件", painterResource(R.drawable.ic_attach)) { plusOpen = false; onPickFile() })
                    onLocation?.let { add(PlusItem("位置", painterResource(R.drawable.ic_location)) { plusOpen = false; it() }) }
                    onCall?.let { call ->
                        add(PlusItem("语音通话", rememberVectorPainter(Icons.Default.Call)) { plusOpen = false; call(false) })
                        add(PlusItem("视频通话", painterResource(R.drawable.ic_videocam)) { plusOpen = false; call(true) })
                    }
                    onFavorites?.let { add(PlusItem("收藏", rememberVectorPainter(Icons.Default.Star)) { plusOpen = false; it() }) }
                    onSchedule?.let { add(PlusItem("定时发送", painterResource(R.drawable.ic_schedule)) { if (value.isBlank()) Toast.makeText(ctx, "先在输入框里写好要定时发的内容", Toast.LENGTH_SHORT).show() else { plusOpen = false; it() } }) }
                    onDictate?.let { add(PlusItem("说话转文字", painterResource(R.drawable.ic_mic)) { plusOpen = false; it() }) }
                }
                FlowRow(
                    maxItemsInEachRow = 4,
                    modifier = Modifier.fillMaxWidth().height(panelHeight()).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    items.forEach { it ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(76.dp).padding(bottom = 18.dp).combinedClickable(onClick = it.onClick, onLongClick = it.onLongClick),
                        ) {
                            Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                Icon(it.icon, contentDescription = it.label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
                            }
                            Text(it.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
        below?.invoke()
    }
}

/** The floating recording card: a pulsing bubble, the two slide targets (取消 / 转文字) and the hint. */
@Composable
private fun RecordingOverlay(zone: Int, level: Float, elapsedMs: Int, transcribeAvailable: Boolean) {
    val accent = LocalChatPalette.current.accent
    val err = MaterialTheme.colorScheme.error
    val ok = Color(0xFF2FB36A)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(180.dp).height(64.dp).clip(RoundedCornerShape(18.dp))
                .then(when (zone) { 1 -> Modifier.background(err); 2 -> Modifier.background(ok); else -> Modifier.background(accent) }),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(14) { i ->
                    val h = (6 + 22 * level * (0.4f + 0.6f * kotlin.math.sin((i + elapsedMs / 90f) * 0.9f).let { kotlin.math.abs(it) })).dp
                    Box(Modifier.width(3.dp).height(h).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(fmtClip(elapsedMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ZoneTarget("×", "取消", active = zone == 1, color = err)
            if (transcribeAvailable) ZoneTarget("文", "转文字", active = zone == 2, color = ok)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (zone) { 1 -> "松开手指，取消发送"; 2 -> "松开手指，转成文字"; else -> if (transcribeAvailable) "松开发送 · 上滑取消 · 右上滑转文字" else "松开发送 · 上滑取消" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ZoneTarget(symbol: String, label: String, active: Boolean, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (active) 64.dp else 52.dp).clip(CircleShape).background(if (active) color else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Text(symbol, color = if (active) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
