package ink.jvm.chatter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Longest side of the working bitmap. */
private const val MAX_SIDE = 2048
/** Mosaic block size in image pixels. */
private const val MOSAIC_BLOCK = 16
/** Smallest crop rectangle, image pixels. */
private const val MIN_CROP = 32f
/** The mosaic brush is this much wider than the pen at the same width setting. */
private const val MOSAIC_WIDTH_FACTOR = 3f

private val PEN_COLORS: List<Int> = listOf(0xFFEE5C8E, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF3B82F6, 0xFF000000, 0xFFFFFFFF).map { it.toInt() }
private val PEN_WIDTHS_DP = listOf(3f, 8f, 16f)

private enum class Tool { Pen, Mosaic, Crop }

/** One undoable step. Coordinates are always in pixels of the working bitmap; a crop only narrows the visible window. */
private sealed interface Op
private class PenOp(val color: Int, val width: Float, val path: AndroidPath) : Op { val compose = path.asComposePath() }
/** [outline] is the filled outline of the brush stroke; the mosaic layer is drawn clipped to it. */
private class MosaicOp(val outline: AndroidPath) : Op { val compose = outline.asComposePath() }
private class CropOp(val rect: Rect) : Op

/** Pen colour / width captured when a stroke starts; the points themselves live in a snapshot list so the canvas redraws. */
private class Live { var tool = Tool.Pen; var color = 0; var width = 1f }

/** The decoded photo plus its block-averaged twin (1/16 size, drawn nearest-neighbour under mosaic strokes). */
private class Session(val base: Bitmap, val mosaic: Bitmap) {
    val baseImage: ImageBitmap = base.asImageBitmap()
    val mosaicImage: ImageBitmap = mosaic.asImageBitmap()
    val full = Rect(0f, 0f, base.width.toFloat(), base.height.toFloat())
    private var busy = false
    private var closed = false

    /** The bake runs on IO with the bitmaps; recycling waits for it. */
    @Synchronized fun begin() { busy = true }
    @Synchronized fun end() { busy = false; if (closed) recycle() }
    @Synchronized fun close() { closed = true; if (!busy) recycle() }
    private fun recycle() { base.recycle(); mosaic.recycle() }
}

/** Full-screen editor for one picked photo: 画笔 / 马赛克 / 裁剪 / 撤销. 完成 writes a JPEG into cacheDir/edit and hands its Uri to [onDone]. */
@Composable
fun ImageEditorDialog(source: Uri, onDone: (Uri) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<Session?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val loaded = withContext(Dispatchers.IO) { runCatching { load(ctx, source) }.getOrNull() }
        if (loaded == null) {
            Toast.makeText(ctx, "无法打开图片", Toast.LENGTH_SHORT).show()
            onCancel()
        } else session = loaded
    }
    val current = session
    DisposableEffect(current) { onDispose { current?.close() } }

    val cancel = { if (!saving) onCancel() }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (current == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                Editor(current, enabled = !saving, onCancel = cancel) { ops ->
                    if (saving) return@Editor
                    saving = true
                    scope.launch {
                        current.begin()
                        val result = try {
                            withContext(Dispatchers.IO) { runCatching { bake(ctx, current, ops) } }
                        } finally {
                            current.end()
                            saving = false
                        }
                        result.onSuccess(onDone).onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Editor(s: Session, enabled: Boolean, onCancel: () -> Unit, onDone: (List<Op>) -> Unit) {
    val palette = LocalChatPalette.current
    val primary = MaterialTheme.colorScheme.primary
    var tool by remember { mutableStateOf(Tool.Pen) }
    var color by remember { mutableIntStateOf(PEN_COLORS[0]) }
    var widthDp by remember { mutableFloatStateOf(PEN_WIDTHS_DP[1]) }
    var ops by remember { mutableStateOf(listOf<Op>()) }
    /** Crop rectangle being adjusted (image px); only while the crop tool is active. */
    var pending by remember { mutableStateOf<Rect?>(null) }
    val livePts = remember { mutableStateListOf<Offset>() }
    val live = remember { Live() }
    val viewport = viewportOf(ops, s)
    val viewportNow = rememberUpdatedState(viewport)
    LaunchedEffect(tool, viewport) { pending = if (tool == Tool.Crop) viewport else null }

    fun applyCrop() {
        val pr = pending ?: return
        val r = Rect(pr.left.roundToInt().toFloat(), pr.top.roundToInt().toFloat(), pr.right.roundToInt().toFloat(), pr.bottom.roundToInt().toFloat()).intersect(viewport)
        if (r.width >= 1f && r.height >= 1f && r != viewport) ops = ops + CropOp(r)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Text("编辑图片", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().padding(8.dp)
                .pointerInput(tool, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (size.width == 0 || size.height == 0) return@awaitEachGesture
                        down.consume()
                        val vp = viewportNow.value
                        val fit = fitOf(size.toSize(), vp)
                        if (tool == Tool.Crop) {
                            val start = pending ?: vp
                            val sr = fit.toScreen(start)
                            val grab = 24.dp.toPx()
                            val corners = listOf(sr.topLeft, sr.topRight, sr.bottomLeft, sr.bottomRight)
                            val corner = corners.indexOfFirst { (it - down.position).getDistance() <= grab }
                            // 0..3 = drag that corner, 4 = move the box, 5 = draw a new box from the touch point.
                            val mode = if (corner >= 0) corner else if (sr.contains(down.position)) 4 else 5
                            val origin = clampPoint(fit.toImage(down.position), vp)
                            val minW = min(MIN_CROP, vp.width)
                            val minH = min(MIN_CROP, vp.height)
                            drag(down.id) { ch ->
                                ch.consume()
                                val raw = fit.toImage(ch.position)
                                val p = clampPoint(raw, vp)
                                pending = when (mode) {
                                    0 -> Rect(min(p.x, start.right - minW), min(p.y, start.bottom - minH), start.right, start.bottom)
                                    1 -> Rect(start.left, min(p.y, start.bottom - minH), max(p.x, start.left + minW), start.bottom)
                                    2 -> Rect(min(p.x, start.right - minW), start.top, start.right, max(p.y, start.top + minH))
                                    3 -> Rect(start.left, start.top, max(p.x, start.left + minW), max(p.y, start.top + minH))
                                    4 -> {
                                        val d = raw - origin
                                        start.translate(
                                            d.x.coerceIn(vp.left - start.left, vp.right - start.right),
                                            d.y.coerceIn(vp.top - start.top, vp.bottom - start.bottom),
                                        )
                                    }
                                    else -> Rect(min(origin.x, p.x), min(origin.y, p.y), max(origin.x, p.x), max(origin.y, p.y))
                                }
                            }
                            val r = pending
                            if (mode == 5 && r != null && (r.width < minW || r.height < minH)) pending = start
                        } else {
                            live.tool = tool
                            live.color = color
                            live.width = widthDp.dp.toPx() / fit.scale * (if (tool == Tool.Mosaic) MOSAIC_WIDTH_FACTOR else 1f)
                            livePts.clear()
                            livePts += fit.toImage(down.position)
                            try {
                                drag(down.id) { ch ->
                                    ch.consume()
                                    livePts += fit.toImage(ch.position)
                                }
                                val path = pathOf(livePts)
                                val op: Op = if (live.tool == Tool.Mosaic) MosaicOp(strokeOutline(path, live.width)) else PenOp(live.color, live.width, path)
                                ops = ops + op
                            } finally {
                                livePts.clear()
                            }
                        }
                    }
                },
        ) {
            val vp = viewport
            val fit = fitOf(size, vp)
            withTransform({
                translate(fit.ox, fit.oy)
                scale(fit.scale, fit.scale, Offset.Zero)
                translate(-vp.left, -vp.top)
            }) {
                clipRect(vp.left, vp.top, vp.right, vp.bottom) {
                    drawImage(s.baseImage)
                    for (op in ops) drawOp(op, s)
                    if (livePts.isNotEmpty()) {
                        val path = pathOf(livePts)
                        when (live.tool) {
                            Tool.Pen -> drawPath(path.asComposePath(), Color(live.color), style = Stroke(live.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            Tool.Mosaic -> drawMosaic(s, strokeOutline(path, live.width).asComposePath())
                            Tool.Crop -> Unit
                        }
                    }
                }
            }
            pending?.let { pr -> drawCropFrame(fit.toScreen(clampRect(pr, vp)), fit.toScreen(vp)) }
        }

        // Options for the active tool.
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tool) {
                Tool.Pen -> {
                    PEN_COLORS.forEach { c -> ColorDot(c, selected = color == c) { color = c } }
                    Spacer(Modifier.weight(1f))
                    PEN_WIDTHS_DP.forEach { w -> WidthDot(w, selected = widthDp == w, primary) { widthDp = w } }
                }
                Tool.Mosaic -> {
                    Text("涂抹要打码的地方", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    PEN_WIDTHS_DP.forEach { w -> WidthDot(w, selected = widthDp == w, primary) { widthDp = w } }
                }
                Tool.Crop -> {
                    Text("拖动边角或框内调整", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pending = viewport }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("还原", color = Color.White)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(primary).clickable(enabled = enabled) { applyCrop() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text("确定裁剪", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // Tool bar.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("画笔", selected = tool == Tool.Pen, enabled = enabled, onClick = { tool = Tool.Pen }) { tint ->
                Icon(Icons.Default.Edit, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            ToolButton("马赛克", selected = tool == Tool.Mosaic, enabled = enabled, onClick = { tool = Tool.Mosaic }) { tint -> MosaicGlyph(tint) }
            ToolButton("裁剪", selected = tool == Tool.Crop, enabled = enabled, onClick = { tool = Tool.Crop }) { tint -> CropGlyph(tint) }
            ToolButton("撤销", selected = false, enabled = enabled && ops.isNotEmpty(), onClick = { ops = ops.dropLast(1) }) { tint -> UndoGlyph(tint) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("取消", color = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(palette.accent)
                    .clickable(enabled = enabled) { onDone(ops) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text("完成", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---- rendering -------------------------------------------------------------------------------------------------------

/** Screen ↔ image mapping for the visible window [vp] letterboxed into the canvas. */
private class Fit(val scale: Float, val ox: Float, val oy: Float, val vp: Rect) {
    fun toImage(p: Offset) = Offset((p.x - ox) / scale + vp.left, (p.y - oy) / scale + vp.top)
    fun toScreen(p: Offset) = Offset(ox + (p.x - vp.left) * scale, oy + (p.y - vp.top) * scale)
    fun toScreen(r: Rect) = Rect(toScreen(r.topLeft), toScreen(r.bottomRight))
}

private fun fitOf(view: Size, vp: Rect): Fit {
    val s = min(view.width / vp.width, view.height / vp.height).let { if (it.isFinite() && it > 0f) it else 1f }
    return Fit(s, (view.width - vp.width * s) / 2f, (view.height - vp.height * s) / 2f, vp)
}

private fun viewportOf(ops: List<Op>, s: Session): Rect = (ops.lastOrNull { it is CropOp } as? CropOp)?.rect ?: s.full

private fun clampPoint(p: Offset, b: Rect) = Offset(p.x.coerceIn(b.left, b.right), p.y.coerceIn(b.top, b.bottom))

private fun clampRect(r: Rect, b: Rect) = Rect(
    r.left.coerceIn(b.left, b.right), r.top.coerceIn(b.top, b.bottom),
    r.right.coerceIn(b.left, b.right), r.bottom.coerceIn(b.top, b.bottom),
)

/** Smoothed polyline through [pts]; a lone point becomes a hair-length segment so round caps render a dot. */
private fun pathOf(pts: List<Offset>): AndroidPath {
    val p = AndroidPath()
    if (pts.isEmpty()) return p
    val first = pts[0]
    p.moveTo(first.x, first.y)
    if (pts.size == 1) {
        p.lineTo(first.x + 0.01f, first.y)
        return p
    }
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        p.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
    val last = pts.last()
    p.lineTo(last.x, last.y)
    return p
}

/** Filled outline of [path] stroked [width] wide with round caps and joins. */
private fun strokeOutline(path: AndroidPath, width: Float): AndroidPath {
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    return AndroidPath().also { paint.getFillPath(path, it) }
}

private fun DrawScope.drawMosaic(s: Session, outline: Path) {
    clipPath(outline) {
        drawImage(
            s.mosaicImage,
            dstSize = IntSize(s.mosaic.width * MOSAIC_BLOCK, s.mosaic.height * MOSAIC_BLOCK),
            filterQuality = FilterQuality.None,
        )
    }
}

private fun DrawScope.drawOp(op: Op, s: Session) {
    when (op) {
        is PenOp -> drawPath(op.compose, Color(op.color), style = Stroke(op.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        is MosaicOp -> drawMosaic(s, op.compose)
        is CropOp -> Unit
    }
}

/** Dim everything outside [sr] (within the image area [vr]), then the frame, thirds and corner handles. Screen coordinates. */
private fun DrawScope.drawCropFrame(sr: Rect, vr: Rect) {
    val dim = Color.Black.copy(alpha = 0.55f)
    fun band(l: Float, t: Float, r: Float, b: Float) {
        if (r > l && b > t) drawRect(dim, Offset(l, t), Size(r - l, b - t))
    }
    band(vr.left, vr.top, vr.right, sr.top)
    band(vr.left, sr.bottom, vr.right, vr.bottom)
    band(vr.left, sr.top, sr.left, sr.bottom)
    band(sr.right, sr.top, vr.right, sr.bottom)

    val thin = 1.dp.toPx()
    val guide = Color.White.copy(alpha = 0.35f)
    for (i in 1..2) {
        val x = sr.left + sr.width * i / 3f
        val y = sr.top + sr.height * i / 3f
        drawLine(guide, Offset(x, sr.top), Offset(x, sr.bottom), thin)
        drawLine(guide, Offset(sr.left, y), Offset(sr.right, y), thin)
    }
    drawRect(Color.White, sr.topLeft, sr.size, style = Stroke(1.5.dp.toPx()))

    val len = min(18.dp.toPx(), min(sr.width, sr.height) / 2f)
    val th = 3.dp.toPx()
    val white = Color.White
    val cap = StrokeCap.Square
    drawLine(white, Offset(sr.left, sr.top), Offset(sr.left + len, sr.top), th, cap)
    drawLine(white, Offset(sr.left, sr.top), Offset(sr.left, sr.top + len), th, cap)
    drawLine(white, Offset(sr.right, sr.top), Offset(sr.right - len, sr.top), th, cap)
    drawLine(white, Offset(sr.right, sr.top), Offset(sr.right, sr.top + len), th, cap)
    drawLine(white, Offset(sr.left, sr.bottom), Offset(sr.left + len, sr.bottom), th, cap)
    drawLine(white, Offset(sr.left, sr.bottom), Offset(sr.left, sr.bottom - len), th, cap)
    drawLine(white, Offset(sr.right, sr.bottom), Offset(sr.right - len, sr.bottom), th, cap)
    drawLine(white, Offset(sr.right, sr.bottom), Offset(sr.right, sr.bottom - len), th, cap)
}

// ---- widgets ---------------------------------------------------------------------------------------------------------

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Color(argb)
    Box(
        Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClick)
            .border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape)
            .padding(3.dp).clip(CircleShape).background(c)
            .border(if (argb == PEN_COLORS.last()) 1.dp else 0.dp, Color(0xFF888888), CircleShape),
    )
}

@Composable
private fun WidthDot(widthDp: Float, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .border(2.dp, if (selected) accent else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size((widthDp + 3f).dp).clip(CircleShape).background(Color.White)) }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else Color.White
    Column(
        Modifier.width(56.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp).alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { glyph(tint) }
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

@Composable
private fun MosaicGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val cell = size.width / 3f
        val gap = 1.dp.toPx()
        for (r in 0 until 3) for (c in 0 until 3) {
            val on = (r + c) % 2 == 0
            drawRect(tint.copy(alpha = if (on) 1f else 0.4f), Offset(c * cell, r * cell), Size(cell - gap, cell - gap))
        }
    }
}

@Composable
private fun CropGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.3f, 0f); lineTo(w * 0.3f, h * 0.7f); lineTo(w, h * 0.7f)
            moveTo(0f, h * 0.3f); lineTo(w * 0.7f, h * 0.3f); lineTo(w * 0.7f, h)
        }
        drawPath(p, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun UndoGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val t = 2.dp.toPx()
        val r = size.minDimension / 2f - t
        val c = center
        // Counter-clockwise arc from the bottom round to the upper left, arrowhead at its end.
        drawArc(tint, startAngle = 90f, sweepAngle = -250f, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = Stroke(t, cap = StrokeCap.Round))
        val a = -160.0 * PI / 180.0
        val end = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        val d = Offset(sin(a).toFloat(), -cos(a).toFloat())
        val n = Offset(-d.y, d.x)
        val len = 5.dp.toPx()
        val tip = end + d * (len * 0.6f)
        drawLine(tint, tip, tip - d * len + n * (len * 0.7f), t, StrokeCap.Round)
        drawLine(tint, tip, tip - d * len - n * (len * 0.7f), t, StrokeCap.Round)
    }
}

// ---- bitmap work (IO thread) ---------------------------------------------------------------------------------------

/** Decodes [uri] upright (EXIF) with the longest side ≤ [MAX_SIDE], or null when it cannot be read. */
private fun load(ctx: Context, uri: Uri): Session? {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val longest = max(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    // Sub-sample to at most 1.5× the target, then scale exactly; keeps the transient decode under ~37 MB.
    var sample = 1
    while (longest / sample > MAX_SIDE * 3 / 2) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val orientation = cr.openInputStream(uri)?.use { s ->
        runCatching { ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
    }
    val scale = min(1f, MAX_SIDE.toFloat() / max(raw.width, raw.height))
    if (scale < 1f) m.postScale(scale, scale)
    val upright = if (m.isIdentity) raw else {
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }
    return Session(upright, pixelate(upright, MOSAIC_BLOCK))
}

/** Block averages of [src] at [block]-pixel resolution: one pixel per block, drawn scaled up without filtering. */
private fun pixelate(src: Bitmap, block: Int): Bitmap {
    val w = src.width
    val h = src.height
    val bw = (w + block - 1) / block
    val bh = (h + block - 1) / block
    val out = IntArray(bw * bh)
    val rows = IntArray(w * block)
    for (by in 0 until bh) {
        val y0 = by * block
        val nRows = min(block, h - y0)
        src.getPixels(rows, 0, w, 0, y0, w, nRows)
        for (bx in 0 until bw) {
            val x0 = bx * block
            val nCols = min(block, w - x0)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in 0 until nRows) {
                val base = yy * w + x0
                for (xx in 0 until nCols) {
                    val p = rows[base + xx]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
            out[by * bw + bx] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    return Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888)
}

/** Composites the visible window plus every op into a fresh bitmap and writes it as a JPEG under cacheDir/edit. */
private fun bake(ctx: Context, s: Session, ops: List<Op>): Uri {
    val vp = viewportOf(ops, s)
    val left = vp.left.roundToInt().coerceIn(0, s.base.width - 1)
    val top = vp.top.roundToInt().coerceIn(0, s.base.height - 1)
    val w = (vp.right.roundToInt() - left).coerceIn(1, s.base.width - left)
    val h = (vp.bottom.roundToInt() - top).coerceIn(1, s.base.height - top)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    try {
        val c = AndroidCanvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.translate(-left.toFloat(), -top.toFloat())
        c.drawBitmap(s.base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val blocks = Paint().apply { isFilterBitmap = false }
        val scaleUp = Matrix().apply { setScale(MOSAIC_BLOCK.toFloat(), MOSAIC_BLOCK.toFloat()) }
        for (op in ops) when (op) {
            is PenOp -> {
                pen.color = op.color
                pen.strokeWidth = op.width
                c.drawPath(op.path, pen)
            }
            is MosaicOp -> {
                c.save()
                c.clipPath(op.outline)
                c.drawBitmap(s.mosaic, scaleUp, blocks)
                c.restore()
            }
            is CropOp -> Unit
        }
        val dir = File(ctx.cacheDir, "edit").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { if (!out.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("编码失败") }
        return Uri.fromFile(file)
    } finally {
        out.recycle()
    }
}
