package ink.jvm.chatter.media

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import ink.jvm.chatter.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device text help. The three LiteRT-LM Qwen files stay on CPU.
 * The GGUF option goes through HexagonSummary (Qualcomm GenieX).
 * Weights download from ModelScope. Chat text never leaves the phone.
 */
object LocalSummary {
    data class Option(
        val id: String,
        val title: String,
        val detail: String,
        val downloadHint: String,
        val fileName: String,
        val minBytes: Long,
        val minFree: Long,
        val context: Int,
        val needEightGb: Boolean,
        val urls: List<String>,
        val geniex: Boolean = false,
    )

    private const val DEFAULT_ID = "qwen35-4b"

    private val options = listOf(
        Option(
            id = "qwen35-4b",
            title = "Qwen3.5 4B",
            detail = "默认。没有正好 3B 的端侧文件，这是最接近的一档。约 2.6 GB，手机内存要有 8 GB。只处理文字。",
            downloadHint = "约 2.6 GB，另外还要留出大约 3 GB 给第一次运行的缓存。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            fileName = "Qwen3.5-4B_mixed_int4.litertlm",
            minBytes = 2_200_000_000L,
            minFree = 6L * 1024 * 1024 * 1024,
            context = 4096,
            needEightGb = true,
            urls = listOf(
                "https://www.modelscope.cn/api/v1/models/litert-community/Qwen3.5-4B/repo?Revision=master&FilePath=Qwen3.5-4B_mixed_int4.litertlm",
                "https://www.modelscope.cn/models/litert-community/Qwen3.5-4B/resolve/master/Qwen3.5-4B_mixed_int4.litertlm",
            ),
        ),
        Option(
            id = "qwen35-2b-npu",
            title = "Qwen3.5 2B · 高通 NPU",
            detail = "GGUF Q4_0，约 1.2 GB。骁龙 8 Gen 2、8 Gen 3、8 Elite 走 Hexagon NPU，打不开就改用这颗的 CPU。华为麒麟和联发科天玑没有能放进安装包的 NPU 库，选这颗也只走 CPU。只处理文字。",
            downloadHint = "约 1.2 GB。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            fileName = "Qwen3.5-2B-Q4_0.gguf",
            minBytes = 1_100_000_000L,
            minFree = 2_500_000_000L,
            context = 4096,
            needEightGb = false,
            urls = listOf(
                "https://www.modelscope.cn/api/v1/models/unsloth/Qwen3.5-2B-GGUF/repo?Revision=master&FilePath=Qwen3.5-2B-Q4_0.gguf",
                "https://www.modelscope.cn/models/unsloth/Qwen3.5-2B-GGUF/resolve/master/Qwen3.5-2B-Q4_0.gguf",
            ),
            geniex = true,
        ),
        Option(
            id = "qwen35-2b",
            title = "Qwen3.5 2B",
            detail = "更小一档。约 2.0 GB。只处理文字。",
            downloadHint = "约 2.0 GB，第一次运行还要一些缓存空间。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            fileName = "Qwen3.5-2B_int8.litertlm",
            minBytes = 1_600_000_000L,
            minFree = 4_500_000_000L,
            context = 4096,
            needEightGb = false,
            urls = listOf(
                "https://www.modelscope.cn/api/v1/models/litert-community/Qwen3.5-2B/repo?Revision=master&FilePath=Qwen3.5-2B_int8.litertlm",
                "https://www.modelscope.cn/models/litert-community/Qwen3.5-2B/resolve/master/Qwen3.5-2B_int8.litertlm",
            ),
        ),
        Option(
            id = "qwen3-1.7b",
            title = "Qwen3 1.7B",
            detail = "更轻，约 1 GB。内存紧张时用这个。只处理文字。",
            downloadHint = "约 1 GB。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            minBytes = 700_000_000L,
            minFree = 2_200_000_000L,
            context = 4096,
            needEightGb = false,
            urls = listOf(
                "https://www.modelscope.cn/api/v1/models/litert-community/Qwen3-1.7B/repo?Revision=master&FilePath=Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
                "https://www.modelscope.cn/models/litert-community/Qwen3-1.7B/resolve/master/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            ),
        ),
    )

    private val byId = options.associateBy { it.id }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val gate = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var engineId: String? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _choice = MutableStateFlow(DEFAULT_ID)
    val choice: StateFlow<String> = _choice.asStateFlow()

    private val _accelNote = MutableStateFlow<String?>(null)
    val accelNote: StateFlow<String?> = _accelNote.asStateFlow()

    internal fun setAccelNote(text: String?) {
        _accelNote.value = text
    }

    internal fun stripThink(raw: String): String = visible(raw)

    fun options(): List<Option> = options

    fun option(id: String): Option = byId[id] ?: options.first()

    fun bind(ctx: Context) {
        val id = canonical(Prefs(ctx).summaryModel)
        if (_choice.value != id) _choice.value = id
    }

    fun current(ctx: Context): Option = option(canonical(Prefs(ctx).summaryModel))

    /** Switch the weight file used next time. Does not download. */
    fun select(ctx: Context, id: String) {
        val next = canonical(id)
        Prefs(ctx).summaryModel = next
        _choice.value = next
        _accelNote.value = null
        if ((engineId != next || nextOptionIsGeniex(next)) && gate.tryLock()) {
            try {
                releaseEngine()
            } finally {
                gate.unlock()
            }
        }
    }

    fun ready(ctx: Context): Boolean = fileOf(ctx, current(ctx)).let { it.isFile && it.length() > current(ctx).minBytes }

    /** Drop a cached engine. Safe to call when the system wants memory back. */
    fun release() {
        if (!gate.tryLock()) return
        try {
            releaseEngine()
        } finally {
            gate.unlock()
        }
    }

    /** Download the selected weight file. Call this only from the settings download row, never from a summary action and never from a call. */
    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                val model = current(ctx)
                if (ready(ctx)) return@withLock
                val free = StatFs(ctx.filesDir.absolutePath).availableBytes
                if (free < model.minFree) {
                    throw IOException("存储空间不够，${model.title} 大约需要 ${model.minFree / (1024 * 1024)} MB 空闲空间")
                }
                val dest = fileOf(ctx, model)
                val errors = mutableListOf<String>()
                for (url in model.urls) {
                    try {
                        download(model, url, dest)
                        if (dest.isFile && dest.length() > model.minBytes) return@withLock
                        errors += "文件不完整"
                    } catch (e: Exception) {
                        dest.delete()
                        errors += e.message ?: url
                    }
                }
                throw IOException(errors.lastOrNull()?.let { "${model.title} 下载失败：$it" } ?: "${model.title} 下载失败")
            } finally {
                _status.value = null
            }
        }
    }

    suspend fun summarize(ctx: Context, transcript: String, onPartial: (String) -> Unit = {}): String {
        if (transcript.isBlank()) throw IOException("没有可整理的内容")
        return complete(ctx, prompt(transcript), onPartial)
    }

    suspend fun summarizeChat(ctx: Context, dialog: String, onPartial: (String) -> Unit = {}): String {
        if (dialog.isBlank()) throw IOException("没有可总结的消息")
        return complete(ctx, chatPrompt(dialog), onPartial)
    }

    suspend fun polish(ctx: Context, draft: String, onPartial: (String) -> Unit = {}): String {
        if (draft.isBlank()) throw IOException("没有可润色的内容")
        return complete(ctx, polishPrompt(draft.take(2000)), onPartial)
    }

    suspend fun suggest(ctx: Context, dialog: String, onPartial: (String) -> Unit = {}): List<String> {
        if (dialog.isBlank()) throw IOException("没有可参考的消息")
        val raw = complete(ctx, suggestPrompt(dialog), onPartial)
        val lines = replyLines(raw)
        if (lines.isEmpty()) throw IOException("没有想出回复")
        return lines
    }

    fun replyLines(raw: String): List<String> = raw.lineSequence()
        .map { it.trim().trim('"', '“', '”') }
        .map { it.replace(Regex("^[0-9]+[.、．]\\s*"), "").replace(Regex("^[-•]\\s*"), "").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(3)
        .toList()

    private suspend fun complete(ctx: Context, prompt: String, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.Default) {
            val text = prompt.trim()
            if (text.isEmpty()) throw IOException("没有可整理的内容")
            val model = current(ctx)
            if (!ready(ctx)) throw IOException("纪要模型未就绪")
            if (model.needEightGb && totalRam(ctx) < 7L * 1024 * 1024 * 1024) {
                throw IOException("这台手机内存不到 8 GB，换 Qwen3.5 2B 或 Qwen3 1.7B")
            }
            gate.withLock {
                ensureActive()
                if (model.geniex) {
                    releaseLitert()
                    return@withLock HexagonSummary.generate(ctx, model, text, onPartial)
                }
                _accelNote.value = null
                HexagonSummary.release()
                val llm = try {
                    engineFor(ctx, model)
                } catch (e: OutOfMemoryError) {
                    releaseEngine()
                    throw IOException("内存不够，关掉别的应用，或换一个更小的模型")
                }
                ensureActive()
                val conversation = llm.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4),
                        channels = emptyList(),
                        maxOutputToken = 512,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    ),
                )
                try {
                    val deferred = CompletableDeferred<String>()
                    val raw = StringBuilder()
                    val alive = AtomicBoolean(true)
                    fun publish() {
                        val shown = visible(raw.toString())
                        if (alive.get()) main.post { if (alive.get()) onPartial(shown) }
                    }
                    conversation.sendMessageAsync(
                        text,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val chunk = message.toString()
                                val soFar = raw.toString()
                                raw.clear()
                                raw.append(if (chunk.startsWith(soFar)) chunk else soFar + chunk)
                                publish()
                            }

                            override fun onDone() {
                                if (!deferred.isCompleted) deferred.complete(visible(raw.toString()).trim())
                            }

                            override fun onError(throwable: Throwable) {
                                if (throwable is kotlinx.coroutines.CancellationException) {
                                    if (!deferred.isCompleted) deferred.complete(visible(raw.toString()).trim())
                                } else if (!deferred.isCompleted) {
                                    deferred.completeExceptionally(IOException(throwable.message ?: "整理失败", throwable))
                                }
                            }
                        },
                        maxOutputToken = 512,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    )
                    try {
                        val out = deferred.await()
                        alive.set(false)
                        if (out.isEmpty()) throw IOException("没有整理出内容")
                        out
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        alive.set(false)
                        withContext(NonCancellable) {
                            runCatching { conversation.cancelProcess() }
                            val deadline = System.nanoTime() + 2_000_000_000L
                            while (!deferred.isCompleted && System.nanoTime() < deadline) delay(40)
                        }
                        throw e
                    }
                } catch (e: IOException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    releaseEngine()
                    throw IOException("内存不够，关掉别的应用，或换一个更小的模型")
                } catch (e: Exception) {
                    throw IOException(e.message ?: "整理失败")
                } finally {
                    withContext(NonCancellable) {
                        runCatching { conversation.close() }
                        if (engineId != current(ctx).id) releaseEngine()
                    }
                }
            }
        }

    private fun engineFor(ctx: Context, model: Option): Engine {
        val cached = engine
        if (cached != null && engineId == model.id) return cached
        releaseEngine()
        val created = Engine(
            EngineConfig(
                modelPath = fileOf(ctx, model).absolutePath,
                backend = Backend.CPU(threadCount = 4),
                maxNumTokens = model.context,
                cacheDir = File(dir(ctx), "cache-${model.id}").apply { mkdirs() }.absolutePath,
            ),
        )
        try {
            created.initialize()
        } catch (e: Throwable) {
            runCatching { created.close() }
            throw e
        }
        engine = created
        engineId = model.id
        return created
    }

    private fun releaseEngine() {
        releaseLitert()
        HexagonSummary.release()
    }

    private fun releaseLitert() {
        val current = engine
        engine = null
        engineId = null
        if (current != null) runCatching { current.close() }
    }

    private fun nextOptionIsGeniex(id: String) = byId[id]?.geniex == true

    private fun prompt(transcript: String): String = """
        下面是这台手机麦克风在通话里听到的话，每行开头是时间，只有这一方，没有对方。请用简体中文写一段简短纪要，一百五十字以内。不要编造没有出现的内容，不要写成双方对话。内容很少就概括那一两句。只输出纪要。

        $transcript
    """.trimIndent()

    private fun chatPrompt(dialog: String): String = """
        下面是两人最近的文字消息，每行以说话人开头。用简体中文概括双方聊了什么，一百五十字以内。不要编造。只输出概括。

        $dialog
    """.trimIndent()

    private fun polishPrompt(draft: String): String = """
        把下面的话润色成自然的简体中文，意思不变，不要增加内容，不要解释。只输出润色后的文字。

        $draft
    """.trimIndent()

    private fun suggestPrompt(dialog: String): String = """
        根据下面的对话，给「我」写三条可以发出去的短回复。每条单独一行，不要编号，不要解释。

        $dialog
    """.trimIndent()

    private fun dir(ctx: Context) = File(ctx.filesDir, "llm").apply { mkdirs() }

    private fun fileOf(ctx: Context, model: Option) = File(dir(ctx), model.fileName)

    private fun canonical(id: String) = if (byId.containsKey(id)) id else DEFAULT_ID

    private fun totalRam(ctx: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }

    private fun visible(raw: String): String {
        val closed = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val open = closed.indexOf("<think>")
        return if (open >= 0) closed.substring(0, open) else closed
    }

    private fun download(model: Option, url: String, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "lochatter").build())
        try {
            call.execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw IOException("下载被拒绝（HTTP ${resp.code}）")
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
                            _status.value = if (total > 0) "正在下载 ${model.title} ${got * 100 / total}%"
                            else "正在下载 ${model.title} ${got / (1024 * 1024)} MB"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (part.length() < model.minBytes) {
            part.delete()
            throw IOException("下载不完整")
        }
        if (dest.exists() && !dest.delete()) throw IOException("保存失败")
        if (!part.renameTo(dest)) throw IOException("保存失败")
    }
}
