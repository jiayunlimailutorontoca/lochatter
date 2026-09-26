package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.media.LocalSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/** On-device chat tools. Nothing here is uploaded. */
internal sealed class AssistRequest {
    data class Summary(val limit: Int) : AssistRequest()
    data object Polish : AssistRequest()
    data object Suggest : AssistRequest()
}

@Composable
internal fun ChatAssistDialog(
    repo: ChatRepository,
    request: AssistRequest?,
    draft: String,
    onDraft: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var token by remember { mutableIntStateOf(0) }
    var body by remember(request) { mutableStateOf("") }
    var suggestions by remember(request) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var running by remember(request) { mutableStateOf(false) }
    var stopped by remember(request) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val work = remember(request) { mutableStateOf<Job?>(null) }
    val phase by LocalSummary.phase.collectAsStateWithLifecycle()
    LaunchedEffect(body) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(request) {
        val req = request ?: return@LaunchedEffect
        val missing = LocalSummary.unavailable(ctx)
        if (missing != null) {
            Toast.makeText(ctx, missing, Toast.LENGTH_SHORT).show()
            onClose()
            return@LaunchedEffect
        }
        work.value = coroutineContext[Job]
        val alive = AtomicBoolean(true)
        val mine = ++token
        running = true
        try {
            when (req) {
                is AssistRequest.Summary -> body = LocalSummary.summarizeChat(ctx, recentDialog(repo, req.limit)) {
                    if (alive.get() && mine == token) body = it
                }
                AssistRequest.Polish -> body = LocalSummary.polish(ctx, draft.trim()) {
                    if (alive.get() && mine == token) body = it
                }
                AssistRequest.Suggest -> {
                    val lines = LocalSummary.suggest(ctx, recentDialog(repo, 30)) {
                        if (alive.get() && mine == token) body = it
                    }
                    if (mine == token) suggestions = lines
                }
            }
        } catch (e: CancellationException) {
            stopped = true
            if (req is AssistRequest.Suggest && suggestions.isEmpty()) suggestions = LocalSummary.replyLines(body)
        } catch (e: Exception) {
            error = e.message ?: "整理失败"
        } finally {
            alive.set(false)
            running = false
        }
    }
    if (request == null) return
    fun stop() {
        token++
        work.value?.cancel()
    }
    AlertDialog(
        onDismissRequest = {
            stop()
            onClose()
        },
        title = {
            Text(
                when (request) {
                    is AssistRequest.Summary -> "最近 ${request.limit} 条"
                    AssistRequest.Polish -> "润色"
                    AssistRequest.Suggest -> "建议回复"
                },
            )
        },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(scroll)) {
                when {
                    error != null -> Text(error ?: "整理失败", style = MaterialTheme.typography.bodyMedium)
                    request is AssistRequest.Suggest && suggestions.isNotEmpty() -> Text(
                        if (stopped) "已停止。点一条放进输入框。" else "点一条放进输入框。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    body.isNotBlank() -> Text(
                        body + if (stopped) "\n\n已停止。" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(phase ?: "正在整理…", style = MaterialTheme.typography.bodyMedium)
                    }
                    stopped -> Text("已停止。", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("没有整理出内容", style = MaterialTheme.typography.bodyMedium)
                }
                if (request is AssistRequest.Suggest && suggestions.isNotEmpty()) {
                    suggestions.forEach { line ->
                        TextButton(onClick = { onDraft(line); stop(); onClose() }, modifier = Modifier.fillMaxWidth()) {
                            Text(line)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = { stop() }) { Text("停止") }
            } else when (request) {
                AssistRequest.Polish -> TextButton(
                    enabled = body.isNotBlank(),
                    onClick = { onDraft(body.trim()); stop(); onClose() },
                ) { Text("用这版") }
                is AssistRequest.Summary -> TextButton(enabled = error == null, onClick = { stop(); onClose() }) { Text("好") }
                AssistRequest.Suggest -> TextButton(onClick = { stop(); onClose() }) { Text("关闭") }
            }
        },
        dismissButton = {
            if (!running && request is AssistRequest.Polish) {
                TextButton(onClick = { stop(); onClose() }) { Text("取消") }
            }
        },
    )
}

private fun recentDialog(repo: ChatRepository, limit: Int): String {
    val me = repo.me
    val peer = repo.prefs.peerName.ifEmpty { "对方" }
    val msgs = repo.recentMessages(400).filter { usable(it) }.takeLast(limit)
    if (msgs.isEmpty()) return ""
    val sb = StringBuilder()
    for (m in msgs) {
        val who = if (m.from == me) "我" else peer
        val t = m.text!!.replace('\n', ' ').take(200)
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(who).append('：').append(t)
    }
    return if (sb.length > 3500) "（更早的消息已略）\n" + sb.substring(sb.length - 3500) else sb.toString()
}

private fun usable(m: LocalMessage): Boolean =
    m.kind == "text" && !m.toBot && !m.fromBot && !m.text.isNullOrBlank()
