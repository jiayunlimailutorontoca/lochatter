package ink.jvm.chatter.util

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.io.File

/**
 * Which media ids were saved to the phone (相册 / 影片 / 下载) from this app, so bubbles, the file page and the
 * media library show 「已保存」 instead of offering the download again. Persisted as one id per line in
 * filesDir/saved-media.txt (oldest first, capped at [MAX_SAVED]); the in-memory copy is Compose-observable.
 */
object SavedMedia {
    const val MAX_SAVED = 4000
    private const val FILE = "saved-media.txt"

    private var loaded = false
    /** Insertion order, for the cap. */
    private val order = LinkedHashSet<String>()

    /** Observable copy of the saved ids (the value is unused). */
    val ids: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /** One-tap saves in flight: media id → progress 0..1 (the bubble shows a ring instead of the download button). */
    val progress: SnapshotStateMap<String, Float> = mutableStateMapOf()

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val text = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        val list = parseSavedIds(text)
        order.addAll(list)
        list.forEach { ids[it] = true }
    }

    /** Cheap enough to call from composition. */
    fun isSaved(ctx: Context, mediaId: String): Boolean {
        load(ctx)
        return ids.containsKey(mediaId)
    }

    /** Remember [mediaId] as saved and persist; safe from any thread. */
    @Synchronized
    fun markSaved(ctx: Context, mediaId: String) {
        load(ctx)
        if (mediaId.isEmpty()) return
        order.remove(mediaId)
        order.add(mediaId)
        ids[mediaId] = true
        while (order.size > MAX_SAVED) {
            val oldest = order.first()
            order.remove(oldest)
            ids.remove(oldest)
        }
        runCatching { File(ctx.filesDir, FILE).writeText(serializeSavedIds(order, MAX_SAVED)) }
    }
}

/** One id per line; blank lines and duplicates dropped, order kept. */
internal fun parseSavedIds(text: String): List<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

/** Oldest-first list capped to the newest [max] entries. */
internal fun serializeSavedIds(ids: Collection<String>, max: Int): String =
    ids.toList().takeLast(max).joinToString("\n")
