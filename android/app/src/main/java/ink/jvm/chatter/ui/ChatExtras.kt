package ink.jvm.chatter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ink.jvm.chatter.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val mdHm: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val ymdHm: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** WeChat's time chip text: today "21:03", yesterday "昨天 21:03", this week "周三 21:03", then the date. */
internal fun fmtChatTime(ts: Long): String {
    val z = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    val d = z.toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> z.format(hm)
        d == today.minusDays(1) -> "昨天 " + z.format(hm)
        d.isAfter(today.minusDays(7)) -> WEEK[d.dayOfWeek.value - 1] + " " + z.format(hm)
        d.year == today.year -> z.format(mdHm)
        else -> z.format(ymdHm)
    }
}

/** Time separator between message groups more than five minutes apart (1.7, WeChat rule). */
@Composable
internal fun TimeChip(ts: Long) {
    val palette = LocalChatPalette.current
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Surface(color = palette.dayChip, shape = CircleShape) {
            Text(fmtChatTime(ts), style = MaterialTheme.typography.labelSmall, color = palette.onDayChip, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

/** The multi-select circle at the left of a bubble row. */
@Composable
internal fun SelectDot(selected: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Default.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(16.dp)) }
}

/** Top bar while selecting messages: count plus 转发 / 收藏 / 保存 / 删除. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(count: Int, onClose: () -> Unit, onForward: () -> Unit, onFavorite: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        title = { Text("已选 $count 项") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "取消") } },
        actions = {
            IconButton(onClick = onForward, enabled = count > 0) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "转发") }
            IconButton(onClick = onFavorite, enabled = count > 0) { Icon(Icons.Default.Star, contentDescription = "收藏") }
            IconButton(onClick = onSave, enabled = count > 0) { Icon(painterResource(R.drawable.ic_download), contentDescription = "保存") }
            IconButton(onClick = onDelete, enabled = count > 0) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** 转发 target: the other person or the assistant. */
@Composable
internal fun ForwardDialog(count: Int, peerName: String, botName: String?, onClose: () -> Unit, onPick: (toBot: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (count > 1) "转发 $count 条消息" else "转发") },
        text = {
            Column {
                Text("发给 $peerName", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onPick(false) }.padding(vertical = 12.dp))
                if (botName != null) Text("发给 $botName", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onPick(true) }.padding(vertical = 12.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** Double-tap a text bubble: the text alone on a full screen, selectable, pinch to resize, tap to leave. */
@Composable
internal fun BigTextDialog(text: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
                .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.7f, 3f) } },
        ) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 64.dp)) {
                SelectionContainer {
                    Text(text, fontSize = (22 * scale).sp, lineHeight = (32 * scale).sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
