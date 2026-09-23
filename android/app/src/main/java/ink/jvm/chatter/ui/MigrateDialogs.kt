package ink.jvm.chatter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import ink.jvm.chatter.crypto.Migration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Old phone: a QR code with the encrypted account bundle plus the 6-digit PIN that unlocks it. */
@Composable
fun MigrateShowDialog(payload: String, onClose: () -> Unit) {
    val pin = remember { Migration.randomPin() }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(payload) {
        withContext(Dispatchers.Default) {
            runCatching {
                val text = Migration.pack(payload, pin)
                val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
                val size = 720
                val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
                val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
                val px = IntArray(m.width * m.height)
                for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                bmp.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
                bmp
            }.onSuccess { bitmap = it }.onFailure { error = if (it is com.google.zxing.WriterException) "内容太长，装不进一个二维码（密钥环太大）。请用「导出密钥」" else "生成失败：${it.message}" }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("迁移到新手机") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val b = bitmap
                if (b != null) Image(b.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(260.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp))
                else Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) { Text(error ?: "生成中…", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(12.dp))
                Text("在新手机的登录页点「换手机？扫旧手机上的二维码」，扫完输入：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(pin.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("谁拍下这个码并知道这串数字，谁就能读你们的聊天。只当面给自己的新手机扫。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

/** New phone: camera scanner, then the PIN; [onResult] gets the decrypted payload JSON. */
@Composable
fun MigrateScanDialog(onResult: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (scanned == null) "扫旧手机上的二维码" else "输入旧手机显示的数字") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    scanned != null -> {
                        OutlinedTextField(
                            value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6); wrong = false },
                            label = { Text("6 位数字") }, singleLine = true, isError = wrong,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        if (wrong) Text("数字不对，再看一眼旧手机", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    denied -> Text("需要相机权限才能扫码", color = MaterialTheme.colorScheme.error)
                    granted -> ScannerView(modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))) { text ->
                        if (text.startsWith(Migration.PREFIX) && scanned == null) scanned = text
                    }
                    else -> Text("正在请求相机权限…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (scanned != null) TextButton(enabled = pin.length == 6, onClick = {
                val plain = Migration.unpack(scanned!!, pin)
                if (plain == null) wrong = true else onResult(plain)
            }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** zxing's embedded scanner view, paused / resumed with the lifecycle. */
@Composable
private fun ScannerView(modifier: Modifier, onDecoded: (String) -> Unit) {
    val ctx = LocalContext.current
    val view = remember {
        DecoratedBarcodeView(ctx).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) { result.text?.let(onDecoded) }
            })
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose { owner.lifecycle.removeObserver(obs); view.pause() }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
