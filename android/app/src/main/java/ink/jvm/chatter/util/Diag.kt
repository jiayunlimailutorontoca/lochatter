package ink.jvm.chatter.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import ink.jvm.chatter.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostics: an in-memory ring of app events (connection, uploads, calls, watchdog) mirrored to a small
 * rolling file, plus a crash handler that writes the stack trace before the process dies.
 * "导出诊断" bundles all of it with the process logcat into one text file for sharing.
 */
object Diag {
    private const val RING = 600
    private const val FILE_MAX = 256 * 1024
    private val ring = ArrayDeque<String>(RING)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var appCtx: Context? = null
    @Volatile private var logFile: File? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        logFile = File(ctx.filesDir, "diag.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(ctx, t, e) }
            previous?.uncaughtException(t, e)
        }
        log("Diag", "process start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} $tag: $msg"
        Log.i(tag, msg)
        synchronized(ring) {
            if (ring.size >= RING) ring.removeFirst()
            ring.addLast(line)
        }
        val f = logFile ?: return
        runCatching {
            if (f.length() > FILE_MAX) {
                // keep the newer half
                val keep = f.readText().let { it.substring(it.length / 2) }
                f.writeText(keep)
            }
            f.appendText(line + "\n")
        }
    }

    fun warn(tag: String, msg: String, e: Throwable? = null) {
        log(tag, "WARN $msg" + (e?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    private fun writeCrash(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val dir = File(ctx.filesDir, "crashes").apply { mkdirs() }
        // keep the 5 newest
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
            "time: ${fmt.format(Date())}\nthread: ${t.name}\nversion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "device: ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n\n$sw\n\n--- recent events ---\n" +
                synchronized(ring) { ring.joinToString("\n") }
        )
    }

    /** Crash files written since the app last acknowledged them. */
    fun pendingCrashes(ctx: Context): List<File> =
        File(ctx.filesDir, "crashes").listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    fun clearCrashes(ctx: Context) {
        File(ctx.filesDir, "crashes").listFiles()?.forEach { it.delete() }
    }

    /** Writes the bundle to the share cache and returns it. */
    fun export(ctx: Context, extra: String = ""): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "lochatter-diag-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        val sb = StringBuilder()
        sb.append("lochatter ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}\n")
        sb.append("exported: ${fmt.format(Date())}\n")
        if (extra.isNotBlank()) sb.append(extra).append('\n')
        sb.append("\n===== crashes =====\n")
        pendingCrashes(ctx).forEach { f -> sb.append("--- ${f.name} ---\n").append(runCatching { f.readText() }.getOrDefault("")).append('\n') }
        sb.append("\n===== app events (file) =====\n")
        logFile?.let { f -> if (f.exists()) sb.append(runCatching { f.readText() }.getOrDefault("")) }
        sb.append("\n===== app events (memory) =====\n")
        synchronized(ring) { ring.forEach { sb.append(it).append('\n') } }
        sb.append("\n===== logcat =====\n")
        sb.append(logcat())
        out.writeText(sb.toString())
        return out
    }

    fun share(ctx: Context, extra: String = "") {
        val f = export(ctx, extra)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "导出诊断信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Our own process log; readable without any permission. */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    }.getOrElse { "logcat unavailable: ${it.message}" }
}
