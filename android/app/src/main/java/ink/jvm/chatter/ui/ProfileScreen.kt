package ink.jvm.chatter.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.data.ChatRepository
import kotlinx.coroutines.launch

/** [mine] is this account's profile (avatar and signature can be changed). Otherwise it is the other person, read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    repo: ChatRepository,
    mine: Boolean,
    onBack: () -> Unit,
    onAnniversaries: () -> Unit,
    onAlbum: () -> Unit,
) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun leave() {
        focus.clearFocus(force = true)
        onBack()
    }
    BackHandler(onBack = ::leave)
    val myName = repo.prefs.userName.ifEmpty { "我" }
    val peerName = repo.prefs.peerName.ifEmpty { "对方" }
    val shownName = if (mine) myName else peerName
    val avatarId by (if (mine) repo.myAvatarId else repo.peerAvatarId).collectAsStateWithLifecycle()
    val signature by (if (mine) repo.mySignature else repo.peerSignature).collectAsStateWithLifecycle()
    var draft by remember(signature) { mutableStateOf(signature) }
    LaunchedEffect(Unit) { runCatching { repo.refreshShared() } }
    var safety by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { repo.setAvatar(uri) }
                .onFailure { Toast.makeText(ctx, "头像没换上：${it.message}", Toast.LENGTH_SHORT).show() }
                .onSuccess { Toast.makeText(ctx, "头像已同步", Toast.LENGTH_SHORT).show() }
        }
    }
    if (safety) {
        SafetyNumberDialog(repo, repo.prefs.e2eVerified, onVerified = { repo.setVerified(it) }, onClose = { safety = false })
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (mine) "我的资料" else "对方资料") },
                navigationIcon = {
                    Row(
                        Modifier.clickable(onClick = ::leave).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        Text("返回", style = MaterialTheme.typography.titleMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text(shownName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Avatar(
                shownName,
                size = 96.dp,
                textStyle = MaterialTheme.typography.displaySmall,
                image = avatarId?.let { repo.api.mediaUrl(it) },
                modifier = Modifier.clip(CircleShape).clickable(enabled = mine) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            Text(
                if (mine) "点头像更换。只会改你自己的，对方那边会跟着更新。" else "对方的头像。他们一换，这里大约半分钟内会更新，在线时马上换。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 28.dp, end = 28.dp),
            )
            Spacer(Modifier.height(20.dp))
            if (mine) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 80) draft = it },
                    label = { Text("我的签名") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    minLines = 2,
                )
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.setSignature(draft.trim()) }
                            .onFailure { Toast.makeText(ctx, "没保存上：${it.message}", Toast.LENGTH_SHORT).show() }
                            .onSuccess { Toast.makeText(ctx, "签名已更新", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("保存签名") }
            } else {
                Text(
                    signature.ifBlank { "还没有签名" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (signature.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                ProfileRow("安全码", "当面对一下这 60 位数字") {
                    if (repo.safetyNumber() != null) safety = true else Toast.makeText(ctx, "对方还没有密钥", Toast.LENGTH_SHORT).show()
                }
                HorizontalDivider(Modifier.padding(start = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ProfileRow("纪念日", "一起倒数的日子", onAnniversaries)
                HorizontalDivider(Modifier.padding(start = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ProfileRow("我们的相册", "聊天里的照片都会进来", onAlbum)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (mine) "你的头像和签名只由这个账号修改。对方看到的是你的，你看到的是对方的。"
                else "这里不能改对方的头像和签名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun ProfileRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
