package ink.jvm.chatter.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Fetches media through the authenticated client (which also decrypts end-to-end encrypted blobs) into the
 * cache, then shares it, saves it to the gallery, or saves it to Downloads.
 */
object MediaSaver {
    private fun ext(mime: String) = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
        "video/mp4" -> "mp4"; "audio/mp4" -> "m4a"; "application/pdf" -> "pdf"
        else -> mime.substringAfter('/', "bin").take(5)
    }

    private fun safeName(m: MediaInfo): String = (m.name ?: (m.id.take(16) + "." + ext(m.mime))).replace('/', '_').replace('\\', '_')

    /** Local copy in the share cache; re-downloaded when the size differs (encrypted blobs report -1 sizes, so those always refresh). */
    suspend fun fetch(ctx: Context, repo: ChatRepository, m: MediaInfo, onProgress: ((Float) -> Unit)? = null): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, m.id.take(24) + "-" + safeName(m))
        if (f.exists() && f.length() > 0 && (m.size <= 0 || f.length() == m.size || repo.e2eOn)) return@withContext f
        val req = Request.Builder().url(repo.api.mediaUrl(m.id)).get().build()
        val tmp = File(dir, f.name + ".part")
        repo.authHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 ${resp.code}")
            val body = resp.body!!
            val total = if (m.size > 0) m.size else body.contentLength()
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                body.byteStream().use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && onProgress != null) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        f
    }

    suspend fun share(ctx: Context, repo: ChatRepository, m: MediaInfo) {
        val f = fetch(ctx, repo, m)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType(m.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens a file with whatever app handles its type. */
    suspend fun open(ctx: Context, repo: ChatRepository, m: MediaInfo) {
        val f = fetch(ctx, repo, m)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(view, "打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Pictures/lochatter (images and videos) via MediaStore; visible in the gallery immediately. */
    suspend fun saveToGallery(ctx: Context, repo: ChatRepository, m: MediaInfo): String {
        val f = fetch(ctx, repo, m)
        val isVideo = m.mime.startsWith("video/")
        val name = f.name.substringAfter('-')
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, m.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/lochatter")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri: Uri = ctx.contentResolver.insert(collection, values) ?: throw IOException("无法写入相册")
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "lochatter").apply { mkdirs() }
                val dst = File(dir, name)
                f.copyTo(dst, overwrite = true)
                @Suppress("DEPRECATION")
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)))
            }
        }
        SavedMedia.markSaved(ctx, m.id)
        return if (isVideo) "已保存到「影片/lochatter」" else "已保存到相册「lochatter」"
    }

    /** Downloads/lochatter for generic files (decrypted copy, real name). */
    suspend fun saveToDownloads(ctx: Context, repo: ChatRepository, m: MediaInfo, onProgress: ((Float) -> Unit)? = null): String {
        val f = fetch(ctx, repo, m, onProgress)
        val name = safeName(m)
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, m.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/lochatter")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("无法写入下载目录")
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "lochatter").apply { mkdirs() }
                f.copyTo(File(dir, name), overwrite = true)
            }
        }
        SavedMedia.markSaved(ctx, m.id)
        return "已保存到「下载/lochatter/$name」"
    }

    /** The cached decrypted copy when [fetch] already ran for this media and it looks complete. */
    fun cachedFile(ctx: Context, m: MediaInfo): File? {
        val f = File(File(ctx.cacheDir, "share"), m.id.take(24) + "-" + safeName(m))
        return f.takeIf { it.exists() && it.length() > 0 && (m.size <= 0 || it.length() == m.size) }
    }

    fun isCached(ctx: Context, m: MediaInfo): Boolean = cachedFile(ctx, m) != null

    /** Saves by type: pictures and videos to the gallery, everything else to Downloads. Returns the toast text. */
    suspend fun save(ctx: Context, repo: ChatRepository, m: MediaInfo, onProgress: ((Float) -> Unit)? = null): String =
        if (m.mime.startsWith("image/") || m.mime.startsWith("video/")) saveToGallery(ctx, repo, m) else saveToDownloads(ctx, repo, m, onProgress)

    /** Bytes used by the share / voice caches and Coil's disk cache. */
    fun cacheBytes(ctx: Context): Long {
        fun size(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return size(File(ctx.cacheDir, "share")) + size(File(ctx.cacheDir, "voice")) + size(File(ctx.cacheDir, "image_cache"))
    }

    fun clearCache(ctx: Context) {
        File(ctx.cacheDir, "share").deleteRecursively()
        File(ctx.cacheDir, "voice").deleteRecursively()
        File(ctx.cacheDir, "image_cache").deleteRecursively()
        runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
    }
}
