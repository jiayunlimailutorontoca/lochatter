package ink.jvm.chatter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * 「+」→ 拍摄. Tap the white button for a photo, tap the red one for a clip of at most 60 seconds
 * (a ring fills as it records). The result goes to the existing send preview.
 */
@Composable
fun CaptureScreen(onDone: (Uri) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        granted = map[Manifest.permission.CAMERA] == true
        if (!granted) {
            Toast.makeText(ctx, "需要相机权限才能拍摄", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }
    LaunchedEffect(Unit) {
        if (!granted) perm.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    if (!granted) return

    val executor = remember { ContextCompat.getMainExecutor(ctx) }
    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flash by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableIntStateOf(0) }
    var videoOk by remember { mutableStateOf(true) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
        VideoCapture.withOutput(recorder)
    }
    var active by remember { mutableStateOf<Recording?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    fun outFile(ext: String): File {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        return File(dir, System.currentTimeMillis().toString() + "." + ext)
    }
    fun uriOf(f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    LaunchedEffect(lens, previewView) {
        val view = previewView ?: return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            val bound = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, selector, preview, imageCapture, videoCapture)
            }.isSuccess
            videoOk = bound
            if (!bound) {
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, selector, preview, imageCapture)
                }.onFailure {
                    Toast.makeText(ctx, "相机打不开", Toast.LENGTH_SHORT).show()
                    onCancel()
                }
            }
        }, executor)
    }

    LaunchedEffect(recording) {
        if (!recording) {
            elapsedMs = 0
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (recording) {
            elapsedMs = (System.currentTimeMillis() - start).toInt()
            if (elapsedMs >= 60_000) {
                active?.stop()
                break
            }
            delay(100)
        }
    }

    fun shoot() {
        if (recording) return
        val out = outFile("jpg")
        imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(out).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) { onDone(uriOf(out)) }
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(ctx, "拍照失败", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun toggleVideo() {
        if (!videoOk) {
            Toast.makeText(ctx, "这台手机不能录像", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording) {
            active?.stop()
            return
        }
        val out = outFile("mp4")
        var rec = videoCapture.output.prepareRecording(ctx, FileOutputOptions.Builder(out).build())
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            rec = rec.withAudioEnabled()
        }
        recording = true
        active = rec.start(executor) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = false
                active = null
                if (!ev.hasError() && out.length() > 0) onDone(uriOf(out))
                else if (ev.hasError()) Toast.makeText(ctx, "录像失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = { if (recording) active?.stop() else onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { c -> PreviewView(c).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER; previewView = it } },
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (recording) active?.stop() else onCancel() }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (recording) Text("${elapsedMs / 1000}s", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { flash = !flash }) { Text(if (flash) "闪光开" else "闪光", color = Color.White) }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    if (!recording) lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }) { Text("翻转", color = Color.White) }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(onClick = ::shoot),
                    contentAlignment = Alignment.Center,
                ) {}
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    if (recording) {
                        Canvas(Modifier.size(76.dp)) {
                            drawArc(
                                Color.White,
                                startAngle = -90f,
                                sweepAngle = 360f * (elapsedMs / 60_000f).coerceIn(0f, 1f),
                                useCenter = false,
                                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                    }
                    Box(
                        Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFE53950)).clickable(onClick = ::toggleVideo),
                    )
                }
            }
            Text(
                if (recording) "再点红钮结束，最长 60 秒" else "白钮拍照 · 红钮录像",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            )
        }
    }
}

/** Long-press 「拍摄」: hand the shot to the system camera, then the existing send preview. */
@Composable
internal fun rememberSystemCamera(onShot: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = target
        if (ok && uri != null) onShot(uri)
    }
    return {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "sys-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        target = uri
        runCatching { launcher.launch(uri) }.onFailure {
            Toast.makeText(ctx, "这台手机没有系统相机", Toast.LENGTH_SHORT).show()
        }
    }
}
