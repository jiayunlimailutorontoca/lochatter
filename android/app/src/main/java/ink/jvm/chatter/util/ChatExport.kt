package ink.jvm.chatter.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ink.jvm.chatter.crypto.E2E
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.StickerRef
import ink.jvm.chatter.ui.callLabel
import ink.jvm.chatter.ui.stripMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export of the local history: a plain-text file (media as placeholders with names), or a zip that also
 * carries the media files plus an HTML page that shows them inline.
 */
object ChatExport {
    suspend fun share(ctx: Context, repo: ChatRepository) {
        val all = repo.allMessages()
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "lochatter-${stamp()}.txt")
        f.writeText(buildText(all, repo))
        send(ctx, f, "text/plain")
    }

    /**
     * `chat.txt` (same text as [share]), `chat.html` and `media/` in one zip. Media goes through [MediaSaver.fetch]
     * (decrypted); a download that fails is noted on the message's line instead. View-once pictures are never
     * included, library stickers (`bqb|…`) stay `[表情]`. [onProgress] is called on the main thread after every message.
     */
    suspend fun shareZip(ctx: Context, repo: ChatRepository, onProgress: (done: Int, total: Int) -> Unit) {
        val all = repo.allMessages()
        val total = all.size
        val zip = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "lochatter-${stamp()}.zip")
            // Every export is a full copy; keep only the newest one in the cache.
            dir.listFiles { f -> f != out && f.name.startsWith("lochatter-") && f.name.endsWith(".zip") }?.forEach { it.delete() }
            val width = total.toString().length
            val packed = mutableMapOf<Int, Packed>()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
                all.forEachIndexed { i, m ->
                    val media = exportMedia(m)
                    if (media != null) {
                        val fetched = runCatching { MediaSaver.fetch(ctx, repo, media) }
                        fetched.onFailure { if (it is CancellationException) throw it }
                        val file = fetched.getOrNull()
                        packed[i] = when {
                            file == null -> Packed(null, "媒体未收入压缩包：${fetched.exceptionOrNull()?.message ?: "下载失败"}")
                            file.length() == 0L -> Packed(null, "媒体未收入压缩包：文件为空")
                            isEncrypted(file) -> Packed(null, "媒体未收入压缩包：无法解密")
                            else -> {
                                val path = "media/" + "%0${width}d-%s".format(Locale.US, i + 1, fileName(media, file))
                                // Pictures, clips and voice notes are already compressed; deflating them again only costs time.
                                zos.setLevel(if (media.mime.substringBefore('/') in COMPRESSED) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                                zos.putNextEntry(ZipEntry(path).apply { time = m.ts })
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                                Packed(path, null)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onProgress(i + 1, total) }
                }
                zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                zos.putNextEntry(ZipEntry("chat.txt"))
                zos.write(buildText(all, repo) { packed[it]?.note }.toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("chat.html"))
                zos.write(buildHtml(all, repo, packed).toByteArray())
                zos.closeEntry()
            }
            out
        }
        send(ctx, zip, "application/zip")
    }

    fun ttlLabel(seconds: Long): String = when {
        seconds <= 0 -> "关闭"
        seconds % 86400 == 0L -> "${seconds / 86400} 天"
        seconds % 3600 == 0L -> "${seconds / 3600} 小时"
        else -> "${seconds / 60} 分钟"
    }

    // ---- text ----

    /** [note] adds a parenthesised remark to the line of message *i* (e.g. why its media is missing from the zip). */
    private fun buildText(all: List<LocalMessage>, repo: ChatRepository, note: (Int) -> String? = { null }): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val names = Names.of(repo)
        val sb = StringBuilder()
        sb.append("lochatter 聊天记录：${names.me} 与 ${names.peer}\n导出时间：${fmt.format(Date())}\n共 ${all.size} 条\n\n")
        all.forEachIndexed { i, m ->
            val quote = m.reply?.let { " ｜回复「${repo.quoteText(it).take(40)}」" } ?: ""
            sb.append("[${fmt.format(Date(m.ts))}] ${names.of(m, repo.me)}: ${bodyOf(m)}$quote")
            if (m.editedAt != null) sb.append("（已编辑）")
            note(i)?.let { sb.append('（').append(it).append('）') }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun bodyOf(m: LocalMessage): String = when (m.kind) {
        "text" -> m.text ?: ""
        "card" -> "[播报] " + stripMarkdown(m.text ?: "")
        "image" -> (if (m.once) "[图片·看一次]" else "[图片]") + (m.text?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        "video" -> (if (m.once) "[视频·看一次]" else "[视频]") + (m.text?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        "audio" -> "[语音 ${(m.media?.durationMs ?: 0) / 1000} 秒]"
        "file" -> "[文件] ${m.media?.name ?: ""}"
        "album" -> if (m.text.isNullOrBlank()) "[相册]" else "[相册] ${m.text}"
        "call" -> "[${callLabel(m.text)}]"
        "ttl" -> "[消息定时销毁：${ttlLabel(m.text?.toLongOrNull() ?: 0)}]"
        "sticker" -> "[表情]"
        "pat" -> "[拍一拍]"
        "location" -> "[位置] ${locationLabel(m)}"
        else -> m.text ?: ""
    }

    /** Address when the fix has one, else "lat,lng"; the raw text when it does not decode. */
    private fun locationLabel(m: LocalMessage): String {
        val fix = locationOf(m.text) ?: return m.text ?: ""
        return fix.address ?: "${fix.lat},${fix.lng}"
    }

    private fun locationUrl(m: LocalMessage): String? = locationOf(m.text)?.let { "https://uri.amap.com/marker?position=${it.lng},${it.lat}" }

    private class LocationFix(val lat: Double, val lng: Double, val address: String?)

    /**
     * Wire text of a `location` message: `lat,lng|accuracyMeters|address|live` (see Locator.encode). Parsed here
     * rather than through Locator so the export does not depend on it; null for anything else (e.g. a locked text).
     */
    private fun locationOf(text: String?): LocationFix? {
        val parts = text?.split('|') ?: return null
        val coords = parts[0].split(',')
        if (coords.size != 2) return null
        val lat = coords[0].trim().toDoubleOrNull() ?: return null
        val lng = coords[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LocationFix(lat, lng, parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
    }

    private class Names(val me: String, val peer: String, val bot: String) {
        fun of(m: LocalMessage, myId: Long): String = when {
            m.from == myId -> me
            m.fromBot -> bot
            else -> peer
        }

        companion object {
            fun of(repo: ChatRepository) = Names(repo.prefs.userName.ifEmpty { "我" }, repo.prefs.peerName.ifEmpty { "对方" }, repo.prefs.botName.ifEmpty { "助手" })
        }
    }

    // ---- html ----

    private fun buildHtml(all: List<LocalMessage>, repo: ChatRepository, packed: Map<Int, Packed>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val names = Names.of(repo)
        val title = esc("lochatter 聊天记录：${names.me} 与 ${names.peer}")
        val sb = StringBuilder(all.size * 160 + CSS.length + 512)
        sb.append("<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
            .append(title).append("</title><style>").append(CSS).append("</style></head><body>\n<h1>").append(title)
            .append("</h1><p class=\"meta\">导出时间：").append(esc(fmt.format(Date()))).append(" · 共 ").append(all.size).append(" 条</p>\n<main>\n")
        all.forEachIndexed { i, m ->
            val who = esc(names.of(m, repo.me))
            val time = esc(fmt.format(Date(m.ts)))
            if (m.isSystem) {
                sb.append("<div class=\"sys\">").append(time).append(" · ").append(who).append(' ').append(esc(bodyOf(m))).append("</div>\n")
                return@forEachIndexed
            }
            val p = packed[i]
            sb.append("<div class=\"m").append(if (m.from == repo.me) " me" else "").append(if (m.fromBot) " bot" else "")
                .append("\"><div class=\"who\">").append(who).append(" · ").append(time).append("</div><div class=\"b\">")
            // `.b` is white-space: pre-wrap, so nothing below may emit a newline inside it.
            m.reply?.let { sb.append("<div class=\"q\">回复 ").append(esc(repo.quoteText(it).take(40))).append("</div>") }
            sb.append(bodyHtml(m, p?.path))
            if (m.editedAt != null) sb.append("<div class=\"n\">（已编辑）</div>")
            p?.note?.let { sb.append("<div class=\"n\">").append(esc(it)).append("</div>") }
            sb.append("</div></div>\n")
        }
        sb.append("</main></body></html>\n")
        return sb.toString()
    }

    /** [path] is the zip-relative media path when the file was packed. */
    private fun bodyHtml(m: LocalMessage, path: String?): String {
        val href = path?.let { Uri.encode(it, "/") }
        val caption = m.text?.takeIf { it.isNotBlank() }?.let { "<div>" + esc(it) + "</div>" } ?: ""
        return when (m.kind) {
            "image" -> if (href != null) "<a href=\"$href\"><img src=\"$href\" alt=\"图片\" loading=\"lazy\"></a>$caption" else esc(bodyOf(m))
            "video" -> if (href != null) "<video controls preload=\"metadata\" src=\"$href\"></video><div><a href=\"$href\" download>下载视频</a></div>$caption" else esc(bodyOf(m))
            "audio", "file" -> if (href != null) "<a href=\"$href\" download>${esc(bodyOf(m))}</a>" else esc(bodyOf(m))
            "sticker" -> if (href != null) "<img class=\"s\" src=\"$href\" alt=\"表情\" loading=\"lazy\">" else esc(bodyOf(m))
            "location" -> locationUrl(m)?.let { "<a href=\"${esc(it)}\" target=\"_blank\" rel=\"noopener\">${esc(bodyOf(m))}</a>" } ?: esc(bodyOf(m))
            else -> esc(bodyOf(m))
        }
    }

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** Rose palette from Theme.kt, light and dark. */
    private const val CSS = """
body{margin:0;padding:16px;background:#FFF5F8;color:#2B1A22;font:15px/1.5 -apple-system,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif}
h1{font-size:18px;margin:0 0 2px}
.meta{color:#8E5C70;font-size:13px;margin:0 0 16px}
main{display:flex;flex-direction:column;max-width:720px;margin:0 auto}
.m{max-width:82%;align-self:flex-start;margin:5px 0}
.m.me{align-self:flex-end}
.m.me .who{text-align:right}
.who{font-size:12px;color:#8E5C70;margin:0 6px 2px}
.b{background:#fff;border-radius:16px;padding:8px 12px;white-space:pre-wrap;word-break:break-word;box-shadow:0 1px 2px rgba(43,26,34,.08)}
.me .b{background:linear-gradient(135deg,#FF7FA9,#EE5C8E);color:#fff}
.bot .b{background:#DDF5E8}
.b img,.b video{display:block;max-width:100%;max-height:360px;border-radius:10px}
.b img.s{max-height:120px}
.q{border-left:3px solid rgba(43,26,34,.25);padding:0 8px;margin-bottom:6px;font-size:12px;opacity:.85}
.me .q{border-color:rgba(255,255,255,.7)}
.n{font-size:12px;opacity:.7;margin-top:4px}
.sys{text-align:center;color:#8E5C70;font-size:12px;margin:10px 0}
a{color:inherit}
@media (prefers-color-scheme:dark){
body{background:#17101A;color:#FFE8EE}
.meta,.who,.sys{color:#DDB2C1}
.b{background:#2B1E26;box-shadow:none}
.me .b{background:linear-gradient(135deg,#C24A7E,#8F2F5F);color:#FFEFF4}
.bot .b{background:#1E4A34}
.q{border-color:rgba(255,232,238,.35)}
}
"""

    // ---- media ----

    /** Outcome for one message's media: the zip path when packed, otherwise why it is missing. */
    private class Packed(val path: String?, val note: String?)

    private val MEDIA_KINDS = setOf("image", "video", "audio", "file")
    private val COMPRESSED = setOf("image", "video", "audio")

    /** What to pack for a message: nothing for text, view-once pictures and library stickers. */
    private fun exportMedia(m: LocalMessage): MediaInfo? = when {
        m.once -> null
        m.kind == "sticker" -> (StickerRef.parse(m.text) as? StickerRef.Media)?.let { ref ->
            m.media?.takeIf { it.id.isNotEmpty() } ?: MediaInfo(ref.mediaId, "image/jpeg", -1L, ref.w, ref.h)
        }
        m.kind in MEDIA_KINDS -> m.media?.takeIf { it.id.isNotEmpty() }
        else -> null
    }

    /** Original name when there is one, else the id plus an extension from the bytes (custom stickers carry no MIME) or the MIME. */
    private fun fileName(m: MediaInfo, f: File): String {
        m.name?.takeIf { it.isNotBlank() }?.let { return safeName(it) }
        return m.id.take(16) + "." + (sniffExt(f) ?: extOf(m.mime))
    }

    private fun safeName(name: String): String {
        val s = name.map { if (it < ' ' || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trimEnd('.')
        if (s.isEmpty()) return "file"
        if (s.length <= 100) return s
        val ext = s.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length <= 8) s.take(100 - ext.length - 1) + "." + ext else s.take(100)
    }

    private fun extOf(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
        "video/mp4" -> "mp4"; "audio/mp4" -> "m4a"; "audio/mpeg" -> "mp3"; "application/pdf" -> "pdf"
        else -> mime.substringAfter('/', "bin").substringBefore(';').take(5).ifEmpty { "bin" }
    }

    private fun head(f: File, n: Int): ByteArray = runCatching {
        val buf = ByteArray(n)
        val read = f.inputStream().use { it.read(buf) }
        if (read == n) buf else buf.copyOf(maxOf(read, 0))
    }.getOrDefault(ByteArray(0))

    private fun sniffExt(f: File): String? {
        val h = head(f, 12)
        fun at(i: Int, c: Char) = i < h.size && h[i] == c.code.toByte()
        return when {
            h.size >= 2 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "jpg"
            at(1, 'P') && at(2, 'N') && at(3, 'G') -> "png"
            at(0, 'G') && at(1, 'I') && at(2, 'F') -> "gif"
            at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') && at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P') -> "webp"
            else -> null
        }
    }

    /** Still an end-to-end encrypted blob: the session key is missing, so the interceptor passed it through untouched. */
    private fun isEncrypted(f: File): Boolean {
        val h = head(f, 4)
        return h.size == 4 && E2E.isEncryptedStream(h)
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun send(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "导出聊天记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
