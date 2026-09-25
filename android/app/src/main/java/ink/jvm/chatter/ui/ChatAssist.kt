package ink.jvm.chatter.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.media.LocalSummary

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
    var body by remember(request) { mutableStateOf<String?>(null) }
    var suggestions by remember(request) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        val req = request ?: return@LaunchedEffect
        if (!LocalSummary.ready(ctx)) {
            Toast.makeText(ctx, "先下载纪要模型", Toast.LENGTH_SHORT).show()
            onClose()
            return@LaunchedEffect
        }
        val mine = ++token
        val result = runCatching {
            when (req) {
                is AssistRequest.Summary -> LocalSummary.summarizeChat(ctx, recentDialog(repo, req.limit))
                AssistRequest.Polish -> LocalSummary.polish(ctx, draft.trim())
                AssistRequest.Suggest -> null
            }
        }
        val replies = if (req is AssistRequest.Suggest) runCatching { LocalSummary.suggest(ctx, recentDialog(repo, 30)) } else null
        if (mine != token) return@LaunchedEffect
        when (req) {
            is AssistRequest.Suggest -> replies!!.onSuccess { suggestions = it }.onFailure { error = it.message ?: "整理失败" }
            else -> result.onSuccess { body = it as String }.onFailure { error = it.message ?: "整理失败" }
        }
    }
    if (request == null) return
    val running = error == null && body == null && suggestions.isEmpty()
    AlertDialog(
        onDismissRequest = {
            token++
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
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        error != null -> error ?: "整理失败"
                        running -> "正在这台手机上整理，不会上传。"
                        request is AssistRequest.Suggest -> "点一条放进输入框。"
                        else -> body ?: ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (request is AssistRequest.Suggest && suggestions.isNotEmpty()) {
                    suggestions.forEach { line ->
                        TextButton(onClick = { onDraft(line); token++; onClose() }, modifier = Modifier.fillMaxWidth()) {
                            Text(line)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (request) {
                AssistRequest.Polish -> TextButton(
                    enabled = !body.isNullOrBlank(),
                    onClick = { onDraft(body!!.trim()); token++; onClose() },
                ) { Text("用这版") }
                is AssistRequest.Summary -> TextButton(enabled = !running && error == null, onClick = { token++; onClose() }) { Text("好") }
                AssistRequest.Suggest -> TextButton(onClick = { token++; onClose() }) { Text("关闭") }
            }
        },
        dismissButton = {
            if (request is AssistRequest.Polish) {
                TextButton(onClick = { token++; onClose() }) { Text("取消") }
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
