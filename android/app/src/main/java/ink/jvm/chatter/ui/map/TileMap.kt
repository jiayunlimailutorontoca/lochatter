package ink.jvm.chatter.ui.map

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Where the map looks: centre in WGS-84 degrees and a fractional zoom (3 ≈ a country, 18 ≈ a street). */
data class MapState(val centerLat: Double, val centerLng: Double, val zoom: Double) {
    fun centeredAt(lat: Double, lng: Double): MapState = copy(centerLat = TileMath.clampLat(lat), centerLng = TileMath.wrapLng(lng))

    companion object {
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 18.0
    }
}

/** A teardrop marker whose tip sits on the coordinate; [accuracyM] > 0 draws a translucent circle under it. */
data class MapPin(val lat: Double, val lng: Double, val color: Color = PIN_RED, val label: String? = null, val accuracyM: Int = 0)

/** The "you are here" blue dot. */
data class MapDot(val lat: Double, val lng: Double, val accuracyM: Int = 0)

val PIN_RED = Color(0xFFE5393F)
val DOT_BLUE = Color(0xFF1E88E5)
private val PAPER = Color(0xFFE8E4DE)

data class TileKey(val z: Int, val x: Int, val y: Int)

/** Tiles are 256 px of map; on dense screens they are drawn larger so labels stay legible (osmdroid's "scaled to DPI"). */
internal fun tileScaleFor(density: Float): Float = (density * 0.85f).coerceIn(1f, 3f)

/** Pyramid level used for a fractional zoom: the nearest one, so tiles are drawn between 71 % and 141 % of their size. */
internal fun tileLevel(zoom: Double): Int = floor(zoom + 0.5).toInt().coerceIn(0, TileMath.MAX_TILE_Z)

/** Screen pixels per tile pixel at pyramid level [z]. */
internal fun screenScale(zoom: Double, z: Int, tileScale: Float): Double = 2.0.pow(zoom - z) * tileScale

/**
 * Process-wide tile memory: decoded bitmaps keyed by z/x/y, a bounded LRU shared by every map on screen (bubbles,
 * preview, picker). Coil does the network and disk caching underneath; a tile that fails to load (server without
 * `/tiles/`, offline) is left alone for a while instead of being retried on every frame.
 */
object TileStore {
    private const val MAX_TILES = 96
    private const val RETRY_MS = 30_000L
    private const val PARALLEL = 4
    private const val TIMEOUT_MS = 20_000L

    private val lock = Any()
    private val cache = object : LinkedHashMap<TileKey, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, ImageBitmap>?): Boolean = size > MAX_TILES
    }
    private val inFlight = HashSet<TileKey>()
    private val failedAt = HashMap<TileKey, Long>()
    private var wanted: Set<TileKey> = emptySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)

    /** Bumped whenever a tile lands; maps read it in their draw pass so they repaint. */
    val version = MutableStateFlow(0L)

    operator fun get(key: TileKey): ImageBitmap? = synchronized(lock) { cache[key] }

    /** Replaces the wanted set: queued requests that fell out of view are skipped when their turn comes. */
    fun want(ctx: Context, tiles: List<Pair<TileKey, String>>) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val start = synchronized(lock) {
            wanted = tiles.mapTo(HashSet()) { it.first }
            tiles.filter { (k, _) -> !cache.containsKey(k) && k !in inFlight && (failedAt[k]?.let { now - it < RETRY_MS } != true) }
                .onEach { inFlight += it.first }
        }
        for ((key, url) in start) scope.launch {
            try {
                gate.withPermit {
                    if (synchronized(lock) { key in wanted }) {
                        val bmp = fetch(app, url)
                        synchronized(lock) {
                            if (bmp != null) { cache[key] = bmp; failedAt.remove(key) } else failedAt[key] = System.currentTimeMillis()
                        }
                        if (bmp != null) version.update { it + 1 }
                    }
                }
            } finally {
                synchronized(lock) { inFlight -= key }
            }
        }
    }

    private suspend fun fetch(app: Context, url: String): ImageBitmap? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            val req = ImageRequest.Builder(app).data(url).size(coil.size.Size.ORIGINAL).allowHardware(false)
                .memoryCachePolicy(CachePolicy.DISABLED).build()
            val res = Coil.imageLoader(app).execute(req) as? SuccessResult
            val d = res?.drawable
            val bmp = (d as? BitmapDrawable)?.bitmap ?: d?.toBitmap()
            bmp?.asImageBitmap()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

/**
 * Slippy map drawn from XYZ tiles (`/tiles/{z}/{x}/{y}.png`, 256 px, OpenStreetMap through the server).
 * Pan, pinch-zoom around the fingers and double-tap to zoom in when [interactive]; a still thumbnail otherwise.
 * Missing tiles show the nearest cached ancestor scaled up, or plain paper; nothing here throws when the server
 * has no tiles or the phone is offline. [onMoveEnd] fires once per finished user gesture (not for programmatic moves).
 */
@Composable
fun TileMap(
    state: MapState,
    onStateChange: (MapState) -> Unit,
    tileUrl: (z: Int, x: Int, y: Int) -> String,
    modifier: Modifier = Modifier,
    pins: List<MapPin> = emptyList(),
    myLocation: MapDot? = null,
    interactive: Boolean = true,
    onMoveEnd: (MapState) -> Unit = {},
    /** "wgs84" (OpenStreetMap) or "gcj02" (Amap): tiles and pins are drawn in this datum, state and callbacks stay WGS-84. */
    datum: String = "wgs84",
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val tileScale = remember(density.density) { tileScaleFor(density.density) }
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(state)
    val change = rememberUpdatedState(onStateChange)
    val moveEnd = rememberUpdatedState(onMoveEnd)
    val version = TileStore.version.collectAsState()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val moved = remember { BooleanArray(1) }
    val measurer = rememberTextMeasurer()
    val gcj = datum == "gcj02"
    val ms = mapDatum(state, gcj)

    // Ask for the tiles in view plus a one-tile ring around them, nearest first.
    LaunchedEffect(state, sizePx, tileScale) {
        if (sizePx.width <= 0 || sizePx.height <= 0) return@LaunchedEffect
        val z = tileLevel(state.zoom)
        val scale = screenScale(state.zoom, z, tileScale)
        val cx = TileMath.lngToX(ms.centerLng, z.toDouble())
        val cy = TileMath.latToY(ms.centerLat, z.toDouble())
        val halfW = sizePx.width / 2 / scale
        val halfH = sizePx.height / 2 / scale
        val n = 1 shl z
        val x0 = floor((cx - halfW) / TileMath.TILE).toInt() - 1
        val x1 = floor((cx + halfW) / TileMath.TILE).toInt() + 1
        val y0 = floor((cy - halfH) / TileMath.TILE).toInt() - 1
        val y1 = floor((cy + halfH) / TileMath.TILE).toInt() + 1
        val ctX = cx / TileMath.TILE
        val ctY = cy / TileMath.TILE
        val wanted = ArrayList<Triple<Double, TileKey, String>>()
        for (ty in y0..y1) {
            if (ty < 0 || ty >= n) continue
            for (tx in x0..x1) {
                val wx = Math.floorMod(tx, n)
                val dx = tx + 0.5 - ctX
                val dy = ty + 0.5 - ctY
                wanted += Triple(dx * dx + dy * dy, TileKey(z, wx, ty), tileUrl(z, wx, ty))
            }
        }
        wanted.sortBy { it.first }
        TileStore.want(ctx, wanted.map { it.second to it.third })
    }

    val gestures = if (!interactive) Modifier else Modifier
        .pointerInput(tileScale) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val s = mapDatum(latest.value, gcj)
                var next = s
                if (zoom != 1f && zoom > 0f) {
                    val fdx = ((centroid.x - size.width / 2f) / tileScale).toDouble()
                    val fdy = ((centroid.y - size.height / 2f) / tileScale).toDouble()
                    next = TileMath.zoomedAround(next, next.zoom + log2(zoom.toDouble()), fdx, fdy)
                }
                if (pan != Offset.Zero) next = TileMath.panned(next, (-pan.x / tileScale).toDouble(), (-pan.y / tileScale).toDouble())
                if (next != s) {
                    moved[0] = true
                    change.value(wgsDatum(next, gcj))
                }
            }
        }
        .pointerInput(Unit) {
            // Passive watcher: once every finger is up after a gesture that moved the map, report where it ended.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                moved[0] = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
                if (moved[0]) {
                    moved[0] = false
                    moveEnd.value(latest.value)
                }
            }
        }
        .pointerInput(tileScale) {
            detectTapGestures(onDoubleTap = { pos ->
                scope.launch {
                    val start = mapDatum(latest.value, gcj)
                    val target = (floor(start.zoom) + 1).coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
                    if (target <= start.zoom) return@launch
                    val fdx = ((pos.x - size.width / 2f) / tileScale).toDouble()
                    val fdy = ((pos.y - size.height / 2f) / tileScale).toDouble()
                    animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                        change.value(wgsDatum(TileMath.zoomedAround(start, start.zoom + (target - start.zoom) * t, fdx, fdy), gcj))
                    }
                    moveEnd.value(latest.value)
                }
            })
        }

    Box(modifier.clip(RoundedCornerShape(0.dp)).background(PAPER).onSizeChanged { sizePx = it }.then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            drawTiles(ms, tileScale, version)
            for (p in pins) {
                if (p.accuracyM > 0) {
                    val r = (p.accuracyM / TileMath.metersPerPixel(p.lat, state.zoom) * tileScale).toFloat()
                    if (r > 6.dp.toPx()) {
                        val c = toScreen(ms, tileScale, p.lat, p.lng, gcj)
                        drawCircle(p.color.copy(alpha = 0.10f), r, c)
                        drawCircle(p.color.copy(alpha = 0.35f), r, c, style = Stroke(1.dp.toPx()))
                    }
                }
            }
            myLocation?.let { d ->
                val c = toScreen(ms, tileScale, d.lat, d.lng, gcj)
                val r = (d.accuracyM / TileMath.metersPerPixel(d.lat, state.zoom) * tileScale).toFloat()
                drawDot(c, r)
            }
            for (p in pins) {
                val c = toScreen(ms, tileScale, p.lat, p.lng, gcj)
                drawPin(c, p.color)
                p.label?.let { drawLabel(measurer, it, c) }
            }
        }
        Text(
            "© OpenStreetMap", fontSize = 9.sp, lineHeight = 11.sp, color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** The picker's fixed marker: place it with `Alignment.Center` and its tip points at the map centre. */
@Composable
fun CenterPin(color: Color = PIN_RED, modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp, 48.dp).offset(y = (-24).dp)) { drawPin(Offset(size.width / 2, size.height), color) }
}

private fun DrawScope.drawTiles(s: MapState, tileScale: Float, version: State<Long>) {
    // Reading the store's version here (not in composition) means a landed tile only repaints, without recomposing.
    version.value
    drawRect(PAPER)
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val z = tileLevel(s.zoom)
    val scale = screenScale(s.zoom, z, tileScale)
    val tilePx = TileMath.TILE * scale
    val n = 1 shl z
    val originX = TileMath.lngToX(s.centerLng, z.toDouble()) - (w / 2) / scale
    val originY = TileMath.latToY(s.centerLat, z.toDouble()) - (h / 2) / scale
    val tx0 = floor(originX / TileMath.TILE).toInt()
    val tx1 = floor((originX + w / scale) / TileMath.TILE).toInt()
    val ty0 = floor(originY / TileMath.TILE).toInt()
    val ty1 = floor((originY + h / scale) / TileMath.TILE).toInt()
    for (ty in ty0..ty1) {
        if (ty < 0 || ty >= n) continue
        val top = (ty * TileMath.TILE - originY) * scale
        val t = top.roundToInt()
        val b = (top + tilePx).roundToInt()
        for (tx in tx0..tx1) {
            val left = (tx * TileMath.TILE - originX) * scale
            val l = left.roundToInt()
            val r = (left + tilePx).roundToInt()
            val dst = IntSize(r - l, b - t)
            if (dst.width <= 0 || dst.height <= 0) continue
            val key = TileKey(z, Math.floorMod(tx, n), ty)
            val img = TileStore[key]
            if (img != null) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(l, t), dst)
            else drawAncestor(key, IntOffset(l, t), dst)
        }
    }
}

/** A missing tile shows the matching quarter (or 1/16, 1/64) of the nearest cached ancestor, scaled up. */
private fun DrawScope.drawAncestor(key: TileKey, dstOffset: IntOffset, dstSize: IntSize) {
    for (k in 1..3) {
        val pz = key.z - k
        if (pz < 0) return
        val parent = TileStore[TileKey(pz, key.x shr k, key.y shr k)] ?: continue
        val pw = parent.width shr k
        val ph = parent.height shr k
        if (pw <= 0 || ph <= 0) return
        val mask = (1 shl k) - 1
        drawImage(parent, IntOffset((key.x and mask) * pw, (key.y and mask) * ph), IntSize(pw, ph), dstOffset, dstSize)
        return
    }
}

/** The map centre in the tile datum. */
internal fun mapDatum(s: MapState, gcj: Boolean): MapState =
    if (!gcj) s else ink.jvm.chatter.util.Gcj02.fromWgs84(s.centerLat, s.centerLng).let { (a, b) -> s.copy(centerLat = a, centerLng = b) }

/** Back to WGS-84 after a gesture computed in the tile datum. */
internal fun wgsDatum(s: MapState, gcj: Boolean): MapState =
    if (!gcj) s else ink.jvm.chatter.util.Gcj02.toWgs84(s.centerLat, s.centerLng).let { (a, b) -> s.copy(centerLat = a, centerLng = b) }

private fun DrawScope.toScreen(s: MapState, tileScale: Float, wLat: Double, wLng: Double, gcj: Boolean): Offset {
    val (lat, lng) = if (gcj) ink.jvm.chatter.util.Gcj02.fromWgs84(wLat, wLng) else wLat to wLng
    val x = (TileMath.lngToX(lng, s.zoom) - TileMath.lngToX(s.centerLng, s.zoom)) * tileScale + size.width / 2
    val y = (TileMath.latToY(lat, s.zoom) - TileMath.latToY(s.centerLat, s.zoom)) * tileScale + size.height / 2
    return Offset(x.toFloat(), y.toFloat())
}

/** WeChat-style teardrop: round head, tapered tail, white rim, white dot; [tip] is the exact coordinate. */
fun DrawScope.drawPin(tip: Offset, color: Color) {
    val r = 10.dp.toPx()
    val cy = tip.y - 26.dp.toPx()
    drawOval(Color.Black.copy(alpha = 0.22f), Offset(tip.x - 7.dp.toPx(), tip.y - 2.5f.dp.toPx()), Size(14.dp.toPx(), 5.dp.toPx()))
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        cubicTo(tip.x - 3.dp.toPx(), tip.y - 9.dp.toPx(), tip.x - r, cy + 7.dp.toPx(), tip.x - r, cy)
        arcTo(Rect(tip.x - r, cy - r, tip.x + r, cy + r), 180f, 180f, false)
        cubicTo(tip.x + r, cy + 7.dp.toPx(), tip.x + 3.dp.toPx(), tip.y - 9.dp.toPx(), tip.x, tip.y)
        close()
    }
    drawPath(path, color)
    drawPath(path, Color.White, style = Stroke(1.5.dp.toPx()))
    drawCircle(Color.White, 4.dp.toPx(), Offset(tip.x, cy))
}

private fun DrawScope.drawDot(c: Offset, accuracyPx: Float) {
    if (accuracyPx > 8.dp.toPx()) {
        drawCircle(DOT_BLUE.copy(alpha = 0.12f), accuracyPx, c)
        drawCircle(DOT_BLUE.copy(alpha = 0.35f), accuracyPx, c, style = Stroke(1.dp.toPx()))
    }
    drawCircle(Color.White, 9.dp.toPx(), c)
    drawCircle(DOT_BLUE, 6.5f.dp.toPx(), c)
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, tip: Offset) {
    val layout = measurer.measure(text, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF222222)))
    val pad = 5.dp.toPx()
    val bw = layout.size.width + pad * 2
    val bh = layout.size.height + pad
    val topLeft = Offset(tip.x - bw / 2, tip.y - 44.dp.toPx() - bh)
    drawRoundRect(Color.White.copy(alpha = 0.92f), topLeft, Size(bw, bh), CornerRadius(6.dp.toPx()))
    drawText(layout, topLeft = Offset(topLeft.x + pad, topLeft.y + pad / 2))
}
