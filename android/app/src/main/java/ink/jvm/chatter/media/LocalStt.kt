package ink.jvm.chatter.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * On-device SenseVoice (int8) through the sherpa-onnx AAR. The model is downloaded on first use
 * into the app's files directory and dropped from memory after two idle minutes.
 */
object LocalStt {
    private const val MODEL = "model.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val MIN_MODEL_BYTES = 100L * 1024 * 1024
    private const val MAX_SAMPLES = 300 * 16_000
    private const val TARGET_RATE = 16_000
    private val TOKEN_RE = Regex("<\\|[^|]*\\|>")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stt").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val gate = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var generation = 0
    private var idle: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    /** Short Chinese line while a model download, load, or decode is in progress. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun ready(ctx: Context): Boolean {
        val dir = dir(ctx)
        val model = File(dir, MODEL)
        val tokens = File(dir, TOKENS)
        return model.isFile && model.length() > MIN_MODEL_BYTES && tokens.isFile && tokens.length() > 100
    }

    /** Download the model if it is not already on disk. [allowMobile] skips the Wi-Fi-only check. */
    suspend fun ensure(ctx: Context, allowMobile: Boolean) = withContext(worker) {
        gate.withLock {
            try {
                ensureLocked(ctx, allowMobile)
            } finally {
                _status.value = null
            }
        }
    }

    /**
     * Load the recognizer that is already on disk. Does not download and does not touch the network.
     * Call this only after the media path is up, and before any caption copy starts.
     */
    suspend fun start(ctx: Context) = withContext(worker) {
        if (!ready(ctx)) throw IOException("语音识别模型未就绪")
        gate.withLock {
            generation++
            idle?.cancel()
            try {
                recognizerLocked(ctx)
            } finally {
                _status.value = null
                scheduleIdle()
            }
        }
    }

    /**
     * Transcribe 16 kHz mono PCM that is already on this phone.
     * Does not download a model and does not touch the network. Empty audio returns "".
     */
    suspend fun transcribePcm(ctx: Context, samples: FloatArray): String = withContext(worker) {
        if (!ready(ctx)) throw IOException("语音识别模型未就绪")
        if (samples.isEmpty()) return@withContext ""
        gate.withLock {
            generation++
            idle?.cancel()
            try {
                val rec = recognizerLocked(ctx)
                val stream = rec.createStream()
                try {
                    val clipped = if (samples.size > MAX_SAMPLES) samples.copyOf(MAX_SAMPLES) else samples
                    stream.acceptWaveform(clipped, TARGET_RATE)
                    rec.decode(stream)
                    rec.getResult(stream).text.replace(TOKEN_RE, "").trim()
                } finally {
                    stream.release()
                }
            } catch (e: OutOfMemoryError) {
                recognizer?.release()
                recognizer = null
                throw IOException("内存不够，关掉别的应用再试")
            } finally {
                scheduleIdle()
            }
        }
    }

    /** Decode [file] (m4a/aac voice note) and return the transcript. Throws with a Chinese message. */
    suspend fun transcribe(ctx: Context, file: File, allowMobile: Boolean): String = withContext(worker) {
        gate.withLock {
            generation++
            idle?.cancel()
            try {
                ensureLocked(ctx, allowMobile)
                _status.value = "正在识别…"
                val samples = decodeToFloat(file)
                if (samples.isEmpty()) throw IOException("没有听到内容")
                val rec = recognizerLocked(ctx)
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, TARGET_RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.replace(TOKEN_RE, "").trim()
                    if (text.isEmpty()) throw IOException("没有听到内容")
                    text
                } finally {
                    stream.release()
                }
            } catch (e: OutOfMemoryError) {
                recognizer?.release()
                recognizer = null
                throw IOException("内存不够，关掉别的应用再试")
            } finally {
                scheduleIdle()
                _status.value = null
            }
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "sense-voice").apply { mkdirs() }

    private fun ensureLocked(ctx: Context, allowMobile: Boolean) {
        if (ready(ctx)) return
        if (!allowMobile && !onWifi(ctx)) {
            throw IOException("移动数据下不自动下载语音模型。连上 Wi-Fi，或到设置关掉「仅 Wi-Fi 下载原图和文件」，也可以在设置里点下载")
        }
        val free = StatFs(ctx.filesDir.absolutePath).availableBytes
        if (free < 500L * 1024 * 1024) throw IOException("存储空间不够，语音模型大约需要 500 MB")
        val dir = dir(ctx)
        val errors = mutableListOf<String>()
        for (url in FILE_URLS) {
            try {
                download(url.onnx, File(dir, MODEL), "正在下载语音模型")
                download(url.tokens, File(dir, TOKENS), "正在下载词表")
                if (ready(ctx)) return
                errors += "文件不完整"
            } catch (e: Exception) {
                File(dir, MODEL).delete()
                File(dir, TOKENS).delete()
                errors += e.message ?: url.onnx
            }
        }
        for (url in ARCHIVES) {
            val tar = File(dir, "model.tar.bz2")
            try {
                download(url, tar, "正在下载语音模型")
                extract(tar, dir)
                tar.delete()
                if (ready(ctx)) return
                errors += "压缩包里没有模型"
            } catch (e: Exception) {
                errors += e.message ?: url
            } finally {
                tar.delete()
            }
        }
        throw IOException(errors.lastOrNull()?.let { "语音模型下载失败：$it" } ?: "语音模型下载失败")
    }

    private data class Urls(val onnx: String, val tokens: String)

    private val FILE_URLS = listOf(
        Urls(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
        ),
        Urls(
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
        ),
    )

    private val ARCHIVES = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
    )

    private fun download(url: String, dest: File, label: String) {
        val part = File(dest.parentFile, dest.name + ".part")
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "lochatter").build())
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("空响应")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) _status.value = "$label ${got * 100 / total}%"
                            else _status.value = "$label ${got / (1024 * 1024)} MB"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        val min = if (dest.name == TOKENS) 100L else if (dest.name.endsWith(".bz2")) 50L * 1024 * 1024 else MIN_MODEL_BYTES
        if (part.length() < min) {
            part.delete()
            throw IOException("下载不完整")
        }
        if (dest.exists() && !dest.delete()) throw IOException("保存失败")
        if (!part.renameTo(dest)) throw IOException("保存失败")
    }

    private fun extract(tarBz2: File, dest: File) {
        _status.value = "正在解压模型…"
        BZip2CompressorInputStream(tarBz2.inputStream().buffered(1 shl 20)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (name != MODEL && name != TOKENS) continue
                    File(dest, name).outputStream().buffered().use { tar.copyTo(it) }
                }
            }
        }
    }

    private fun recognizerLocked(ctx: Context): OfflineRecognizer {
        recognizer?.let { return it }
        _status.value = "正在加载模型…"
        val dir = dir(ctx)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, MODEL).absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = File(dir, TOKENS).absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        val rec = try {
            OfflineRecognizer(config = config)
        } catch (e: OutOfMemoryError) {
            throw IOException("内存不够，关掉别的应用再试")
        } catch (e: Throwable) {
            File(dir, MODEL).delete()
            throw IOException("模型加载失败，已删掉损坏的文件，下次会重新下载", e)
        }
        recognizer = rec
        return rec
    }

    private fun scheduleIdle() {
        val gen = generation
        idle?.cancel()
        idle = scope.launch {
            delay(120_000)
            gate.withLock {
                if (generation != gen) return@withLock
                recognizer?.release()
                recognizer = null
            }
        }
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** First audio track → 16 kHz mono float samples in -1..1, capped at five minutes. */
    private fun decodeToFloat(file: File): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = FloatArray(MAX_SAMPLES)
        var n = 0
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) throw IOException("没有音频")
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("这个音频解不了")
            val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw IOException("这个音频解不了") }
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outIndex = 0L
            var inBase = 0L

            while (!outputDone && n < MAX_SAMPLES) {
                if (!inputDone) {
                    val ib = c.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = c.getInputBuffer(ib)!!
                        val read = extractor.readSampleData(buf, 0)
                        if (read < 0) {
                            c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ib, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ob = c.dequeueOutputBuffer(info, 10_000)
                when {
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    ob >= 0 -> {
                        val buf = c.getOutputBuffer(ob)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val mono = toMono(buf, channels.coerceAtLeast(1), floatPcm)
                        val chunkEnd = inBase + mono.size
                        while (n < MAX_SAMPLES) {
                            val src = outIndex * rate / TARGET_RATE
                            if (src >= chunkEnd) break
                            out[n++] = mono[(src - inBase).toInt().coerceIn(0, mono.size - 1)]
                            outIndex++
                        }
                        inBase = chunkEnd
                        c.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> if (inputDone && ob == MediaCodec.INFO_TRY_AGAIN_LATER) Thread.sleep(5)
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
        return out.copyOf(n)
    }

    /** Interleaved PCM (16-bit or float) → mono float samples, channels averaged. */
    private fun toMono(buf: ByteBuffer, channels: Int, floatPcm: Boolean): FloatArray {
        if (floatPcm) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / channels
            val outArr = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0f
                for (ch in 0 until channels) acc += fb.get()
                outArr[i] = (acc / channels).coerceIn(-1f, 1f)
            }
            return outArr
        }
        val sb = buf.asShortBuffer()
        val frames = sb.remaining() / channels
        val outArr = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (ch in 0 until channels) acc += sb.get().toInt()
            outArr[i] = (acc / channels / 32768f).coerceIn(-1f, 1f)
        }
        return outArr
    }
}
