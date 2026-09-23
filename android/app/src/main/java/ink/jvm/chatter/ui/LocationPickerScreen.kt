package ink.jvm.chatter.ui

import android.Manifest
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.Poi
import ink.jvm.chatter.ui.map.CenterPin
import ink.jvm.chatter.ui.map.MapDot
import ink.jvm.chatter.ui.map.MapState
import ink.jvm.chatter.ui.map.TileMap
import ink.jvm.chatter.util.Fix
import ink.jvm.chatter.util.Locator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val PICK_ZOOM = 16.0

/** A row of the picker list: the exact map centre or a place from the server. */
private data class Place(val name: String, val address: String?, val lat: Double, val lng: Double, val accuracyM: Int, val distance: Int?, val isCenter: Boolean)

/**
 * WeChat-like 选点 page: the map on top with a fixed centre pin (drag the map under it), 「定位」 to jump to the
 * phone's position, and below it a search box plus the places around the centre (`/geo/regeo`, 1.6). Without the
 * server's geo helper the list has only the centre with the system geocoder's address. 发送 hands the chosen
 * place to [onSend] as a WGS-84 [Fix] whose address is "name·address".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerScreen(repo: ChatRepository, onSend: (Fix, live: Boolean) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val features by repo.features.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Locator.hasPermission(ctx)) }
    var map by remember { mutableStateOf(MapState(35.86, 104.19, 4.0)) }
    var me by remember { mutableStateOf<Fix?>(null) }
    var locating by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf(false) }
    var centerAddress by remember { mutableStateOf<String?>(null) }
    var centerName by remember { mutableStateOf<String?>(null) }
    var places by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Poi>?>(null) }
    var live by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<Job?>(null) }
    var search by remember { mutableStateOf<Job?>(null) }
    var gen by remember { mutableIntStateOf(0) }

    fun lookupCenter(state: MapState) {
        lookup?.cancel()
        val g = ++gen
        centerName = null
        selected = 0
        lookup = scope.launch {
            delay(600)
            loading = true
            if (features.geo) {
                val r = runCatching { repo.api.geoRegeo(state.centerLat, state.centerLng) }.getOrNull()
                if (g != gen) return@launch
                if (r != null) { centerAddress = r.address; centerName = r.name; places = r.pois }
                else { centerAddress = "纬度 %.5f，经度 %.5f".format(Locale.US, state.centerLat, state.centerLng); places = emptyList() }
            } else {
                val a = runCatching { Locator.geocode(ctx, state.centerLat, state.centerLng) }.getOrNull()
                if (g != gen) return@launch
                centerAddress = a ?: "纬度 %.5f，经度 %.5f".format(Locale.US, state.centerLat, state.centerLng)
                places = emptyList()
            }
            loading = false
        }
    }
    suspend fun locate() {
        locating = true
        val f = runCatching { Locator.current(ctx) }.getOrNull()
        locating = false
        if (f == null) { Toast.makeText(ctx, "定位失败，请打开 GPS 后重试", Toast.LENGTH_SHORT).show(); return }
        me = f
        located = true
        map = MapState(f.lat, f.lng, PICK_ZOOM)
        lookupCenter(map)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.any { it }
        if (granted) scope.launch { locate() } else Toast.makeText(ctx, "没有定位权限，可以拖动地图手动选点", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) {
        if (granted) locate() else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(query) {
        search?.cancel()
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        if (!features.geo) return@LaunchedEffect
        search = scope.launch {
            delay(500)
            results = runCatching { repo.api.geoSearch(query.trim(), map.centerLat, map.centerLng) }.getOrElse { emptyList() }
            selected = -1
        }
    }

    val list: List<Place> = remember(centerAddress, centerName, places, results, map, me, located) {
        val r = results
        if (r != null) {
            r.map { Place(it.name, it.address.takeIf { a -> a.isNotBlank() }, it.lat, it.lng, 0, it.distance.takeIf { d -> d > 0 }, isCenter = false) }
        } else {
            val atMe = me?.let { m -> located && Math.abs(m.lat - map.centerLat) < 1e-5 && Math.abs(m.lng - map.centerLng) < 1e-5 } == true
            val center = Place(
                centerName ?: if (atMe) "我的位置" else "地图中心", centerAddress ?: "正在查地址…", map.centerLat, map.centerLng,
                if (atMe) me!!.accuracyM else 0, null, isCenter = true,
            )
            listOf(center) + places.filter { it.name != centerName }.map { Place(it.name, it.address.takeIf { a -> a.isNotBlank() }, it.lat, it.lng, 0, it.distance, isCenter = false) }
        }
    }
    val chosen = list.getOrNull(selected)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发送位置") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消") } },
                actions = {
                    TextButton(enabled = chosen != null, onClick = {
                        val p = chosen ?: return@TextButton
                        onSend(Fix(p.lat, p.lng, p.accuracyM, Locator.placeText(p.name, p.address)), live)
                    }) { Text(if (live) "开始分享" else "发送", fontWeight = FontWeight.SemiBold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.fillMaxWidth().weight(0.45f)) {
                TileMap(
                    state = map,
                    onStateChange = { map = it },
                    tileUrl = repo.api::tileUrl,
                    myLocation = me?.let { MapDot(it.lat, it.lng, it.accuracyM) },
                    onMoveEnd = { s -> results = null; query = ""; lookupCenter(s) },
                    datum = features.tileDatum ?: "wgs84",
                    modifier = Modifier.fillMaxSize(),
                )
                CenterPin(modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    MapRoundButton(R.drawable.ic_my_location, "定位", tint = MaterialTheme.colorScheme.primary, busy = locating) {
                        if (granted) scope.launch { locate() } else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                if (!features.tiles) {
                    Text(
                        "服务器没开地图瓦片，先按坐标发送", style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (features.geo) {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("搜索地点") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "清除") } },
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("实时位置（15 分钟，每 10 秒更新）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Switch(checked = live, onCheckedChange = { live = it })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().weight(0.55f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list.size) { i ->
                        val p = list[i]
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = i
                                if (!p.isCenter) map = MapState(p.lat, p.lng, maxOf(map.zoom, PICK_ZOOM))
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (p.isCenter) "[当前位置] ${p.name}" else p.name,
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val sub = listOfNotNull(p.distance?.let { d -> if (d < 1000) "${d} 米" else "%.1f 公里".format(Locale.US, d / 1000.0) }, p.address).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected == i) Icon(Icons.Default.Check, contentDescription = "已选", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                if (list.isEmpty()) Text("没有找到地点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
