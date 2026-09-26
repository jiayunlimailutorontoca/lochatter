package com.alibaba.mnnllm.android.llm

/** JNI looks this method up by name on the listener instance. Returning true stops generation. */
interface GenerateProgressListener {
    fun onProgress(progress: String?): Boolean
}
