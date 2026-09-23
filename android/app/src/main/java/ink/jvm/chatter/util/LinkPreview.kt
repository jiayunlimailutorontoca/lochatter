package ink.jvm.chatter.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What a page says about itself (Open Graph / Twitter cards / plain `<title>`). */
@Serializable
data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val image: String?,
    val site: String?,
    val fetchedAt: Long,
)

/**
 * Fetches and caches link previews. Never throws: any failure is a null preview, and nulls are remembered for an
 * hour so a dead link is not re-fetched on every recomposition. Memory (200 entries) in front of a JSON file cache.
 */
object LinkPreviews {
    private const val MAX_BYTES = 512 * 1024
    private const val TTL_OK = 7L * 24 * 3600 * 1000
    private const val TTL_MISS = 3600L * 1000
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    /** Characters a URL never contains unencoded; CJK punctuation ends a link glued to a sentence. */
    private const val URL_STOP = "<>\"'，。、；：！？（）【】《》「」"

    /** Cache line; [preview] null = known miss. */
    @Serializable
    private class Entry(val at: Long, val preview: LinkPreview?)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 200
    }

    suspend fun fetch(ctx: Context, http: OkHttpClient, url: String): LinkPreview? = withContext(Dispatchers.IO) {
        runCatching { fetchOrCached(ctx, http, url) }.getOrNull()
    }

    /** First http(s) URL in free text, without the punctuation a sentence hangs on it. */
    fun firstUrl(text: String): String? {
        var at = 0
        while (true) {
            val h = text.indexOf("http", at)
            if (h < 0) return null
            at = h + 4
            if (h > 0 && text[h - 1].isLetterOrDigit()) continue
            if (!text.startsWith("http://", h) && !text.startsWith("https://", h)) continue
            var e = h
            while (e < text.length && !text[e].isWhitespace() && text[e] !in URL_STOP) e++
            while (e > h && text[e - 1] in ")]}>,;'\"") e--
            val u = text.substring(h, e)
            if (u.length > 8 && u.toHttpUrlOrNull() != null) return u
        }
    }

    fun clearCache(ctx: Context) {
        synchronized(memory) { memory.clear() }
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "linkpreview").apply { mkdirs() }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fresh(e: Entry, now: Long): Boolean = now - e.at < if (e.preview != null) TTL_OK else TTL_MISS

    private fun fetchOrCached(ctx: Context, http: OkHttpClient, url: String): LinkPreview? {
        val now = System.currentTimeMillis()
        val k = key(url)
        synchronized(memory) { memory[k] }?.let { if (fresh(it, now)) return it.preview }
        val file = File(dir(ctx), "$k.json")
        val onDisk = runCatching { if (file.exists()) json.decodeFromString(Entry.serializer(), file.readText()) else null }.getOrNull()
        if (onDisk != null && fresh(onDisk, now)) {
            synchronized(memory) { memory[k] = onDisk }
            return onDisk.preview
        }
        val preview = runCatching { download(http, url) }.getOrNull()
        val entry = Entry(now, preview)
        synchronized(memory) { memory[k] = entry }
        runCatching { file.writeText(json.encodeToString(Entry.serializer(), entry)) }
        return preview
    }

    private fun download(http: OkHttpClient, url: String): LinkPreview? {
        val target = url.toHttpUrlOrNull() ?: return null
        val client = http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
        val req = Request.Builder().url(target)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val ctype = body.contentType()
            val mime = ctype?.let { "${it.type}/${it.subtype}" }?.lowercase() ?: resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (mime != "text/html" && mime != "application/xhtml+xml") return null
            val bytes = body.byteStream().use { inp ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = inp.read(buf, 0, minOf(buf.size, MAX_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val finalUrl = resp.request.url
            val html = String(bytes, charsetOf(bytes, ctype?.charset()))
            return parse(finalUrl.toString(), html)
        }
    }

    private fun charsetOf(bytes: ByteArray, declared: Charset?): Charset {
        if (declared != null) return declared
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val m = META_CHARSET.find(head) ?: META_CONTENT_CHARSET.find(head)
        val name = m?.groupValues?.getOrNull(1)?.trim()
        return runCatching { if (name.isNullOrEmpty()) null else Charset.forName(name) }.getOrNull() ?: Charsets.UTF_8
    }

    private val META_CHARSET = Regex("""<meta[^>]*\bcharset\s*=\s*["']?\s*([\w-]+)""", RegexOption.IGNORE_CASE)
    private val META_CONTENT_CHARSET = Regex("""<meta[^>]*\bcontent\s*=\s*["'][^"']*charset=([\w-]+)""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
    private val META = Regex("""<meta\s+([^>]*)>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]{1,6}|#\d{1,7}|[a-zA-Z]{1,10});""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "copy" to "©", "reg" to "®", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "bull" to "•", "trade" to "™")

    internal fun parse(finalUrl: String, html: String): LinkPreview? {
        val headEnd = html.indexOf("</head>", ignoreCase = true).let { if (it < 0) html.length else it }
        val head = html.substring(0, headEnd)
        val meta = HashMap<String, String>()
        for (m in META.findAll(head)) {
            val attrs = HashMap<String, String>()
            for (a in ATTR.findAll(m.groupValues[1])) attrs[a.groupValues[1].lowercase()] = a.groupValues[2].ifEmpty { a.groupValues[3].ifEmpty { a.groupValues[4] } }
            val name = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase() ?: continue
            val content = attrs["content"] ?: continue
            if (name !in meta && content.isNotBlank()) meta[name] = content
        }
        fun pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { meta[it] }?.let { clean(it) }?.takeIf { it.isNotEmpty() }
        val title = pick("og:title", "twitter:title") ?: TITLE.find(head)?.groupValues?.get(1)?.let { clean(it) }?.takeIf { it.isNotEmpty() }
        val description = pick("og:description", "twitter:description", "description")
        val image = pick("og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src")?.let { resolve(finalUrl, it) }
        val site = pick("og:site_name", "twitter:site")?.trimStart('@')?.takeIf { it.isNotEmpty() }
        if (title == null && description == null && image == null) return null
        return LinkPreview(finalUrl, title?.take(300), description?.take(600), image, site?.take(100), System.currentTimeMillis())
    }

    private fun resolve(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.startsWith("data:")) return null
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        val b = base.toHttpUrlOrNull() ?: return null
        return runCatching { b.resolve(r)?.toString() }.getOrNull()
    }

    private val WS = Regex("""\s+""")
    private fun clean(s: String): String = unescape(s).replace(WS, " ").trim()

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[e] ?: m.value
            }
        }
    }
}
