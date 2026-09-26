package com.alibaba.mnnllm.android.llm

/**
 * Thin handle for libmnnllmapp.so. The native symbols are registered on this class name.
 */
internal class LlmSession {
    private var ptr = 0L

    fun open(configPath: String, mergedConfig: String, extraConfig: String) {
        ensureLoaded()
        ptr = initNative(configPath, null, mergedConfig, extraConfig)
        if (ptr == 0L) throw IllegalStateException("GPU 没有打开")
    }

    fun reset() {
        if (ptr != 0L) resetNative(ptr)
    }

    fun submit(prompt: String, listener: GenerateProgressListener): HashMap<String, Any> {
        if (ptr == 0L) throw IllegalStateException("GPU 没有打开")
        return submitNative(ptr, prompt, false, listener)
    }

    fun close() {
        val kept = ptr
        ptr = 0L
        if (kept != 0L) runCatching { releaseNative(kept) }
    }

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?,
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener,
    ): HashMap<String, Any>

    private external fun resetNative(instanceId: Long)

    private external fun releaseNative(instanceId: Long)

    companion object {
        private var loaded = false

        fun ensureLoaded() {
            if (loaded) return
            System.loadLibrary("MNN")
            System.loadLibrary("mnnllmapp")
            loaded = true
        }
    }
}
