package ink.jvm.chatter.data

import android.net.Uri
import ink.jvm.chatter.media.ImageUtil
import ink.jvm.chatter.util.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Photo, video, voice, and file uploads. Placeholders hit the message list before the bytes leave the phone. */
internal class MediaUploader(private val r: ChatRepository) {
    private val _uploading = MutableStateFlow(0)
    val uploading: StateFlow<Int> = _uploading.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val localUris = ConcurrentHashMap<String, Uri>()
    private val originals = ConcurrentHashMap.newKeySet<String>()

    fun isUploading(id: String): Boolean = jobs.containsKey(id)
    fun localUri(id: String): Uri? = localUris[id]

    /**
     * Several photos / videos at once with an optional caption on the last one.
     * [toBot]: plaintext, for the assistant. [once]: view-once. [album]: photos land in the shared album.
     */
    fun sendMedia(uris: List<Uri>, caption: String?, original: Boolean, toBot: Boolean = false, once: Boolean = false, album: Boolean = false) {
        val dest = if (toBot) "bot" else null
        r.scope.launch {
            val base = System.currentTimeMillis()
            val items = uris.mapIndexed { index, uri ->
                val isVideo = isVideoUri(uri)
                val id = UUID.randomUUID().toString()
                localUris[id] = uri
                val info = if (isVideo || original) runCatching { Api.describe(r.app, uri) }.getOrNull() else null
                val media = if (isVideo) MediaInfo(id = "", mime = info?.mime ?: "video/mp4", size = info?.size?.coerceAtLeast(0) ?: 0, name = info?.name) else null
                val kind = if (isVideo) "video" else if (album && !toBot) "album" else "image"
                val m = LocalMessage(id, null, r.me, kind, null, media, base + index, LocalMessage.PENDING, expiresAt = r.inbox.expiry(toBot), to = dest, once = once && !toBot)
                r.db.upsert(m)
                r.inbox.upsertVisible(m)
                Triple(id, uri, isVideo)
            }
            val cap = caption?.trim()?.takeIf { it.isNotEmpty() }
            if (cap != null) items.lastOrNull()?.let { (id, _, _) ->
                r.db.get(id)?.let { m -> r.db.upsert(m.copy(text = cap)); r.inbox.upsertVisible(m.copy(text = cap)) }
            }
            if (original) items.forEach { originals.add(it.first) }
            for ((id, uri, isVideo) in items) runCatching { uploadMedia(id, uri, image = !isVideo) }
        }
    }

    suspend fun sendVoice(file: File, durationMs: Int, toBot: Boolean = false) {
        val id = UUID.randomUUID().toString()
        localUris[id] = Uri.fromFile(file)
        val placeholder = LocalMessage(
            id, null, r.me, "audio", null,
            MediaInfo(id = "", mime = "audio/mp4", size = file.length(), durationMs = durationMs),
            System.currentTimeMillis(), LocalMessage.PENDING, expiresAt = r.inbox.expiry(toBot), to = if (toBot) "bot" else null,
        )
        r.db.upsert(placeholder)
        r.inbox.upsertVisible(placeholder)
        uploadMedia(id, Uri.fromFile(file), image = false)
    }

    suspend fun sendFile(uri: Uri, toBot: Boolean = false) {
        val id = UUID.randomUUID().toString()
        localUris[id] = uri
        val info = withContext(Dispatchers.IO) { Api.describe(r.app, uri) }
        val media = MediaInfo(id = "", mime = info.mime, size = info.size.coerceAtLeast(0), name = info.name)
        val placeholder = LocalMessage(id, null, r.me, "file", null, media, System.currentTimeMillis(), LocalMessage.PENDING, expiresAt = r.inbox.expiry(toBot), to = if (toBot) "bot" else null)
        r.db.upsert(placeholder)
        r.inbox.upsertVisible(placeholder)
        uploadMedia(id, uri, image = false)
    }

    fun cancelUpload(id: String) {
        jobs.remove(id)?.cancel()
        localUris.remove(id)
        r.scope.launch {
            r.db.delete(id)
            r.inbox.removeVisible(id)
        }
    }

    suspend fun uploadMedia(id: String, uri: Uri, image: Boolean) {
        val job = r.scope.launch(Dispatchers.IO) { doUpload(id, uri, image) }
        jobs[id] = job
        try {
            job.join()
        } finally {
            jobs.remove(id, job)
        }
        if (job.isCancelled) return
        if (r.db.get(id)?.status == LocalMessage.FAILED) throw IOException("发送失败")
    }

    private fun encName(name: String?): String? {
        if (name == null) return null
        if (r.sessionKey == null && !r.keys.v2Ready()) return name
        return r.enc(name, "name", urlSafe = true)
    }

    private fun decName(name: String?): String? = MessagePipeline.decryptName(name, r::dec)

    private suspend fun doUpload(id: String, uri: Uri, image: Boolean) {
        _uploading.value = _uploading.value + 1
        val progress: (Float) -> Unit = { p -> _progress.value = _progress.value + (id to p) }
        try {
            val prev = r.db.get(id) ?: return
            val plain = prev.toBot
            fun wireName(name: String?): String? = if (plain) name else encName(name)
            val f = when {
                image -> {
                    val orig = originals.remove(id)
                    val (full, thumb) = ImageUtil.prepare(r.app, uri, original = orig)
                    val t = r.api.upload(thumb.bytes, "image/jpeg", thumb.width, thumb.height, plain = plain)
                    if (orig && full.bytes.isEmpty()) {
                        val info = Api.describe(r.app, uri)
                        r.api.uploadUri(r.app, uri, info.copy(name = wireName(info.name) ?: info.name), full.width, full.height, null, t.id, progress, plain = plain)
                    } else {
                        r.api.upload(full.bytes, full.mime, full.width, full.height, thumb = t.id, onProgress = progress, plain = plain)
                    }
                }
                prev.kind == "video" -> {
                    val info = Api.describe(r.app, uri)
                    val (cover, dur, dims) = ImageUtil.videoInfo(r.app, uri)
                    val t = r.api.upload(cover.bytes, "image/jpeg", cover.width, cover.height, plain = plain)
                    r.api.uploadUri(r.app, uri, info.copy(name = wireName(info.name) ?: info.name), dims.first.takeIf { it > 0 }, dims.second.takeIf { it > 0 }, dur.takeIf { it > 0 }, t.id, progress, plain = plain)
                }
                prev.kind == "audio" -> {
                    val file = File(uri.path ?: throw IOException("录音文件丢失"))
                    if (!file.exists()) throw IOException("录音文件丢失")
                    r.api.upload(file.readBytes(), "audio/mp4", durationMs = prev.media?.durationMs, onProgress = progress, plain = plain)
                }
                else -> {
                    val info = Api.describe(r.app, uri)
                    r.api.uploadUri(r.app, uri, info.copy(name = wireName(info.name) ?: info.name), onProgress = progress, plain = plain)
                }
            }
            val localMedia = f.copy(name = prev.media?.name ?: decName(f.name))
            val done = LocalMessage(id, null, r.me, prev.kind, prev.text, localMedia, prev.ts, LocalMessage.PENDING, prev.reply, expiresAt = prev.expiresAt, to = prev.to, once = prev.once)
            r.db.upsert(done)
            r.inbox.upsertVisible(done)
            r.ws.send(MsgSend(id, done.kind, if (plain) done.text else r.enc(done.text, id), f.id, done.reply?.id, done.to, once = if (done.once) true else null, notice = PushNotice.of(done.kind, done.text, done.once)))
            if (prev.kind == "audio") runCatching { File(uri.path!!).delete() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("MediaUploader", "upload $id failed", e)
            r.db.markFailed(id)
            r.db.get(id)?.let { r.inbox.upsertVisible(it) }
        } finally {
            _uploading.value = (_uploading.value - 1).coerceAtLeast(0)
            _progress.value = _progress.value - id
        }
    }

    private fun isVideoUri(uri: Uri): Boolean {
        r.app.contentResolver.getType(uri)?.let { return it.startsWith("video/") }
        val name = uri.lastPathSegment?.lowercase() ?: return false
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm") || name.endsWith(".3gp") || name.endsWith(".mkv")
    }
}
