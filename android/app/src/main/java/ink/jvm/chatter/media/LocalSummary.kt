package ink.jvm.chatter.media

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import ink.jvm.chatter.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * On-device text help through MNN on the GPU, plus an optional cloud endpoint the user fills in.
 * The three Qwen3 files download from ModelScope. They are not inside the APK.
 * Chat text leaves the phone only when the user has selected the cloud endpoint.
 */
object LocalSummary {
    data class Piece(val name: String, val minBytes: Long)

    data class Option(
        val id: String,
        val title: String,
        val detail: String,
        val downloadHint: String,
        val repo: String,
        val pieces: List<Piece>,
        val minFree: Long,
        val needEightGb: Boolean,
        val cloud: Boolean = false,
    )

    private const val DEFAULT_ID = "qwen3-1.7b"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun local(id: String, title: String, detail: String, hint: String, repo: String, weightMin: Long, minFree: Long, eight: Boolean) =
        Option(
            id = id,
            title = title,
            detail = detail,
            downloadHint = hint,
            repo = repo,
            pieces = listOf(
                Piece("config.json", 100),
                Piece("llm.mnn", 100_000),
                Piece("llm.mnn.weight", weightMin),
                Piece("llm_config.json", 100),
                Piece("tokenizer.txt", 100_000),
            ),
            minFree = minFree,
            needEightGb = eight,
        )

    private val options = listOf(
        local(
            id = "qwen3-0.6b",
            title = "Qwen3 0.6B",
            detail = "最小的一档。约 0.5 GB。走 GPU。只处理文字。",
            hint = "约 0.5 GB。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            repo = "MNN/Qwen3-0.6B-MNN",
            weightMin = 400_000_000L,
            minFree = 800L * 1024 * 1024,
            eight = false,
        ),
        local(
            id = "qwen3-1.7b",
            title = "Qwen3 1.7B",
            detail = "默认。约 1.2 GB。走 GPU。只处理文字。",
            hint = "约 1.2 GB。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            repo = "MNN/Qwen3-1.7B-MNN",
            weightMin = 1_100_000_000L,
            minFree = 2L * 1024 * 1024 * 1024,
            eight = false,
        ),
        local(
            id = "qwen3-4b",
            title = "Qwen3 4B",
            detail = "更大一档。约 2.7 GB。走 GPU。手机内存要有 8 GB。只处理文字。",
            hint = "约 2.7 GB。从魔搭下载。没下好之前，这些功能只提示，不会开始下载。",
            repo = "MNN/Qwen3-4B-MNN",
            weightMin = 2_400_000_000L,
            minFree = 4L * 1024 * 1024 * 1024,
            eight = true,
        ),
        Option(
            id = "cloud",
            title = "云端接口",
            detail = "把要整理的文字发到你填的地址。接口按 OpenAI 的对话格式。",
            downloadHint = "在设置里填写接口地址和模型名。密钥可以留空。",
            repo = "",
            pieces = emptyList(),
            minFree = 0,
            needEightGb = false,
            cloud = true,
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
    private var session: LlmSession? = null
    private var sessionId: String? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _choice = MutableStateFlow(DEFAULT_ID)
    val choice: StateFlow<String> = _choice.asStateFlow()

    private val _accelNote = MutableStateFlow<String?>(null)
    val accelNote: StateFlow<String?> = _accelNote.asStateFlow()

    /** What the summary screen should say while a run has not produced text yet. */
    private val _phase = MutableStateFlow<String?>(null)
    val phase: StateFlow<String?> = _phase.asStateFlow()

    /** True after the selected on-device weight has been opened and is still held. */
    private val _resident = MutableStateFlow(false)
    val resident: StateFlow<Boolean> = _resident.asStateFlow()

    internal fun setAccelNote(text: String?) {
        _accelNote.value = text
    }

    internal fun stripThink(raw: String): String = visible(raw)

    fun options(): List<Option> = options

    fun option(id: String): Option = byId[id] ?: options.first { it.id == DEFAULT_ID }

    fun bind(ctx: Context) {
        val id = canonical(Prefs(ctx).summaryModel)
        if (_choice.value != id) _choice.value = id
    }

    fun current(ctx: Context): Option = option(canonical(Prefs(ctx).summaryModel))

    /** Switch the weight used next time. Does not download and does not call the cloud. */
    fun select(ctx: Context, id: String) {
        val next = canonical(id)
        Prefs(ctx).summaryModel = next
        _choice.value = next
        _accelNote.value = null
        if (sessionId != next && gate.tryLock()) {
            try {
                releaseEngine()
            } finally {
                gate.unlock()
            }
        }
    }

    fun ready(ctx: Context): Boolean {
        val model = current(ctx)
        if (model.cloud) return cloudReady(ctx)
        return model.pieces.all { piece ->
            fileOf(ctx, model, piece.name).let { it.isFile && it.length() >= piece.minBytes }
        }
    }

    /** Sentence for the toast when a summary action cannot start. Null when it can. */
    fun unavailable(ctx: Context): String? {
        if (ready(ctx)) return null
        return if (current(ctx).cloud) "先填写云端接口" else "先下载纪要模型"
    }

    /** Drop a cached engine. Used when the selected weight changes, or a load fails. */
    fun release() {
        if (!gate.tryLock()) return
        try {
            releaseEngine()
        } finally {
            gate.unlock()
        }
    }

    /** Download the selected weight. Call this only from the settings download row. */
    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                val model = current(ctx)
                if (model.cloud) {
                    if (!cloudReady(ctx)) throw IOException("先填写云端接口")
                    return@withLock
                }
                if (ready(ctx)) return@withLock
                val free = StatFs(ctx.filesDir.absolutePath).availableBytes
                if (free < model.minFree) {
                    throw IOException("存储空间不够，${model.title} 大约需要 ${model.minFree / (1024 * 1024)} MB 空闲空间")
                }
                for (piece in model.pieces) {
                    val dest = fileOf(ctx, model, piece.name)
                    if (dest.isFile && dest.length() >= piece.minBytes) continue
                    val errors = mutableListOf<String>()
                    var ok = false
                    for (url in urls(model.repo, piece.name)) {
                        try {
                            download(model, piece.name, url, dest, piece.minBytes)
                            if (dest.isFile && dest.length() >= piece.minBytes) {
                                ok = true
                                break
                            }
                            errors += "文件不完整"
                        } catch (e: Exception) {
                            dest.delete()
                            errors += e.message ?: url
                        }
                    }
                    if (!ok) throw IOException(errors.lastOrNull()?.let { "${model.title} 下载失败：$it" } ?: "${model.title} 下载失败")
                }
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
        withContext(Dispatchers.IO) {
            val text = prompt.trim()
            if (text.isEmpty()) throw IOException("没有可整理的内容")
            val model = current(ctx)
            if (!ready(ctx)) throw IOException(unavailable(ctx) ?: "纪要模型未就绪")
            if (!model.cloud && model.needEightGb && totalRam(ctx) < 7L * 1024 * 1024 * 1024) {
                throw IOException("这台手机内存不到 8 GB，换 Qwen3 1.7B 或 Qwen3 0.6B")
            }
            gate.withLock {
                try {
                    ensureActive()
                    if (model.cloud) {
                        _phase.value = "正在把文字发到云端接口…"
                        return@withLock cloudComplete(ctx, text, onPartial)
                    }
                    val warm = session != null && sessionId == model.id
                    _phase.value = if (warm) "正在整理…" else "正在载入 ${model.title}。这一次会久一些，载好后留在内存里，下次不用再载。"
                    val job = coroutineContext[Job]
                    val engine = engineFor(ctx, model)
                    _phase.value = "正在整理…"
                    ensureActive()
                    runCatching { engine.reset() }
                    val raw = StringBuilder()
                    engine.submit(text, object : GenerateProgressListener {
                        override fun onProgress(progress: String?): Boolean {
                            if (!progress.isNullOrEmpty()) {
                                val soFar = raw.toString()
                                if (progress.startsWith(soFar)) {
                                    raw.clear()
                                    raw.append(progress)
                                } else {
                                    raw.append(progress)
                                }
                                val shown = visible(raw.toString())
                                if (job?.isActive != false) main.post { if (job?.isActive != false) onPartial(shown) }
                            }
                            return job?.isActive == false
                        }
                    })
                    if (job?.isActive == false) throw CancellationException()
                    _accelNote.value = " 当前走 GPU。"
                    val out = visible(raw.toString()).trim()
                    if (out.isEmpty()) throw IOException("没有整理出内容")
                    out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    releaseEngine()
                    throw IOException("内存不够，关掉别的应用，或换一个更小的模型")
                } catch (e: Exception) {
                    releaseEngine()
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "GPU 没有打开"
                    throw IOException(msg)
                } finally {
                    _phase.value = null
                }
            }
        }

    private fun engineFor(ctx: Context, model: Option): LlmSession {
        val cached = session
        if (cached != null && sessionId == model.id) return cached
        releaseEngine()
        val folder = dir(ctx, model)
        val config = gpuConfig(folder)
        val extra = JSONObject()
            .put("is_r1", false)
            .put("mmap_dir", File(folder, "mmap").apply { mkdirs() }.absolutePath)
            .put("keep_history", false)
            .toString()
        val created = LlmSession()
        try {
            created.open(config.absolutePath, config.readText(), extra)
        } catch (e: Throwable) {
            created.close()
            if (e is IOException) throw e
            throw IOException(e.message?.takeIf { it.isNotBlank() } ?: "GPU 没有打开", e)
        }
        session = created
        sessionId = model.id
        _resident.value = true
        return created
    }

    private fun gpuConfig(folder: File): File {
        val raw = JSONObject(File(folder, "config.json").readText())
        raw.put("backend_type", "opencl")
        raw.put("max_new_tokens", 512)
        raw.put("enable_thinking", false)
        raw.put("temperature", 0.4)
        raw.put("topK", 40)
        raw.put("topP", 0.9)
        val out = File(folder, "config.gpu.json")
        out.writeText(raw.toString())
        return out
    }

    private suspend fun cloudComplete(ctx: Context, text: String, onPartial: (String) -> Unit): String {
        val prefs = Prefs(ctx)
        val url = endpoint(prefs.cloudLlmUrl)
        val modelName = prefs.cloudLlmModel.trim()
        val body = JSONObject()
            .put("model", modelName)
            .put("temperature", 0.4)
            .put("max_tokens", 512)
            .put("stream", true)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", text)))
            .toString()
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply {
                val key = prefs.cloudLlmKey.trim()
                if (key.isNotEmpty()) header("Authorization", "Bearer $key")
            }
            .post(body.toRequestBody(jsonType))
            .build()
        val call = http.newCall(req)
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        val raw = StringBuilder()
        try {
            call.execute().use { resp ->
                val payload = resp.body ?: throw IOException("空响应")
                if (!resp.isSuccessful) {
                    val err = payload.string().take(300)
                    throw IOException(cloudError(resp.code, err))
                }
                payload.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    val first = StringBuilder()
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine() ?: break
                        if (first.isEmpty() && line.isBlank()) continue
                        if (first.isEmpty() && !line.startsWith("data:")) {
                            first.append(line)
                            val rest = reader.readText()
                            val whole = first.toString() + rest
                            raw.append(messageContent(whole))
                            break
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val piece = runCatching { deltaContent(data) }.getOrDefault("")
                        if (piece.isEmpty()) continue
                        raw.append(piece)
                        val shown = visible(raw.toString())
                        main.post { onPartial(shown) }
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            call.cancel()
            throw e
        } catch (e: Exception) {
            call.cancel()
            throw IOException(e.message ?: "云端接口失败")
        }
        _accelNote.value = " 当前走云端接口。"
        val out = visible(raw.toString()).trim()
        if (out.isEmpty()) throw IOException("没有整理出内容")
        return out
    }

    private fun cloudReady(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        return prefs.cloudLlmUrl.trim().startsWith("http") && prefs.cloudLlmModel.trim().isNotEmpty()
    }

    private fun endpoint(raw: String): String {
        val t = raw.trim().trimEnd('/')
        if (t.endsWith("/chat/completions")) return t
        if (t.endsWith("/v1")) return "$t/chat/completions"
        return "$t/v1/chat/completions"
    }

    private fun deltaContent(data: String): String {
        val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta")
        if (delta != null) return delta.optString("content", "")
        return choice.optJSONObject("message")?.optString("content", "") ?: ""
    }

    private fun messageContent(whole: String): String {
        val root = runCatching { JSONObject(whole) }.getOrNull() ?: return whole
        if (root.has("error")) throw IOException(cloudError(0, whole))
        return deltaContent(whole).ifEmpty {
            root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
        }
    }

    private fun cloudError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        val text = msg?.takeIf { it.isNotBlank() } ?: "云端接口失败"
        return if (code > 0) "$text（HTTP $code）" else text
    }

    private fun releaseEngine() {
        val current = session
        session = null
        sessionId = null
        _resident.value = false
        current?.close()
    }

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

    private fun dir(ctx: Context, model: Option) = File(File(ctx.filesDir, "llm"), model.id).apply { mkdirs() }

    private fun fileOf(ctx: Context, model: Option, name: String) = File(dir(ctx, model), name)

    private fun urls(repo: String, name: String) = listOf(
        "https://www.modelscope.cn/api/v1/models/$repo/repo?Revision=master&FilePath=$name",
        "https://www.modelscope.cn/models/$repo/resolve/master/$name",
    )

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

    private fun download(model: Option, name: String, url: String, dest: File, minBytes: Long) {
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
                            else "正在下载 ${model.title} ${name} ${got / (1024 * 1024)} MB"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (part.length() < minBytes) {
            part.delete()
            throw IOException("下载不完整")
        }
        if (dest.exists() && !dest.delete()) throw IOException("保存失败")
        if (!part.renameTo(dest)) throw IOException("保存失败")
    }
}
