package ink.jvm.chatter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.jvm.chatter.call.Stroke
import ink.jvm.chatter.call.Whiteboard
import java.util.UUID

private const val ROSE = 0xFFEE5C8EL
private val Palette = listOf(ROSE, 0xFF000000L, 0xFF3B82F6L, 0xFF22C55EL, 0xFFF59E0BL)
private const val ERASER = 0xFFFFFFFFL
private const val ERASER_WIDTH = 24f
private val Widths = listOf(4f, 10f)

/** Full-screen drawing surface shown over a call; strokes sync both ways through [board]. */
@Composable
fun WhiteboardOverlay(board: Whiteboard, onClose: () -> Unit) {
    val strokes by board.strokes.collectAsStateWithLifecycle()
    var color by remember { mutableStateOf(ROSE) }
    var width by remember { mutableFloatStateOf(Widths[0]) }
    var eraser by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<Float>>(emptyList()) }
    val close = { board.setOpen(false); onClose() }
    BackHandler(onBack = close)

    val strokeColor = if (eraser) ERASER else color
    val strokeWidth = if (eraser) ERASER_WIDTH else width
    fun finish() {
        val pts = live
        live = emptyList()
        if (pts.size < 2) return
        board.addLocal(Stroke(UUID.randomUUID().toString(), strokeColor, strokeWidth, pts))
    }

    Column(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.92f))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Palette.forEach { c ->
                Dot(Color(c), 22.dp, selected = !eraser && color == c) { color = c; eraser = false }
            }
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(Color.White)
                    .border(if (eraser) 2.dp else 1.dp, if (eraser) Color(ROSE) else Color(0xFFBBBBBB), CircleShape)
                    .clickable { eraser = true },
                contentAlignment = Alignment.Center,
            ) { Text("擦", color = Color(0xFF666666), fontSize = 11.sp) }
            Spacer(Modifier.width(4.dp))
            Widths.forEach { w ->
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .border(if (!eraser && width == w) 2.dp else 0.dp, Color(ROSE), CircleShape)
                        .clickable { width = w; eraser = false },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size((w + 2).dp).clip(CircleShape).background(Color(0xFF444444))) }
            }
            Spacer(Modifier.weight(1f))
            val pad = PaddingValues(horizontal = 6.dp)
            TextButton(onClick = { board.undoLast() }, contentPadding = pad) { Text("撤销", color = Color(ROSE)) }
            TextButton(onClick = { board.clear() }, contentPadding = pad) { Text("清空", color = Color(ROSE)) }
            TextButton(onClick = close, contentPadding = pad) { Text("关闭", color = Color(ROSE), fontWeight = FontWeight.Bold) }
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p -> live = listOf(p.x / size.width, p.y / size.height) },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = change.position
                            live = live + listOf(p.x / size.width, p.y / size.height)
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    )
                }
                .pointerInput(Unit) {
                    // A plain tap leaves a dot (two identical points) and never falls through to the call UI.
                    detectTapGestures { p ->
                        val nx = p.x / size.width; val ny = p.y / size.height
                        live = listOf(nx, ny, nx, ny)
                        finish()
                    }
                },
        ) {
            strokes.forEach { s ->
                drawStroke(s.points, Color(s.color).let { if (s.mine) it else it.copy(alpha = it.alpha * 0.85f) }, s.width.dp.toPx())
            }
            if (live.size >= 2) drawStroke(live, Color(strokeColor), strokeWidth.dp.toPx())
        }
    }
}

private fun DrawScope.drawStroke(points: List<Float>, color: Color, widthPx: Float) {
    if (points.size < 2) return
    val w = size.width; val h = size.height
    if (points.size == 2) {
        drawCircle(color, widthPx / 2, Offset(points[0] * w, points[1] * h)); return
    }
    val path = Path().apply {
        moveTo(points[0] * w, points[1] * h)
        var i = 2
        while (i + 1 < points.size) { lineTo(points[i] * w, points[i + 1] * h); i += 2 }
    }
    drawPath(path, color, style = DrawStroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun Dot(c: Color, size: Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(c)
            .border(if (selected) 3.dp else 0.dp, Color(0xFF444444).copy(alpha = if (selected) 0.6f else 0f), CircleShape)
            .clickable(onClick = onClick),
    )
}
