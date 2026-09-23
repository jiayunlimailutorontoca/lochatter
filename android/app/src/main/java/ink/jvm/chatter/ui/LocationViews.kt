package ink.jvm.chatter.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.jvm.chatter.ui.map.MapPin
import ink.jvm.chatter.ui.map.MapState
import ink.jvm.chatter.ui.map.TileMap
import ink.jvm.chatter.util.Fix
import ink.jvm.chatter.util.Locator
import java.util.Locale

/** Opens the fix in whatever map app handles geo: URIs (the pre-1.6 behaviour, kept as the fallback). */
fun openExternalMap(ctx: Context, fix: Fix) {
    runCatching { ctx.startActivity(Locator.mapIntent(fix)) }.onFailure { Toast.makeText(ctx, "没有地图应用", Toast.LENGTH_SHORT).show() }
}

/**
 * Location bubble: a real map thumbnail when the server proxies tiles (1.6, [tileUrl]) or the drawn placeholder,
 * then the place name / address and the live badge. [onClick] opens the in-app preview; without it the external map.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationBubble(
    fix: Fix,
    live: Boolean,
    mine: Boolean,
    tileUrl: ((Int, Int, Int) -> String)? = null,
    datum: String = "wgs84",
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val fg = LocalContentColor.current
    val (name, addr) = remember(fix.address) { Locator.splitPlace(fix.address) }
    Column(
        modifier
            .width(240.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(fg.copy(alpha = 0.06f))
            .combinedClickable(onClick = { if (onClick != null) onClick() else openExternalMap(ctx, fix) }, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            if (tileUrl != null) {
                val state = remember(fix.lat, fix.lng) { MapState(fix.lat, fix.lng, 15.5) }
                TileMap(
                    state = state, onStateChange = {}, tileUrl = tileUrl, pins = listOf(MapPin(fix.lat, fix.lng)),
                    interactive = false, datum = datum, modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlaceholderMap(mine, accent, fg)
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                name ?: addr ?: "纬度 %.5f，经度 %.5f".format(Locale.US, fix.lat, fix.lng),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (name != null && addr != null) {
                Text(addr, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (live) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF34C759)))
                    Spacer(Modifier.width(4.dp))
                    Text("实时位置", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                    Spacer(Modifier.width(8.dp))
                }
                if (fix.accuracyM > 0) Text("±${fix.accuracyM} 米", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.55f))
            }
        }
    }
}

/** The pre-1.6 thumbnail: a grid, two "streets" and a pin, so the bubble still reads as a map without tiles. */
@Composable
private fun PlaceholderMap(mine: Boolean, accent: Color, fg: Color) {
    Box(Modifier.fillMaxSize().background(if (mine) Color.White.copy(alpha = 0.18f) else accent.copy(alpha = 0.08f))) {
        Canvas(Modifier.fillMaxSize()) {
            val grid = fg.copy(alpha = 0.12f)
            var x = 0f
            while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += 28f * density }
            var y = 0f
            while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += 28f * density }
            val road = fg.copy(alpha = 0.18f)
            val p = Path().apply { moveTo(0f, size.height * 0.7f); quadraticTo(size.width * 0.4f, size.height * 0.4f, size.width, size.height * 0.55f) }
            drawPath(p, road, style = Stroke(width = 6f * density, cap = StrokeCap.Round))
            drawLine(road, Offset(size.width * 0.3f, 0f), Offset(size.width * 0.45f, size.height), 5f * density, StrokeCap.Round)
            val cx = size.width / 2
            val cy = size.height / 2
            drawCircle(accent.copy(alpha = 0.25f), 16f * density, Offset(cx, cy + 6f * density))
            val pin = Path().apply {
                moveTo(cx, cy + 8f * density)
                cubicTo(cx - 14f * density, cy - 8f * density, cx - 12f * density, cy - 26f * density, cx, cy - 26f * density)
                cubicTo(cx + 12f * density, cy - 26f * density, cx + 14f * density, cy - 8f * density, cx, cy + 8f * density)
                close()
            }
            drawPath(pin, accent)
            drawCircle(Color.White, 4.5f * density, Offset(cx, cy - 14f * density))
        }
    }
}
