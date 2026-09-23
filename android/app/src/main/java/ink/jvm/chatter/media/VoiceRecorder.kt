package ink.jvm.chatter.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Hold-to-talk recorder: AAC in an .m4a container, 32 kbps mono, capped at 3 minutes by the caller. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null
        private set
    var startedAt = 0L
        private set

    fun start() {
        val f = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(24_000)
        r.setAudioEncodingBitRate(32_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r
        file = f
        startedAt = System.currentTimeMillis()
    }

    /** 0..1 loudness for the recording animation. */
    fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 12000f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** @return the file and duration in ms, or null if the clip was too short to keep. */
    fun stop(): Pair<File, Int>? {
        val r = rec ?: return null
        rec = null
        val dur = (System.currentTimeMillis() - startedAt).toInt()
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val f = file
        file = null
        if (!ok || f == null || dur < 700) {
            f?.delete()
            return null
        }
        return f to dur
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }
}
