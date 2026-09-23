package ink.jvm.chatter.data

import android.net.Uri
import ink.jvm.chatter.crypto.E2E
import ink.jvm.chatter.media.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The two-person shared key/value store: quick commands, anniversaries, sticker favourites,
 * pin, and each person's own avatar and signature. A profile key belongs to one account.
 */
internal class SharedStore(private val r: ChatRepository) {
    private val map = MutableStateFlow<Map<String, String>>(emptyMap())
    val quickCommands = MutableStateFlow(SharedCodec.DEFAULT_QUICK)
    val anniversaries = MutableStateFlow<List<Anniversary>>(emptyList())
    val stickerFavorites = MutableStateFlow<List<StickerRef>>(emptyList())
    val chatPinned = MutableStateFlow(false)
    val myAvatarId = MutableStateFlow<String?>(null)
    val peerAvatarId = MutableStateFlow<String?>(null)
    val mySignature = MutableStateFlow("")
    val peerSignature = MutableStateFlow("")

    fun restore() {
        val cached = runCatching {
            ProtoJson.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.serializer<String>(),
                    kotlinx.serialization.serializer<String>(),
                ),
                r.prefs.sharedCache,
            )
        }.getOrDefault(emptyMap())
        apply(cached, persist = false)
    }

    /** Re-read encrypted values after the session key appears. */
    fun reapply() = apply(map.value, persist = false)

    fun merge(key: String, value: String) = apply(map.value + (key to value), persist = true)

    fun apply(next: Map<String, String>, persist: Boolean) {
        map.value = next
        if (persist) {
            r.prefs.sharedCache = ProtoJson.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.serializer<String>(),
                    kotlinx.serialization.serializer<String>(),
                ),
                next,
            )
        }
        next[SharedCodec.KEY_QUICK]?.let { v ->
            SharedCodec.strings(plain(SharedCodec.KEY_QUICK, v)).takeIf { it.isNotEmpty() }?.let { quickCommands.value = it }
        }
        next[SharedCodec.KEY_ANNIV]?.let { v -> plain(SharedCodec.KEY_ANNIV, v)?.let { anniversaries.value = SharedCodec.anniversaries(it) } }
        next[SharedCodec.KEY_STICKERS]?.let { v -> plain(SharedCodec.KEY_STICKERS, v)?.let { stickerFavorites.value = SharedCodec.stickers(it) } }
        next[SharedCodec.KEY_PIN]?.let { v -> chatPinned.value = plain(SharedCodec.KEY_PIN, v) == "1" }
        myAvatarId.value = profileText(next, SharedCodec.avatarKey(r.me))?.takeIf { it.isNotBlank() }
        peerAvatarId.value = profileText(next, SharedCodec.avatarKey(r.peerId))?.takeIf { it.isNotBlank() }
        mySignature.value = profileText(next, SharedCodec.signKey(r.me)).orEmpty()
        peerSignature.value = profileText(next, SharedCodec.signKey(r.peerId)).orEmpty()
        r.widgetTick.value = r.widgetTick.value + 1
    }

    private fun profileText(next: Map<String, String>, key: String): String? {
        val raw = next[key] ?: return null
        return plain(key, raw)
    }

    fun clear() {
        map.value = emptyMap()
        quickCommands.value = SharedCodec.DEFAULT_QUICK
        anniversaries.value = emptyList()
        stickerFavorites.value = emptyList()
        chatPinned.value = false
        myAvatarId.value = null
        peerAvatarId.value = null
        mySignature.value = ""
        peerSignature.value = ""
        r.prefs.sharedCache = "{}"
        r.widgetTick.value = r.widgetTick.value + 1
    }

    /** Opens a stored value. New writes use the epoch key (e2e2); older ones still use the identity key (e2e). */
    private fun plain(key: String, value: String): String? {
        if (!E2E.isEncrypted(value)) return value
        val opened = r.dec(value, key) ?: return null
        return if (opened == MessagePipeline.LOCKED) null else opened
    }

    private suspend fun put(key: String, plain: String, encrypt: Boolean) {
        val wire = if (encrypt) (r.enc(plain, key) ?: plain) else plain
        val item = r.api.putShared(key, wire)
        apply(map.value + (item.key to item.value), persist = true)
    }

    suspend fun setQuickCommands(list: List<String>) = put(SharedCodec.KEY_QUICK, SharedCodec.strings(list), encrypt = false)
    suspend fun setAnniversaries(list: List<Anniversary>) = put(SharedCodec.KEY_ANNIV, SharedCodec.anniversaries(list), encrypt = true)
    suspend fun addStickerFavorite(ref: StickerRef) {
        val cur = stickerFavorites.value.filter { it != ref }
        put(SharedCodec.KEY_STICKERS, SharedCodec.stickers(listOf(ref) + cur), encrypt = true)
    }
    suspend fun removeStickerFavorite(ref: StickerRef) =
        put(SharedCodec.KEY_STICKERS, SharedCodec.stickers(stickerFavorites.value.filter { it != ref }), encrypt = true)
    suspend fun setPinned(on: Boolean) = put(SharedCodec.KEY_PIN, if (on) "1" else "0", encrypt = false)
    suspend fun setSignature(text: String) = put(SharedCodec.signKey(r.me), text.take(80), encrypt = true)

    /** Plain upload so the other phone can show it. The media id in the store is still encrypted, and the key is mine. */
    suspend fun setAvatar(uri: Uri) {
        val (full, _) = withContext(Dispatchers.IO) { ImageUtil.prepare(r.app, uri, original = false) }
        val info = r.api.upload(full.bytes, full.mime, full.width, full.height, plain = true)
        put(SharedCodec.avatarKey(r.me), info.id, encrypt = true)
    }

    /** Pull the shared store again so the other person's avatar and signature catch up. */
    suspend fun refresh() {
        val list = r.api.shared()
        apply(list.associate { it.key to it.value }, persist = true)
    }

    /**
     * A picked image becomes a custom sticker: uploaded unencrypted (the assistant must be able to see it)
     * and remembered in the shared favourites.
     */
    suspend fun addCustomSticker(uri: Uri) {
        val (full, _) = withContext(Dispatchers.IO) { ImageUtil.prepare(r.app, uri, original = false) }
        val info = r.api.upload(full.bytes, full.mime, full.width, full.height, plain = true)
        addStickerFavorite(StickerRef.Media(info.id, info.width ?: full.width, info.height ?: full.height))
    }
}
