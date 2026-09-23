package ink.jvm.chatter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.jvm.chatter.BuildConfig
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.ReleaseInfo
import ink.jvm.chatter.util.UpdateChecker
import kotlinx.coroutines.launch

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val palette = LocalChatPalette.current
    Column(
        Modifier.fillMaxSize().background(palette.canvas).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(palette.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("lochatter 已锁定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock, shape = RoundedCornerShape(16.dp)) { Text("解锁") }
    }
}

@Composable
fun UpdateDialog(r: ReleaseInfo, onDownload: () -> Unit, onSkip: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("发现新版本 ${r.versionName}") },
        text = {
            Column {
                Text("当前 ${BuildConfig.VERSION_NAME}，新版 ${r.versionName}" + if (r.size > 0) "（${"%.1f".format(r.size / 1048576.0)} MB）" else "")
                if (r.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(r.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text("下载完成后会弹出安装界面。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("下载安装") } },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("跳过此版") }
                TextButton(onClick = onLater) { Text("稍后") }
            }
        },
    )
}
