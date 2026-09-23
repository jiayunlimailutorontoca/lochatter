package ink.jvm.chatter.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** One entry of `/stickers/catalog/search.json` (docs/protocol-1.3.md, 表情库静态资源). Unknown keys are ignored. */
@Serializable
data class StickerItem(
    val id: String,
    val name: String = "",
    val label: String = "",
    val src: String,
    val thumb: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val animated: Boolean = false,
    val bytes: Long = 0,
    val category: String = "",
    val categoryTitle: String = "",
    val folder: String = "",
)

/** One entry of `/stickers/catalog/index.json` → `categories[]`. */
@Serializable
data class StickerCategory(
    val slug: String,
    val title: String,
    val count: Int = 0,
    val cover: StickerItem? = null,
)

@Serializable
private data class StickerIndex(val categories: List<StickerCategory> = emptyList())

/**
 * What a `kind:"sticker"` message carries in its text: a library sticker (`bqb|<path>|<w>|<h>`)
 * or a custom one uploaded as media (`media|<id>|<w>|<h>`).
 */
sealed class StickerRef {
    abstract val w: Int
    abstract val h: Int

    data class Bqb(val path: String, override val w: Int, override val h: Int) : StickerRef()
    data class Media(val mediaId: String, override val w: Int, override val h: Int) : StickerRef()

    /** Wire text, see docs/protocol-1.3.md "msg.send 新 kind". */
    fun encode(): String = when (this) {
        is Bqb -> "bqb|$path|$w|$h"
        is Media -> "media|$mediaId|$w|$h"
    }

    /** Full-size image. */
    fun url(serverUrl: String): String = when (this) {
        is Bqb -> "${serverUrl.trimEnd('/')}/stickers/$path"
        is Media -> "${serverUrl.trimEnd('/')}/media/$mediaId"
    }

    /** Grid thumbnail: the library ships a webp under `thumbs/` for every `media/` file; custom stickers have none. */
    fun thumbUrl(serverUrl: String): String = when (this) {
        is Bqb -> "${serverUrl.trimEnd('/')}/stickers/${thumbPath(path)}"
        is Media -> url(serverUrl)
    }

    /** True when the image is (very likely) a GIF; used for the little badge in the grid. */
    val animated: Boolean
        get() = this is Bqb && path.substringAfterLast('.', "").equals("gif", ignoreCase = true)

    companion object {
        /** Parses the wire text; null for anything malformed. */
        fun parse(text: String?): StickerRef? {
            if (text.isNullOrBlank()) return null
            val parts = text.trim().split('|')
            if (parts.size != 4) return null
            val w = parts[2].trim().toIntOrNull() ?: return null
            val h = parts[3].trim().toIntOrNull() ?: return null
            if (w < 0 || h < 0) return null
            val key = parts[1].trim()
            if (key.isEmpty() || key.contains("..")) return null
            return when (parts[0].trim()) {
                "bqb" -> Bqb(key.trimStart('/'), w, h)
                "media" -> Media(key, w, h)
                else -> null
            }
        }

        /** `media/abc.jpg` → `thumbs/abc.webp`. */
        fun thumbPath(src: String): String {
            val p = if (src.startsWith("media/")) "thumbs/" + src.removePrefix("media/") else src
            val dot = p.lastIndexOf('.')
            val slash = p.lastIndexOf('/')
            return if (dot > slash) p.substring(0, dot) + ".webp" else "$p.webp"
        }
    }
}

/** A library item as something that can be sent. */
fun StickerItem.ref(): StickerRef = StickerRef.Bqb(src, width, height)

/**
 * The public sticker library: downloads the two catalog files into `filesDir/stickers/` (refreshed weekly),
 * keeps them parsed in memory and remembers the last 40 stickers the user sent.
 */
class StickerCatalog(ctx: Context, private val http: OkHttpClient, private val serverUrl: () -> String) {
    private val dir = File(ctx.filesDir, "stickers")
    private val sp = ctx.getSharedPreferences("stickers", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<StickerItem>>(emptyList())
    private val _categories = MutableStateFlow<List<StickerCategory>>(emptyList())
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _recent = MutableStateFlow(loadRecents())

    val items: StateFlow<List<StickerItem>> = _items.asStateFlow()
    val categories: StateFlow<List<StickerCategory>> = _categories.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    /** Most recent first; same content as [recents] but observable. */
    val recent: StateFlow<List<StickerRef>> = _recent.asStateFlow()

    /** Lower-cased search fields, built once per parse so a keystroke does not lower-case 5 871 × 4 strings. */
    private class Entry(val item: StickerItem, val label: String, val name: String, val title: String, val folder: String)
    @Volatile private var index: List<Entry> = emptyList()

    /**
     * Makes [items] and [categories] available. Uses the cached files when they are younger than a week,
     * otherwise downloads; a failed download falls back to whatever stale copy exists. Never throws — see [error].
     */
    suspend fun ensureLoaded(force: Boolean = false) {
        mutex.withLock {
            if (!force && _items.value.isNotEmpty() && _categories.value.isNotEmpty()) return
            _loading.value = true
            _error.value = null
            try {
                val (idxText, searchText) = withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    fetch("index.json", force) to fetch("search.json", force)
                }
                val (cats, list) = withContext(Dispatchers.Default) {
                    val cats = ProtoJson.decodeFromString(StickerIndex.serializer(), idxText).categories
                    val list = ProtoJson.decodeFromString(ListSerializer(StickerItem.serializer()), searchText)
                    index = list.map { Entry(it, it.label.lowercase(), it.name.lowercase(), it.categoryTitle.lowercase(), it.folder.lowercase()) }
                    cats to list
                }
                _categories.value = cats
                _items.value = list
            } catch (e: Exception) {
                Log.w(TAG, "catalog load failed: ${e.message}")
                // A corrupt cache would fail the same way next time; drop it so the next attempt re-downloads.
                if (e !is IOException) {
                    File(dir, "index.json").delete()
                    File(dir, "search.json").delete()
                }
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _loading.value = false
            }
        }
    }

    /** Returns the file's text, downloading it first when missing, older than [MAX_AGE_MS] or [force]. */
    private fun fetch(name: String, force: Boolean): String {
        val f = File(dir, name)
        val fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
        if (fresh && !force) return f.readText()
        try {
            val url = "${serverUrl().trimEnd('/')}/stickers/catalog/$name"
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                val tmp = File(dir, "$name.tmp")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (tmp.length() == 0L) throw IOException("empty file")
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("cannot replace $name") }
            }
        } catch (e: IOException) {
            if (f.exists() && f.length() > 0) {
                Log.w(TAG, "using stale $name: ${e.message}")
                return f.readText()
            }
            throw e
        }
        return f.readText()
    }

    /**
     * Case-insensitive substring search over label, file name, category title and folder.
     * Items whose category title equals the query come first, then label/name hits, then category/folder hits.
     */
    fun search(q: String, limit: Int = 800): List<StickerItem> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val idx = index
        val exact = ArrayList<StickerItem>()
        val own = ArrayList<StickerItem>()
        val group = ArrayList<StickerItem>()
        for (e in idx) {
            when {
                e.title == needle || e.folder == needle -> exact.add(e.item)
                e.label.contains(needle) || e.name.contains(needle) -> own.add(e.item)
                e.title.contains(needle) || e.folder.contains(needle) -> group.add(e.item)
                else -> continue
            }
            if (exact.size >= limit) break
        }
        val out = ArrayList<StickerItem>(minOf(limit, exact.size + own.size + group.size))
        for (l in listOf(exact, own, group)) for (x in l) { if (out.size >= limit) return out; out.add(x) }
        return out
    }

    fun byCategory(slug: String): List<StickerItem> = _items.value.filter { it.category == slug }

    // ---- recents (SharedPreferences "stickers", key "recent": JSON list of encoded refs) ----

    /** Most recent first, at most [MAX_RECENT]. */
    fun recents(): List<StickerRef> = _recent.value

    /** Moves [ref] to the front of the recents list. */
    fun touch(ref: StickerRef) {
        val next = (listOf(ref) + _recent.value.filter { it != ref }).take(MAX_RECENT)
        _recent.value = next
        runCatching {
            sp.edit().putString(KEY_RECENT, ProtoJson.encodeToString(ListSerializer(String.serializer()), next.map { it.encode() })).apply()
        }.onFailure { Log.w(TAG, "save recents: ${it.message}") }
    }

    private fun loadRecents(): List<StickerRef> = runCatching {
        val raw = sp.getString(KEY_RECENT, null) ?: return emptyList()
        ProtoJson.decodeFromString(ListSerializer(String.serializer()), raw).mapNotNull { StickerRef.parse(it) }.take(MAX_RECENT)
    }.getOrElse { Log.w(TAG, "load recents: ${it.message}"); emptyList() }

    companion object {
        private const val TAG = "Stickers"
        private const val KEY_RECENT = "recent"
        const val MAX_RECENT = 120
        private const val MAX_AGE_MS = 7L * 24 * 3600_000L
    }
}
