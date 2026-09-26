package ink.jvm.chatter.media

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LLMTokenCallback
import com.geniex.sdk.bean.LlmApplyChatTemplateOutput
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmGenerateResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Qwen GGUF through Qualcomm GenieX (llama.cpp + ggml-hexagon).
 * The JNI bridge class is Kotlin-internal, so this file calls it by the method names
 * already published in the AAR. NPU only on Snapdragon chipsets whose Hexagon version
 * is in the 0.7.0 AAR (v73, v75, v79, v81). Anywhere else, and if NPU create throws,
 * the same file runs on CPU. Loaded only after the user picks this weight.
 */
internal object HexagonSummary {
    private const val UNIT_NPU = "npu"
    private const val UNIT_CPU = "cpu"
    private const val RUNTIME = "llama_cpp"

    private val main = Handler(Looper.getMainLooper())
    private var sdkReady = false
    private var npuBroken = false
    private var abandoned = false
    private var llm: Bridge? = null
    private var handle = 0L
    private var openId: String? = null
    private var openUnit: String? = null

    fun release() {
        if (abandoned) return
        val current = llm
        val kept = handle
        llm = null
        handle = 0L
        openId = null
        openUnit = null
        if (current != null && kept != 0L) runCatching { current.destroy(kept) }
    }

    suspend fun generate(
        ctx: Context,
        model: LocalSummary.Option,
        text: String,
        onPartial: (String) -> Unit,
    ): String {
        coroutineContext.ensureActive()
        if (Build.VERSION.SDK_INT < 27) throw IOException("这颗权重需要 Android 8.1 或更新")
        val unit = try {
            open(ctx, model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            release()
            throw IOException("内存不够，关掉别的应用，或换一个更小的模型")
        }
        coroutineContext.ensureActive()
        val engine = llm ?: throw IOException("模型没有打开")
        val kept = handle
        if (kept == 0L) throw IOException("模型没有打开")
        runCatching { engine.reset(kept) }
        val formatted = try {
            engine.applyChatTemplate(
                kept,
                arrayOf(ChatMessage(role = "user", content = text)),
                null,
                false,
                true,
            ).formattedText
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "整理失败", e)
        }
        if (formatted.isBlank()) throw IOException("整理失败")
        LocalSummary.setAccelNote(
            when {
                unit == UNIT_NPU -> " 当前走高通 Hexagon NPU。"
                npuBroken -> " 高通 NPU 没有打开，已改用这颗权重的 CPU。"
                else -> " 这台不是已打包的骁龙 NPU，这颗权重走 CPU。"
            },
        )
        val raw = StringBuilder()
        val alive = AtomicBoolean(true)
        val callback = object : LLMTokenCallback {
            override fun onToken(token: String): Boolean {
                if (!alive.get() || token.isEmpty()) return alive.get()
                val soFar = raw.toString()
                if (token.startsWith(soFar) && token.length > soFar.length) {
                    raw.clear()
                    raw.append(token)
                } else {
                    raw.append(token)
                }
                val shown = LocalSummary.stripThink(raw.toString())
                if (alive.get()) main.post { if (alive.get()) onPartial(shown) }
                return alive.get()
            }

            override fun onComplete(result: LlmGenerateResult) = Unit
        }
        val config = GenerationConfig(
            maxTokens = 512,
            samplerConfig = SamplerConfig(temperature = 0.4f, topP = 0.9f, topK = 40),
        )
        return coroutineScope {
            val gen = async(Dispatchers.IO) {
                engine.generate(kept, formatted, config, callback)
            }
            try {
                gen.await()
                alive.set(false)
                val out = LocalSummary.stripThink(raw.toString()).trim()
                if (out.isEmpty()) throw IOException("没有整理出内容")
                out
            } catch (e: kotlinx.coroutines.CancellationException) {
                alive.set(false)
                withContext(NonCancellable) {
                    runCatching { withContext(Dispatchers.IO) { engine.stopStream(kept) } }
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (gen.isActive && System.nanoTime() < deadline) delay(40)
                    if (gen.isActive) abandon()
                }
                throw e
            }
        }
    }

    private fun abandon() {
        abandoned = true
        llm = null
        handle = 0L
        openId = null
        openUnit = null
    }

    private suspend fun open(ctx: Context, model: LocalSummary.Option): String = withContext(Dispatchers.IO) {
        if (abandoned) throw IOException("上一次生成还没停下来")
        prepare(ctx)
        val unit = if (!npuBroken && snapdragonNpu()) UNIT_NPU else UNIT_CPU
        if (llm != null && handle != 0L && openId == model.id && openUnit == unit) return@withContext unit
        release()
        try {
            create(ctx, model, unit)
            unit
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            release()
            if (unit != UNIT_NPU) {
                if (e is IOException) throw e
                throw IOException(e.message ?: "模型没有打开", e)
            }
            npuBroken = true
            try {
                create(ctx, model, UNIT_CPU)
                UNIT_CPU
            } catch (again: kotlinx.coroutines.CancellationException) {
                throw again
            } catch (again: Throwable) {
                release()
                throw IOException(again.message ?: "模型没有打开", again)
            }
        }
    }

    private fun create(ctx: Context, model: LocalSummary.Option, unit: String) {
        val created = Bridge()
        val kept = created.create(
            LlmCreateInput(
                model_path = File(File(ctx.filesDir, "llm"), model.fileName).absolutePath,
                config = ModelConfig(
                    nCtx = model.context,
                    nThreads = 4,
                    nThreadsBatch = 4,
                    nGpuLayers = if (unit == UNIT_CPU) 0 else -1,
                ),
                runtime_id = RUNTIME,
                compute_unit = unit,
            ),
        )
        if (kept == 0L) throw IOException("模型没有打开")
        llm = created
        handle = kept
        openId = model.id
        openUnit = unit
    }

    private fun prepare(ctx: Context) {
        if (sdkReady) return
        var llamaMissing = false
        GenieXSdk.getInstance().init(ctx, object : GenieXSdk.InitCallback {
            override fun onSuccess() = Unit
            override fun onFailure(message: String) {
                if (message.contains("llama_cpp")) llamaMissing = true
            }
        })
        if (llamaMissing) throw IOException("高通运行库没准备好")
        sdkReady = true
    }

    /** GenieX 0.7.0 ships ggml-htp for v73, v75, v79 and v81 only. */
    private fun snapdragonNpu(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val maker = Build.SOC_MANUFACTURER
        if (!maker.contains("qcom", true) && !maker.contains("qualcomm", true) && !maker.contains("qti", true)) {
            return false
        }
        val soc = Build.SOC_MODEL.uppercase()
        return listOf("SM8550", "SM8635", "SM7675", "SM8650", "SM8750", "SM8850").any { soc.contains(it) }
    }

    /**
     * com.geniex.sdk.jni.Llm is public bytecode and Kotlin-internal, so the compiler
     * rejects a normal call. The method names match that class.
     */
    private class Bridge {
        private val raw = Class.forName("com.geniex.sdk.jni.Llm").getDeclaredConstructor().newInstance()
        private val type = raw.javaClass
        private val createMethod = type.getMethod("create", LlmCreateInput::class.java)
        private val resetMethod = type.getMethod("reset", java.lang.Long.TYPE)
        private val destroyMethod = type.getMethod("destroy", java.lang.Long.TYPE)
        private val stopMethod = type.getMethod("stopStream", java.lang.Long.TYPE)
        private val templateMethod = type.getMethod(
            "applyChatTemplate",
            java.lang.Long.TYPE,
            Array<ChatMessage>::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
        )
        private val generateMethod = type.getMethod(
            "generate",
            java.lang.Long.TYPE,
            String::class.java,
            GenerationConfig::class.java,
            LLMTokenCallback::class.java,
        )

        fun create(input: LlmCreateInput): Long = createMethod.invoke(raw, input) as Long

        fun reset(handle: Long) {
            resetMethod.invoke(raw, handle)
        }

        fun destroy(handle: Long) {
            destroyMethod.invoke(raw, handle)
        }

        fun stopStream(handle: Long) {
            stopMethod.invoke(raw, handle)
        }

        fun applyChatTemplate(
            handle: Long,
            messages: Array<ChatMessage>,
            tools: String?,
            enableThinking: Boolean,
            addGenerationPrompt: Boolean,
        ): LlmApplyChatTemplateOutput = templateMethod.invoke(
            raw,
            handle,
            messages,
            tools,
            enableThinking,
            addGenerationPrompt,
        ) as LlmApplyChatTemplateOutput

        fun generate(handle: Long, prompt: String, config: GenerationConfig, callback: LLMTokenCallback) {
            generateMethod.invoke(raw, handle, prompt, config, callback)
        }
    }
}
