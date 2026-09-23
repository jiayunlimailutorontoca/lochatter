package ink.jvm.chatter.ui

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.ui.map.MapDot
import ink.jvm.chatter.ui.map.MapPin
import ink.jvm.chatter.ui.map.MapState
import ink.jvm.chatter.ui.map.TileMap
import ink.jvm.chatter.ui.map.TileMath
import ink.jvm.chatter.ui.map.tileScaleFor
import ink.jvm.chatter.util.Fix
import ink.jvm.chatter.util.Locator
import ink.jvm.chatter.util.MapApps
import kotlinx.coroutines.launch
import java.util.Locale

private const val PREVIEW_ZOOM = 16.0

/**
 * WeChat-like full-screen map for one location message: the pin on a draggable map, the place card, and 导航 /
 * 分享 / 复制坐标. Follows the message by id so a live share moves the pin as edits arrive; the sender of a
 * running live share gets a 停止分享 button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPreviewScreen(repo: ChatRepository, message: LocalMessage, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val palette = LocalChatPalette.current
    val messages by repo.messages.collectAsStateWithLifecycle()
    val liveId by repo.liveLocationId.collectAsStateWithLifecycle()
    val features by repo.features.collectAsStateWithLifecycle()
    val current = messages.firstOrNull { it.id == message.id } ?: message
    val decoded = remember(current.text) { Locator.decode(current.text) }
    if (decoded == null) {
        LaunchedEffect(Unit) { Toast.makeText(ctx, "这条位置消息无法解析", Toast.LENGTH_SHORT).show(); onBack() }
        return
    }
    val (fix, live) = decoded
    val mine = current.from == repo.me
    val sharing = live && mine && liveId == current.id
    val (name, addr) = remember(fix.address) { Locator.splitPlace(fix.address) }
    val title = name ?: addr ?: "位置"
    var map by remember { mutableStateOf(MapState(fix.lat, fix.lng, PREVIEW_ZOOM)) }
    var following by remember { mutableStateOf(true) }
    var me by remember { mutableStateOf<Fix?>(null) }
    var locating by remember { mutableStateOf(false) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var cardH by remember { mutableIntStateOf(0) }
    var navSheet by remember { mutableStateOf(false) }
    // Live share: keep the pin centred until the user drags away.
    LaunchedEffect(fix.lat, fix.lng) { if (following) map = map.centeredAt(fix.lat, fix.lng) }

    fun fitBoth(a: Fix, b: Fix) {
        val ts = tileScaleFor(density.density)
        val w = sizePx.width / ts
        val h = (sizePx.height - cardH) / ts
        val zoom = if (sizePx.width <= 0 || h <= 0) 15.0 else TileMath.fitZoom(a.lat, a.lng, b.lat, b.lng, w.toDouble(), h.toDouble(), paddingWorldPx = (with(density) { 40.dp.toPx() } / ts).toDouble(), maxZoom = 17.0)
        val (mlat, mlng) = TileMath.midpoint(a.lat, a.lng, b.lat, b.lng)
        // The card covers the bottom of the map: shift the centre down so the pair sits in the visible part.
        map = TileMath.panned(MapState(mlat, mlng, zoom), 0.0, (cardH / 2 / ts).toDouble())
        following = false
    }
    suspend fun locate() {
        locating = true
        val f = runCatching { Locator.current(ctx) }.getOrNull()
        locating = false
        if (f == null) Toast.makeText(ctx, "定位失败，请打开 GPS 后重试", Toast.LENGTH_SHORT).show()
        else { me = f; fitBoth(fix, f) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) scope.launch { locate() } else Toast.makeText(ctx, "没有定位权限", Toast.LENGTH_SHORT).show()
    }
    fun myLocation() {
        if (locating) return
        if (Locator.hasPermission(ctx)) scope.launch { locate() }
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent) }.onFailure { Toast.makeText(ctx, "没有地图应用", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("位置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).onSizeChanged { sizePx = it }) {
            TileMap(
                state = map,
                onStateChange = { map = it },
                tileUrl = repo.api::tileUrl,
                pins = listOf(MapPin(fix.lat, fix.lng, accuracyM = fix.accuracyM)),
                myLocation = me?.let { MapDot(it.lat, it.lng, it.accuracyM) },
                onMoveEnd = { following = false },
                datum = features.tileDatum ?: "wgs84",
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = with(density) { cardH.toDp() } + 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapRoundButton(R.drawable.ic_location, "回到位置", tint = MaterialTheme.colorScheme.primary) { map = MapState(fix.lat, fix.lng, PREVIEW_ZOOM); following = true }
                MapRoundButton(R.drawable.ic_my_location, "我的位置", tint = MaterialTheme.colorScheme.onSurface, busy = locating) { myLocation() }
            }
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { cardH = it.height },
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (name != null && addr != null) {
                        Text(addr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(
                        listOfNotNull(if (fix.accuracyM > 0) "精度 ±${fix.accuracyM} 米" else null, "%.5f, %.5f".format(Locale.US, fix.lat, fix.lng)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    if (live) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                "实时位置 · 更新于 " + fmtTime(current.editedAt ?: current.ts), color = Color.White, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clip(CircleShape).background(palette.online).padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                            if (sharing) {
                                Spacer(Modifier.width(10.dp))
                                OutlinedButton(onClick = { repo.stopLiveLocation() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                                    Text("停止分享", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CardAction(R.drawable.ic_navigation, "导航") { navSheet = true }
                        CardAction(null, "分享") {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, MapApps.shareText(name, addr, fix.lat, fix.lng))
                            open(Intent.createChooser(send, "分享位置"))
                        }
                        CardAction(R.drawable.ic_copy, "复制坐标") {
                            clipboard.setText(AnnotatedString(MapApps.coordText(fix.lat, fix.lng)))
                            Toast.makeText(ctx, "已复制坐标", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    if (navSheet) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val apps = remember { MapApps.installed(ctx, fix.lat, fix.lng, title) }
        ModalBottomSheet(onDismissRequest = { navSheet = false }, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
            Text("用哪个地图导航", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            if (apps.isEmpty()) {
                Text("没有找到高德、百度、腾讯或 Google 地图；试试系统的地图选择。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            apps.forEach { app ->
                Text(
                    app.label, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { navSheet = false; open(MapApps.intentFor(app, fix.lat, fix.lng, title)) }.padding(horizontal = 24.dp, vertical = 14.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(
                "其他地图应用", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { navSheet = false; open(MapApps.genericIntent(fix.lat, fix.lng, title)) }.padding(horizontal = 24.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Round floating button over the map (回到位置 / 我的位置 / 定位). */
@Composable
internal fun MapRoundButton(icon: Int, description: String, tint: Color, busy: Boolean = false, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = Modifier.size(44.dp).clickable(enabled = !busy, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CardAction(icon: Int?, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            else Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
