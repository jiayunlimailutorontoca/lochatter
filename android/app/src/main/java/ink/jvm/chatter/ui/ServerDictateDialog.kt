package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.media.LocalStt
import ink.jvm.chatter.media.Speech
import ink.jvm.chatter.media.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val MAX_RECORD_MS = 60_000L
private const val SILENCE_MS = 3_000L
private const val SILENCE_LEVEL = 0.06f

/** Ends a hands-free recording once the speaker has said something and then gone quiet for [SILENCE_MS]. Pure, for tests. */
internal class SilenceDetector(private val silenceMs: Long = SILENCE_MS, private val threshold: Float = SILENCE_LEVEL) {
    private var spokeAt = -1L
    private var quietSince = -1L

    /** Feed one loudness sample (0..1) at [nowMs]; true when the utterance is over. */
    fun feed(level: Float, nowMs: Long): Boolean {
        if (level >= threshold) { spokeAt = nowMs; quietSince = -1; return false }
        if (spokeAt < 0) return false
        if (quietSince < 0) quietSince = nowMs
        return nowMs - quietSince >= silenceMs
    }
}

/**
 * Dictation: record with the app's own recorder, then SenseVoice on the phone (or the cloud address from
 * settings). Show the text for editing, 发送. In 开车模式 it is hands-free: recording stops after three
 * seconds of silence, the text is sent at once, and once the assistant's answer has been read aloud the
 * dialog listens again, until 停止.
 */
@Composable
fun ServerDictateDialog(repo: ChatRepository, app: ChatterApp, driveMode: Boolean, replying: Boolean, onClose: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(ctx) }
    var phase by remember { mutableIntStateOf(0) } // 0 idle, 1 recording, 2 transcribing, 3 text ready, 4 waiting for the reply (drive mode)
    var level by remember { mutableFloatStateOf(0f) }
    var seconds by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    var sawReply by remember { mutableStateOf(false) }
    val speaking by app.speaking.collectAsStateWithLifecycle()
    val modelStatus by LocalStt.status.collectAsStateWithLifecycle()

    fun transcribe(f: File) {
        phase = 2; error = null
        scope.launch {
            val said = runCatching { Speech.transcribe(ctx, repo, f, "zh") }.getOrNull()?.trim()
            f.delete()
            when {
                !said.isNullOrEmpty() && driveMode -> { onSend(said); text = ""; sawReply = false; phase = 4 }
                !said.isNullOrEmpty() -> { text = said; phase = 3 }
                else -> { error = Speech.lastError ?: "没有听到内容"; phase = 0 }
            }
        }
    }
    fun stopAndTranscribe() {
        val clip = recorder.stop()
        if (clip == null) { error = "太短了，再说一次"; phase = 0; return }
        pending = clip.first
        transcribe(clip.first)
    }
    fun start() {
        error = null; text = ""
        if (runCatching { recorder.start() }.isFailure) { error = "录音失败，检查麦克风权限"; phase = 0; return }
        phase = 1; seconds = 0
    }
    // Recording loop: level meter, timer, silence detection (hands-free), hard cap.
    LaunchedEffect(phase) {
        if (phase != 1) return@LaunchedEffect
        val det = SilenceDetector()
        val started = System.currentTimeMillis()
        while (isActive && phase == 1) {
            delay(200)
            val now = System.currentTimeMillis()
            level = recorder.level()
            seconds = ((now - started) / 1000).toInt()
            if (now - started >= MAX_RECORD_MS || (driveMode && det.feed(level, now))) { stopAndTranscribe(); break }
        }
    }
    // Hands-free: after the answer has been read aloud, listen again.
    LaunchedEffect(phase, replying, speaking) {
        if (phase != 4) return@LaunchedEffect
        if (replying) { sawReply = true; return@LaunchedEffect }
        if (!sawReply || speaking) return@LaunchedEffect
        delay(1_200)
        if (phase == 4) start()
    }
    LaunchedEffect(Unit) { start() }
    DisposableEffect(Unit) { onDispose { recorder.cancel(); pending?.delete() } }

    AlertDialog(
        onDismissRequest = { recorder.cancel(); onClose() },
        title = { Text(when (phase) { 1 -> "正在录音 ${seconds}s"; 2 -> "正在转文字…"; 3 -> "说话转文字"; 4 -> if (replying) "等它回复…" else if (speaking) "正在朗读…" else "等待中"; else -> "说话转文字" }) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (phase) {
                    1 -> {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                            Box(Modifier.size((56 + 40 * level).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
                            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { stopAndTranscribe() }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_stop), contentDescription = "停止", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (driveMode) "说完停顿 3 秒自动发送" else "说完点圆钮，最长 60 秒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(modelStatus ?: if (Speech.mode(repo) == Speech.Mode.CLOUD) "云端识别中" else "本机识别中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    3 -> OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, label = { Text("识别结果，可修改") })
                    4 -> Text(if (replying) "它正在回复，读完后自动继续听" else if (speaking) "正在朗读回复" else "马上继续听…", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    else -> Text(error ?: "点「再说一次」开始", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            when (phase) {
                1 -> TextButton(onClick = { stopAndTranscribe() }) { Text(if (driveMode) "发送" else "完成") }
                3 -> TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()); if (driveMode) { text = ""; sawReply = false; phase = 4 } else onClose() }) { Text("发送") }
                4 -> TextButton(onClick = { start() }) { Text("现在说") }
                2 -> {}
                else -> TextButton(onClick = { start() }) { Text("再说一次") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                recorder.cancel()
                if (phase == 3) { phase = 0; text = "" } else onClose()
            }) { Text(if (phase == 3) "重录" else if (phase == 4) "停止" else "取消") }
        },
    )
}
