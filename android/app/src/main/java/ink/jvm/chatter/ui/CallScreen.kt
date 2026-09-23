package ink.jvm.chatter.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.R
import ink.jvm.chatter.call.AudioDevice
import ink.jvm.chatter.call.AudioRoute
import ink.jvm.chatter.call.CallCaptions
import ink.jvm.chatter.call.CallManager
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Full-screen call UI; shrinks to a compact layout when the activity is in picture-in-picture. */
@Composable
fun CallScreen(calls: CallManager, peerName: String, onCollapse: () -> Boolean = { false }) {
    val ctx = LocalContext.current
    val palette = LocalChatPalette.current
    val state by calls.state.collectAsStateWithLifecycle()
    val withBot by calls.withBot.collectAsStateWithLifecycle()
    val captionBoard by calls.captions.collectAsStateWithLifecycle()
    val app = ctx.applicationContext as ChatterApp
    var spokenCaption by remember { mutableStateOf("") }
    LaunchedEffect(withBot, captionBoard.phase, captionBoard.lines) {
        if (!withBot) return@LaunchedEffect
        if (captionBoard.phase == "listening") app.stopSpeaking()
        val last = captionBoard.lines.lastOrNull { it.who == "assistant" } ?: return@LaunchedEffect
        if (captionBoard.phase == "speaking" && last.text.isNotBlank() && last.text != spokenCaption) {
            spokenCaption = last.text
            app.speak(last.text)
        }
    }
    DisposableEffect(withBot) {
        onDispose { if (withBot) app.stopSpeaking() }
    }
    val muted by calls.muted.collectAsStateWithLifecycle()
    val devices by calls.audioDevices.collectAsStateWithLifecycle()
    val device by calls.audioDevice.collectAsStateWithLifecycle()
    val local by calls.localVideoTrack.collectAsStateWithLifecycle()
    val remote by calls.remoteVideoTrack.collectAsStateWithLifecycle()
    val inPip by MainActivity.pip
    val cameraOff by calls.cameraOff.collectAsStateWithLifecycle()
    val peerCameraOff by calls.peerCameraOff.collectAsStateWithLifecycle()
    val reconnecting by calls.reconnecting.collectAsStateWithLifecycle()
    val stats by calls.stats.collectAsStateWithLifecycle()
    val boardOpen by calls.whiteboard.open.collectAsStateWithLifecycle()
    val sharing by calls.screenSharing.collectAsStateWithLifecycle()
    val peerSharing by calls.peerScreenSharing.collectAsStateWithLifecycle()
    val e2e = calls.e2eOn
    val verified = calls.e2eVerified
    var showStats by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var swapped by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    // Emoji reactions floating up from the bottom; each lives ~2.5 s.
    val floats = remember { androidx.compose.runtime.mutableStateListOf<CallManager.Float>() }
    LaunchedEffect(Unit) {
        calls.emojiFloats.collect { f ->
            floats += f
            if (floats.size > 12) floats.removeAt(0)
        }
    }
    LaunchedEffect(floats.size) {
        if (floats.isNotEmpty()) { delay(2600); if (floats.isNotEmpty()) floats.removeAt(0) }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCallAudioPerms(ctx, calls.state.value.let { it is CallManager.State.Incoming && it.video })) calls.accept()
        else Toast.makeText(ctx, "需要麦克风权限才能通话", Toast.LENGTH_LONG).show()
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val s = state
    val isVideo = when (s) {
        is CallManager.State.Outgoing -> s.video
        is CallManager.State.Incoming -> s.video
        is CallManager.State.Active -> s.video
        is CallManager.State.Ended -> s.video
        CallManager.State.Idle -> false
    }
    val ringing = s is CallManager.State.Outgoing || s is CallManager.State.Incoming
    val status = when (s) {
        is CallManager.State.Outgoing -> "正在呼叫…"
        is CallManager.State.Incoming -> if (s.video) "邀请你视频通话" else "邀请你语音通话"
        is CallManager.State.Active -> when {
            reconnecting -> "网络不稳定，正在重连…"
            sharing -> "正在共享你的屏幕 · " + (if (s.connected) fmtDuration(now - s.startedAt) else "连接中…")
            peerSharing -> "对方正在共享屏幕 · " + (if (s.connected) fmtDuration(now - s.startedAt) else "连接中…")
            s.connected -> fmtDuration(now - s.startedAt)
            else -> "连接中…"
        }
        is CallManager.State.Ended -> when (s.reason) {
            "declined" -> if (withBot) "助手没有接" else "对方拒绝了"
            "busy" -> if (withBot) "助手正在通话" else "对方正忙"
            "offline" -> if (withBot) "助手不在线" else "对方不在线"
            "timeout" -> if (withBot) "助手没有应答" else "无人接听"
            "failed" -> "连接失败"
            "unavailable" -> "助手还不能接语音，先用按住说话"
            "voice_only" -> "助手只接通语音"
            else -> "通话结束"
        }
        CallManager.State.Idle -> ""
    }

    if (inPip) {
        PipCall(peerName, status, isVideo, remote, local, calls.eglBase)
        return
    }

    val active = s is CallManager.State.Active
    val bothVideo = isVideo && active && remote != null
    // 1.7: a shared screen (either way) is immersive too: tap the picture to hide / show the controls.
    val immersive = active && remote != null && (isVideo || peerSharing || sharing)
    // Controls auto-hide during a connected video call; any tap on the picture brings them back.
    LaunchedEffect(controls, bothVideo, showDevices) {
        if (controls && immersive && !showDevices) {
            delay(5000)
            controls = false
        }
    }
    LaunchedEffect(immersive) { if (!immersive) controls = true }

    // No remote picture yet (ringing / connecting): our own camera fills the screen, like the phone dialer.
    val bigIsLocal = remote == null || swapped
    val bigTrack = if (bigIsLocal) local else remote
    val smallTrack = if (remote == null) null else if (swapped) remote else local
    val bigHidden = if (bigIsLocal) cameraOff else peerCameraOff
    val smallHidden = if (bigIsLocal) peerCameraOff else cameraOff

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.callBackdrop)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (immersive) controls = !controls
            },
    ) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        if (bigTrack != null && !bigHidden) {
            VideoView(bigTrack, calls.eglBase, mirror = bigIsLocal && !sharing, overlay = false, modifier = Modifier.fillMaxSize(), fit = (peerSharing && !bigIsLocal) || (sharing && bigIsLocal))
        }
        if (bigTrack != null && bigHidden) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(if (bigIsLocal) "我" else peerName, size = 112.dp, textStyle = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(if (bigIsLocal) "已关闭摄像头" else "对方已关闭摄像头", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (bothVideo && smallTrack != null && (controls || !(peerSharing || sharing))) {
            // Draggable thumbnail that snaps to the nearest corner; tap to swap with the big picture.
            val pipW = with(density) { 108.dp.roundToPx() }
            val pipH = with(density) { 160.dp.roundToPx() }
            val margin = with(density) { 12.dp.roundToPx() }
            val topInset = with(density) { 48.dp.roundToPx() }
            val bottomInset = with(density) { 220.dp.roundToPx() }
            var pos by remember { mutableStateOf(IntOffset(maxW - pipW - margin, topInset)) }
            fun snap(p: IntOffset): IntOffset {
                val x = if (p.x + pipW / 2 < maxW / 2) margin else maxW - pipW - margin
                val y = if (p.y + pipH / 2 < maxH / 2) topInset else maxH - pipH - bottomInset
                return IntOffset(x, y)
            }
            Box(
                Modifier
                    .offset { pos }
                    .size(width = 108.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { pos = snap(pos) }) { change, drag ->
                            change.consume()
                            pos = IntOffset(
                                (pos.x + drag.x.roundToInt()).coerceIn(0, maxW - pipW),
                                (pos.y + drag.y.roundToInt()).coerceIn(0, maxH - pipH),
                            )
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { swapped = !swapped },
            ) {
                if (smallHidden) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A1A26)), contentAlignment = Alignment.Center) {
                        Avatar(if (bigIsLocal) peerName else "我", size = 48.dp)
                    }
                } else {
                    VideoView(smallTrack, calls.eglBase, mirror = !bigIsLocal && !sharing, overlay = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (bigTrack != null && controls) {
            Box(
                Modifier.fillMaxWidth().height(180.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            )
            Box(
                Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            )
        }

        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (s is CallManager.State.Outgoing || s is CallManager.State.Active) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable { onCollapse() }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_pip), contentDescription = "收起", tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("收起", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Spacer(Modifier.height(36.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (bigTrack == null) {
                Box(contentAlignment = Alignment.Center) {
                    if (ringing) PulseRings()
                    Avatar(peerName, size = 112.dp, textStyle = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(22.dp))
            }
            Text(peerName, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(status, color = if (reconnecting) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.titleMedium)
            if (withBot && active) {
                val phaseLine = when (captionBoard.phase) {
                    "listening" -> "正在听…"
                    "thinking" -> "正在想…"
                    "speaking" -> "正在说…"
                    "error" -> captionBoard.phaseText.ifBlank { "没听清，再说一次" }
                    else -> null
                }
                if (phaseLine != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(phaseLine, color = if (captionBoard.phase == "error") Color(0xFFFFD54F) else Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape).clickable { showStats = !showStats }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = if (withBot || e2e) Color(0xFF7ED4A5) else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    when {
                        withBot -> "媒体加密 · 和助手通话"
                        !e2e -> "未加密信令"
                        verified -> "端到端加密 · 已核对"
                        else -> "端到端加密"
                    },
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                )
                if (active && stats.bars > 0) {
                    Spacer(Modifier.width(10.dp))
                    SignalBars(stats.bars)
                    if (stats.relayed) Text(" 中转", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (showStats && active) {
                Text("延迟 ${stats.rttMs} ms · 丢包 ${stats.lossPct}% · ↑${stats.upKbps} ↓${stats.downKbps} kbps", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
            if (active && stats.bars in 1..1) {
                Text("网络较差", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
            }
            if (active && device.route != AudioRoute.EARPIECE) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Icon(routeIcon(device.route), contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(device.name, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            val captionRows = if (withBot) CallCaptions.rows(captionBoard) else emptyList()
            if (captionRows.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    captionRows.forEach { line ->
                        val mine = line.who == "user"
                        Text(
                            (if (mine) "我" else peerName) + "  " + line.text,
                            color = Color.White.copy(alpha = if (line.live) 0.7f else 0.95f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start),
                        )
                    }
                }
                if (withBot && captionBoard.phase == "error") {
                    Spacer(Modifier.height(6.dp))
                    Text("也可以挂断后按住说话", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(12.dp))
            }

            when (s) {
                is CallManager.State.Incoming -> Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                    RoundButton(Color(0xFFE53950), painterResource(R.drawable.ic_call_end), "拒绝") { calls.reject() }
                    RoundButton(Color(0xFF34B36F), rememberVectorPainter(Icons.Default.Call), "接听") {
                        val perms = callPermissions(s.video)
                        if (hasCallAudioPerms(ctx, s.video)) calls.accept()
                        else permLauncher.launch(perms)
                    }
                }
                is CallManager.State.Ended -> {
                    if (withBot) RoundButton(Color(0xFF34B36F), rememberVectorPainter(Icons.Default.Call), "重试") { calls.retry() }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (active && !withBot) {
                        // In-call reactions: float up on both screens.
                        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("❤️", "👋", "😘", "😂", "👍", "🎉", "🤣", "😭", "😮", "🙏", "👏", "🔥", "🌹", "💯", "🥰", "😡", "🤔", "😴", "🎉", "🎂", "🍻", "🌙", "⭐", "🍀").distinct().forEach { e ->
                                Text(
                                    e, style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable { calls.sendEmoji(e) }.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        ToggleButton(active = muted, painter = painterResource(R.drawable.ic_mic_off), label = "静音") { calls.setMuted(!muted) }
                        // 听筒/扬声器 only: plain toggle. Headset or Bluetooth present: pick from the sheet.
                        val onlyBuiltIn = devices.none { it.route == AudioRoute.WIRED || it.route == AudioRoute.BLUETOOTH }
                        ToggleButton(
                            active = device.route == AudioRoute.SPEAKER,
                            painter = routeIcon(device.route),
                            label = if (onlyBuiltIn) "扬声器" else device.shortName,
                            onLongClick = { showDevices = true },
                        ) {
                            if (onlyBuiltIn) {
                                calls.setSpeaker(device.route != AudioRoute.SPEAKER)
                            } else {
                                showDevices = true
                            }
                        }
                        if (isVideo && !withBot) ToggleButton(active = cameraOff, painter = painterResource(R.drawable.ic_videocam_off), label = "摄像头") { calls.setCameraOff(!cameraOff) }
                        if (isVideo && !cameraOff && !withBot) ToggleButton(active = false, painter = painterResource(R.drawable.ic_camera_switch), label = "翻转") { calls.switchCamera() }
                        if (active && !withBot) ToggleButton(active = boardOpen, painter = painterResource(R.drawable.ic_draw), label = "一起画") { calls.openWhiteboard(!boardOpen) }
                        if (active && !withBot && Build.VERSION.SDK_INT >= 29) ToggleButton(active = sharing, painter = painterResource(R.drawable.ic_screen_share), label = if (sharing) "停止共享" else "共享屏幕") {
                            val act = ctx as? MainActivity
                            if (sharing) act?.stopScreenShare() else act?.requestScreenShare()
                        }
                        ToggleButton(active = false, painter = painterResource(R.drawable.ic_pip), label = "收起") { onCollapse() }
                    }
                    Spacer(Modifier.height(40.dp))
                    RoundButton(Color(0xFFE53950), painterResource(R.drawable.ic_call_end), "挂断") { calls.hangup() }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        }
        // Floating emoji reactions (mine from the right, the peer's from the left).
        floats.forEach { f -> key(f.id) { FloatingEmoji(f) } }
    }

    if (boardOpen && s is CallManager.State.Active) {
        WhiteboardOverlay(calls.whiteboard, onClose = { calls.openWhiteboard(false) })
    }

    if (showDevices) {
        AudioDeviceSheet(
            devices = devices,
            current = device,
            onPick = { calls.selectAudioDevice(it) },
            onDismiss = { showDevices = false },
        )
    }
}

/** One emoji drifting upward and fading; the peer's rise on the left, mine on the right. */
@Composable
private fun FloatingEmoji(f: CallManager.Float) {
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(f.id) { t.animateTo(1f, tween(2400, easing = LinearEasing)) }
    val wobble = remember(f.id) { ((f.id % 60) - 30).toFloat() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val w = constraints.maxWidth.toFloat()
        Text(
            f.emoji, style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.offset {
                IntOffset(
                    ((if (f.mine) w * 0.72f else w * 0.18f) + wobble * kotlin.math.sin(t.value * 6f)).roundToInt(),
                    (h * 0.78f - t.value * h * 0.6f).roundToInt(),
                )
            }.graphicsLayer { alpha = (1f - t.value).coerceIn(0f, 1f); val sc = 0.8f + 0.5f * t.value; scaleX = sc; scaleY = sc },
        )
    }
}

@Composable
private fun routeIcon(route: AudioRoute): Painter = painterResource(
    when (route) {
        AudioRoute.EARPIECE -> R.drawable.ic_earpiece
        AudioRoute.SPEAKER -> R.drawable.ic_volume
        AudioRoute.WIRED -> R.drawable.ic_headset
        AudioRoute.BLUETOOTH -> R.drawable.ic_bluetooth
    }
)

/** Output picker: 听筒 / 扬声器 / 有线耳机 / 蓝牙 (named), like the dialer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDeviceSheet(
    devices: List<AudioDevice>,
    current: AudioDevice,
    onPick: (AudioRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Text(
            "声音输出",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        devices.forEach { d ->
            val selected = d.route == current.route
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onPick(d.route)
                        onDismiss()
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Icon(
                        routeIcon(d.route),
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.route == AudioRoute.BLUETOOTH || d.route == AudioRoute.WIRED) {
                        Text(d.shortName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (selected) Icon(Icons.Default.Check, contentDescription = "当前", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp).navigationBarsPadding())
    }
}

/**
 * Collapsed call: a small chip floating over whatever screen is showing (WeChat style). Drag it anywhere, it
 * snaps to the nearer side; tap to go back to the call. Touches outside the chip fall through to the screen.
 */
@Composable
fun FloatingCallChip(calls: CallManager, peerName: String, onExpand: () -> Unit) {
    val palette = LocalChatPalette.current
    val density = LocalDensity.current
    val state by calls.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val s = state
    val video = when (s) {
        is CallManager.State.Outgoing -> s.video
        is CallManager.State.Active -> s.video
        else -> false
    }
    val status = when (s) {
        is CallManager.State.Outgoing -> "呼叫中"
        is CallManager.State.Active -> if (s.connected) fmtDuration(now - s.startedAt) else "连接中"
        else -> "通话中"
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val chipW = with(density) { 84.dp.toPx() }
        val chipH = with(density) { 66.dp.toPx() }
        val margin = with(density) { 10.dp.toPx() }
        val top = WindowInsets.statusBars.getTop(density) + margin
        val maxX = (constraints.maxWidth - chipW - margin).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - chipH - margin).coerceAtLeast(0f)
        var pos by remember { mutableStateOf(Offset(maxX, top)) }
        var dragging by remember { mutableStateOf(false) }
        val x by animateFloatAsState(pos.x, label = "chipX")
        val y by animateFloatAsState(pos.y, label = "chipY")
        Column(
            modifier = Modifier
                .offset { IntOffset((if (dragging) pos.x else x).roundToInt(), (if (dragging) pos.y else y).roundToInt()) }
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(palette.accent)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            // settle on the nearer side, like the phone's own call bubble
                            pos = Offset(if (pos.x + chipW / 2 < constraints.maxWidth / 2f) margin else maxX, pos.y.coerceIn(top, maxY))
                        },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        pos = Offset((pos.x + drag.x).coerceIn(margin, maxX), (pos.y + drag.y).coerceIn(top, maxY))
                    }
                }
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (video) painterResource(R.drawable.ic_videocam) else rememberVectorPainter(Icons.Default.Call),
                contentDescription = "返回通话",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(status, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(peerName, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PipCall(
    peerName: String,
    status: String,
    video: Boolean,
    remote: VideoTrack?,
    local: VideoTrack?,
    egl: EglBase,
) {
    val palette = LocalChatPalette.current
    Box(Modifier.fillMaxSize().background(palette.callBackdrop)) {
        val track = remote ?: local
        if (video && track != null) {
            VideoView(track, egl, mirror = remote == null, overlay = false, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!video) {
                Avatar(peerName, size = 40.dp, textStyle = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                peerName,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Four bars, lit from the left according to link quality. */
@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= bars) (if (bars <= 1) Color(0xFFFFD54F) else Color(0xFF7ED4A5)) else Color.White.copy(alpha = 0.25f)))
        }
    }
}

/** Two expanding rings behind the avatar while a call is ringing. */
@Composable
private fun PulseRings() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val t1 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "ring1",
    )
    val t2 = (t1 + 0.5f) % 1f
    for (t in listOf(t1, t2)) {
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer {
                    val sc = 1f + 0.75f * t
                    scaleX = sc
                    scaleY = sc
                    alpha = (1f - t) * 0.45f
                }
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
private fun RoundButton(color: Color, painter: Painter, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(74.dp).clip(CircleShape).background(color)) {
            Icon(painter, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToggleButton(
    active: Boolean,
    painter: Painter,
    label: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else Color.White.copy(alpha = 0.16f))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Icon(painter, contentDescription = label, tint = if (active) Color(0xFF2B1A22) else Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun VideoView(track: VideoTrack, egl: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier, fit: Boolean = false) {
    val ctx = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(ctx).apply {
            init(egl.eglBaseContext, null)
            setMirror(mirror)
            setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            if (overlay) setZOrderMediaOverlay(true)
        }
    }
    LaunchedEffect(mirror) { renderer.setMirror(mirror) }
    LaunchedEffect(fit) { renderer.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL) }
    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose { runCatching { track.removeSink(renderer) } }
    }
    DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}