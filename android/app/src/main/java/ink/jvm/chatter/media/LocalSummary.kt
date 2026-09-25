package ink.jvm.chatter.media

import android.content.Context
import android.os.StatFs
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
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

/**
 * On-device call summary. Gemma 3 1B int4 through MediaPipe, CPU only.
 * The weight file is downloaded from ModelScope. The transcript never leaves the phone.
 */
object LocalSummary {
    private const val FILE_NAME = "gemma3-1b-it-int4.task"
    private const val MIN_BYTES = 400L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val gate = Mutex()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun ready(ctx: Context): Boolean {
        val f = File(dir(ctx), FILE_NAME)
        return f.isFile && f.length() > MIN_BYTES
    }

    /** Download the weight file. Call this only from the settings download row, never from the summary switch and never from a call. */
    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                if (ready(ctx)) return@withLock
                val free = StatFs(ctx.filesDir.absolutePath).availableBytes
                if (free < 900L * 1024 * 1024) throw IOException("存储空间不够，纪要模型大约需要 900 MB")
                val dest = File(dir(ctx), FILE_NAME)
                val errors = mutableListOf<String>()
                for (url in URLS) {
                    try {
                        download(url, dest)
                        if (ready(ctx)) return@withLock
                        errors += "文件不完整"
                    } catch (e: Exception) {
                        dest.delete()
                        errors += e.message ?: url
                    }
                }
                throw IOException(errors.lastOrNull()?.let { "纪要模型下载失败：$it" } ?: "纪要模型下载失败")
            } finally {
                _status.value = null
            }
        }
    }

    /**
     * Summarize this phone's call captions. The text is not posted anywhere.
     */
    suspend fun summarize(ctx: Context, transcript: String): String {
        if (transcript.isBlank()) throw IOException("没有可整理的内容")
        return complete(ctx, prompt(transcript))
    }

    /** What the two people said in recent text messages. On this phone only. */
    suspend fun summarizeChat(ctx: Context, dialog: String): String {
        if (dialog.isBlank()) throw IOException("没有可总结的消息")
        return complete(ctx, chatPrompt(dialog))
    }

    /** Rewrite [draft] without adding facts. On this phone only. */
    suspend fun polish(ctx: Context, draft: String): String {
        if (draft.isBlank()) throw IOException("没有可润色的内容")
        return complete(ctx, polishPrompt(draft.take(2000)))
    }

    /** Up to three short replies the user could send. On this phone only. */
    suspend fun suggest(ctx: Context, dialog: String): List<String> {
        if (dialog.isBlank()) throw IOException("没有可参考的消息")
        val raw = complete(ctx, suggestPrompt(dialog))
        val lines = raw.lineSequence()
            .map { it.trim().trim('"', '“', '”') }
            .map { it.replace(Regex("^[0-9]+[.、．]\\s*"), "").replace(Regex("^[-•]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
            .toList()
        if (lines.isEmpty()) throw IOException("没有想出回复")
        return lines
    }

    private suspend fun complete(ctx: Context, prompt: String): String = withContext(Dispatchers.Default) {
        val text = prompt.trim()
        if (text.isEmpty()) throw IOException("没有可整理的内容")
        if (!ready(ctx)) throw IOException("纪要模型未就绪")
        gate.withLock {
            val model = File(dir(ctx), FILE_NAME)
            val llm = try {
                LlmInference.createFromOptions(
                    ctx,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(model.absolutePath)
                        .setMaxTokens(1280)
                        .setMaxTopK(40)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build(),
                )
            } catch (e: OutOfMemoryError) {
                throw IOException("内存不够，关掉别的应用再试")
            }
            try {
                val session = LlmInferenceSession.createFromOptions(
                    llm,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(40)
                        .setTopP(0.9f)
                        .setTemperature(0.4f)
                        .build(),
                )
                try {
                    session.addQueryChunk(text)
                    val out = session.generateResponse().trim()
                    if (out.isEmpty()) throw IOException("没有整理出内容")
                    out
                } finally {
                    session.close()
                }
            } catch (e: IOException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw IOException("内存不够，关掉别的应用再试")
            } catch (e: Exception) {
                throw IOException(e.message ?: "整理失败")
            } finally {
                llm.close()
            }
        }
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

    private fun dir(ctx: Context) = File(ctx.filesDir, "gemma3").apply { mkdirs() }

    private fun download(url: String, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "lochatter").build())
        try {
            call.execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw IOException("下载被拒绝（HTTP ${resp.code}）")
                }
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
                            _status.value = if (total > 0) "正在下载纪要模型 ${got * 100 / total}%"
                            else "正在下载纪要模型 ${got / (1024 * 1024)} MB"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (part.length() < MIN_BYTES) {
            part.delete()
            throw IOException("下载不完整")
        }
        if (dest.exists() && !dest.delete()) throw IOException("保存失败")
        if (!part.renameTo(dest)) throw IOException("保存失败")
    }

    private val URLS = listOf(
        "https://www.modelscope.cn/api/v1/models/litert-community/Gemma3-1B-IT/repo?Revision=master&FilePath=gemma3-1b-it-int4.task",
        "https://www.modelscope.cn/models/litert-community/Gemma3-1B-IT/resolve/master/gemma3-1b-it-int4.task",
    )
}
