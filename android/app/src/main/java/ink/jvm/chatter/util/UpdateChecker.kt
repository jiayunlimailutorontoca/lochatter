package ink.jvm.chatter.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import ink.jvm.chatter.BuildConfig
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.ReleaseInfo
import java.io.File

/** Reads /apk/latest.json (written by deploy/android-build.sh) and installs newer builds via DownloadManager. */
object UpdateChecker {
    private const val TAG = "Update"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private const val PREF = "chatter.update"

    /** @return a newer release, or null. Rate-limited unless [force]. */
    suspend fun check(repo: ChatRepository, force: Boolean): ReleaseInfo? {
        val now = System.currentTimeMillis()
        if (!force && now - repo.prefs.lastUpdateCheck < CHECK_EVERY_MS) return null
        repo.prefs.lastUpdateCheck = now
        val r = runCatching { repo.api.latestRelease() }.getOrElse { Log.w(TAG, "check: ${it.message}"); return null }
        if (r.versionCode <= BuildConfig.VERSION_CODE) return null
        if (!force && r.versionCode == repo.prefs.skippedVersion) return null
        return r
    }

    fun download(ctx: Context, r: ReleaseInfo, token: String?) {
        val dir = File(ctx.getExternalFilesDir(null), "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "lochatter-${r.versionName}.apk"
        val req = DownloadManager.Request(Uri.parse(r.url))
            .setTitle("lochatter ${r.versionName}")
            .setDescription("下载更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, "apk", name)
        token?.let { req.addRequestHeader("Authorization", "Bearer $it") }
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong("id", id).putString("file", File(dir, name).absolutePath).apply()
        ctx.applicationContext.registerReceiver(
            InstallReceiver(), android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0,
        )
        Toast.makeText(ctx, "正在下载更新…", Toast.LENGTH_SHORT).show()
    }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val expected = sp.getLong("id", -1)
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != expected) return
            val f = File(sp.getString("file", "") ?: return)
            runCatching { context.applicationContext.unregisterReceiver(this) }
            if (f.exists() && f.length() > 0) install(context, f) else Toast.makeText(context, "更新下载失败", Toast.LENGTH_LONG).show()
        }
    }
}
