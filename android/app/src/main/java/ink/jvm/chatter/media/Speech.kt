package ink.jvm.chatter.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Speech-to-text. The phone runs SenseVoice locally ([LocalStt]) unless 设置 → 语音识别 has a cloud
 * address switched on. That address is posted the same way as the chat server's `POST /stt`.
 */
object Speech {
    private const val TAG = "Speech"

    enum class Mode { LOCAL, CLOUD }

    /** Why the last [transcribe] returned null, when it failed rather than hearing silence. */
    @Volatile var lastError: String? = null

    fun mode(repo: ink.jvm.chatter.data.ChatRepository): Mode {
        val url = repo.prefs.cloudSttUrl.trim()
        return if (repo.prefs.cloudStt && url.isNotEmpty()) Mode.CLOUD else Mode.LOCAL
    }

    /**
     * Voice file → text. Null when nothing was recognized; never throws (cancellation aside).
     * [language] is a BCP-47 tag; the cloud endpoint gets the language part (`zh-CN` → `zh`).
     */
    suspend fun transcribe(ctx: Context, repo: ink.jvm.chatter.data.ChatRepository, file: File, language: String = "zh-CN"): String? {
        lastError = null
        return try {
            val text = if (mode(repo) == Mode.CLOUD) {
                repo.api.sttAt(repo.prefs.cloudSttUrl.trim(), file, language.substringBefore('-')).text
            } else {
                LocalStt.transcribe(ctx, file, allowMobile = !repo.prefs.wifiOnlyMedia)
            }
            text.trim().ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "transcribe: ${e.message}")
            lastError = e.message ?: "转文字失败"
            null
        }
    }
}
