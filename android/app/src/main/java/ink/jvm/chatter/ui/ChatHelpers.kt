package ink.jvm.chatter.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.service.WatchdogReceiver
import ink.jvm.chatter.util.BackgroundSettings
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Banner text above the chat, or null when everything looks fine. */
internal fun bgWarning(ctx: Context, repo: ChatRepository): String? {
    val kills = repo.prefs.killsLast24h()
    return when {
        !BackgroundSettings.isBatteryWhitelisted(ctx) -> "后台可能被系统杀掉，收不到消息和来电"
        !BackgroundSettings.notificationsEnabled(ctx) -> "通知被关闭了，新消息不会提醒"
        kills >= 2 -> "过去 24 小时进程被系统重启了 $kills 次，后台设置可能没放开"
        else -> null
    }
}

@Composable
internal fun BackgroundDialog(repo: ChatRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var whitelisted by remember { mutableStateOf(BackgroundSettings.isBatteryWhitelisted(ctx)) }
    var exact by remember { mutableStateOf(WatchdogReceiver.exactAllowed(ctx)) }
    var notif by remember { mutableStateOf(BackgroundSettings.notificationsEnabled(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                whitelisted = BackgroundSettings.isBatteryWhitelisted(ctx)
                exact = WatchdogReceiver.exactAllowed(ctx)
                notif = BackgroundSettings.notificationsEnabled(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val kills = repo.prefs.killsLast24h()
    val lastConn = repo.prefs.lastConnectedAt

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("让消息在后台也能送达") },
        text = {
            Column {
                Text(
                    "这台手机是 ${Build.MANUFACTURER} ${Build.MODEL}。系统会杀掉后台应用，杀掉后就收不到消息和来电，请做这几步：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                BackgroundSettings.steps().forEachIndexed { i, s ->
                    Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                CheckRow("电池优化不受限制", whitelisted) { BackgroundSettings.requestIgnoreBattery(ctx) }
                CheckRow("允许精确闹钟（每 5 分钟自检）", exact) { BackgroundSettings.requestExactAlarm(ctx) }
                CheckRow("通知已开启", notif) { BackgroundSettings.openNotificationSettings(ctx) }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("过去 24 小时进程被系统重启 $kills 次")
                        if (kills >= 2) append("，说明后台仍会被杀")
                        if (lastConn > 0) append("；上次在线 ${fmtTime(lastConn)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (kills >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { BackgroundSettings.openAutostart(ctx) }) { Text("打开自启动设置") } },
        dismissButton = { TextButton(onClick = onClose) { Text("知道了") } },
    )
}

@Composable
private fun CheckRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Icon(
            painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_error),
            contentDescription = null,
            tint = if (ok) LocalChatPalette.current.online else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("去开启") }
    }
}

/** Saves a file to Downloads/lochatter through the app (so encrypted blobs are decrypted and keep their real name). */
internal fun startDownload(ctx: Context, repo: ChatRepository, m: MediaInfo) {
    val saved = ink.jvm.chatter.util.SavedMedia
    if (saved.progress.containsKey(m.id)) return
    saved.progress[m.id] = 0f
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val msg = runCatching { ink.jvm.chatter.util.MediaSaver.saveToDownloads(ctx, repo, m) { p -> saved.progress[m.id] = p } }.getOrElse { "下载失败：${it.message}" }
        saved.progress.remove(m.id)
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}

internal fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** "<voice|video>:<answered|missed|declined|cancelled>:<seconds>" → human text. */
internal fun callLabel(text: String?): String {
    val parts = text.orEmpty().split(':')
    val kind = if (parts.getOrNull(0) == "video") "视频通话" else "语音通话"
    val secs = parts.getOrNull(2)?.toLongOrNull() ?: 0L
    return when (parts.getOrNull(1)) {
        "answered" -> "$kind ${"%d:%02d".format(secs / 60, secs % 60)}"
        "declined" -> "$kind 已拒绝"
        "cancelled" -> "$kind 已取消"
        else -> "未接$kind"
    }
}

internal fun fmtClip(ms: Int): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

internal fun callPermissions(video: Boolean): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (video) add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

internal fun hasCallAudioPerms(ctx: Context, video: Boolean): Boolean {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
    if (video && ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
    return true
}

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val dayYearFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

internal fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

fun fmtTime(ts: Long): String {
    val zdt = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    return if (zdt.toLocalDate() == LocalDate.now()) zdt.format(timeFmt) else zdt.format(dateTimeFmt)
}

internal fun fmtDay(ts: Long): String {
    val d = dayOf(ts)
    val today = LocalDate.now()
    return when {
        d == today -> "今天"
        d == today.minusDays(1) -> "昨天"
        d.year == today.year -> d.format(dayFmt)
        else -> d.format(dayYearFmt)
    }
}
