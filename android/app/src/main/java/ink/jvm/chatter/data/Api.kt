package ink.jvm.chatter.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ink.jvm.chatter.crypto.E2E
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : IOException(message)

/** What the system tells us about a picked document. */
data class PickedFile(val name: String, val mime: String, val size: Long)

/**
 * HTTP side of the protocol: login, account, keys, media and the update manifest.
 * [client] must carry the bearer header (see ChatRepository.authHttp). When [keyProvider] returns a
 * session key, uploads are encrypted on the way up; downloads are decrypted by [DecryptInterceptor].
 * [v2KeyProvider] (1.5) returns the epoch key + epochs for the LCE2 stream format when both sides have one.
 */
class Api(
    private val client: OkHttpClient,
    private val prefs: Prefs,
    private val keyProvider: () -> ByteArray? = { null },
    private val v2KeyProvider: () -> MediaKey? = { null },
) {
    /** Epoch key and header fields for an LCE2 upload. */
    class MediaKey(val key: ByteArray, val senderUid: Long, val senderEpoch: Int, val receiverEpoch: Int)

    suspend fun login(server: String, name: String, password: String, device: String): LoginResponse =
        withContext(Dispatchers.IO) {
            val payload = ProtoJson.encodeToString(LoginRequest.serializer(), LoginRequest(name, password, device))
            val req = Request.Builder().url("$server/auth/login").post(payload.toRequestBody(JSON)).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, friendly(resp.code, text))
                ProtoJson.decodeFromString(LoginResponse.serializer(), text)
            }
        }

    // ---- account ----

    suspend fun changePassword(old: String, new: String, logoutOthers: Boolean): Int = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(PasswordRequest.serializer(), PasswordRequest(old, new, logoutOthers))
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/auth/password").post(payload.toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(PasswordResponse.serializer(), text).revoked
    }

    suspend fun devices(): List<DeviceInfo> = withContext(Dispatchers.IO) {
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/auth/devices").get().build()))
        ProtoJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DeviceInfo.serializer()), text)
    }

    suspend fun revokeDevice(id: Long) = withContext(Dispatchers.IO) {
        execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/auth/devices/$id").delete().build()))
        Unit
    }

    /** A device token labeled "web". The phone seals it for the browser; it is not kept on this phone. */
    suspend fun issueWebToken(): String = withContext(Dispatchers.IO) {
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/auth/web-token").post("{}".toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(WebTokenResponse.serializer(), text).token
    }

    /** Hands the encrypted login box to a pending webpage ticket. */
    suspend fun approveWebTicket(id: String, box: String) = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(WebBoxRequest.serializer(), WebBoxRequest(box))
        execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/auth/web-ticket/$id").post(payload.toRequestBody(JSON)).build()))
        Unit
    }

    // ---- in-chat assistant ----

    suspend fun botInfo(): BotInfo = withContext(Dispatchers.IO) {
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/bot").get().build()))
        ProtoJson.decodeFromString(BotInfo.serializer(), text)
    }

    suspend fun renameBot(name: String): BotInfo = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(BotNameRequest.serializer(), BotNameRequest(name))
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/bot/name").post(payload.toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(BotInfo.serializer(), text)
    }

    suspend fun pushPref(): PushPref = withContext(Dispatchers.IO) {
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/push").get().build()))
        ProtoJson.decodeFromString(PushPref.serializer(), text)
    }

    suspend fun putPushKeys(keys: List<PushKeyUp>) = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(PushKeysRequest.serializer(), PushKeysRequest(keys))
        execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/push/keys").post(payload.toRequestBody(JSON)).build()))
        Unit
    }

    suspend fun setPushPref(pref: PushPref): PushPref = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(PushPref.serializer(), pref)
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/push").put(payload.toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(PushPref.serializer(), text)
    }

    /** Whether the assistant's messages follow the disappearing-message timer (both users). */
    suspend fun setBotTtl(enabled: Boolean): BotInfo = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(BotTtlRequest.serializer(), BotTtlRequest(enabled))
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/bot/ttl").post(payload.toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(BotInfo.serializer(), text)
    }

    // ---- shared key/value store (quick commands, anniversaries, custom stickers) ----

    suspend fun shared(): List<SharedItem> = withContext(Dispatchers.IO) {
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/shared").get().build()))
        ProtoJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SharedItem.serializer()), text)
    }

    suspend fun putShared(key: String, value: String): SharedItem = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(SharedValueRequest.serializer(), SharedValueRequest(value))
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/shared/$key").put(payload.toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(SharedItem.serializer(), text)
    }

    /** Sticker library files are served by nginx next to the API (no auth). */
    fun stickerBase(): String = "${prefs.serverUrl}/stickers"

    // ---- end-to-end keys ----

    suspend fun publishKey(pubKey: String): KeyInfo = withContext(Dispatchers.IO) {
        val payload = ProtoJson.encodeToString(KeyRequest.serializer(), KeyRequest(pubKey))
        val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/keys").post(payload.toRequestBody(JSON)).build()))
        ProtoJson.decodeFromString(KeyInfo.serializer(), text)
    }

    /** null when the user has not published a key yet. */
    suspend fun fetchKey(userId: Long): KeyInfo? = withContext(Dispatchers.IO) {
        try {
            val text = execute(client.newCall(Request.Builder().url("${prefs.serverUrl}/keys/$userId").get().build()))
            ProtoJson.decodeFromString(KeyInfo.serializer(), text)
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
        }
    }

    // ---- media ----

    suspend fun upload(
        bytes: ByteArray,
        mime: String,
        width: Int? = null,
        height: Int? = null,
        durationMs: Int? = null,
        thumb: String? = null,
        name: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        /** true for media addressed to the assistant: it has no key, so the bytes go up as they are. */
        plain: Boolean = false,
    ): MediaInfo {
        val v2 = if (plain) null else v2KeyProvider()
        val key = if (plain) null else keyProvider()
        val body = when {
            v2 != null -> java.io.ByteArrayOutputStream(bytes.size + 64).also { E2E.encryptStreamV2(v2.key, v2.senderUid, v2.senderEpoch, v2.receiverEpoch, bytes.inputStream(), it) }.toByteArray().toRequestBody(mime.toMediaType())
            key == null -> bytes.toRequestBody(mime.toMediaType())
            else -> E2E.encryptBytes(key, bytes).toRequestBody(mime.toMediaType())
        }
        return send(body, width, height, durationMs, thumb, name, onProgress)
    }

    /** Streams a content:// document straight from the resolver so large files never sit in memory. */
    suspend fun uploadUri(
        ctx: Context, uri: Uri, info: PickedFile,
        width: Int? = null, height: Int? = null, durationMs: Int? = null, thumb: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        plain: Boolean = false,
    ): MediaInfo {
        val v2 = if (plain) null else v2KeyProvider()
        val key = if (plain) null else keyProvider()
        val body = object : RequestBody() {
            override fun contentType(): MediaType = info.mime.toMediaType()
            override fun contentLength(): Long = when {
                info.size <= 0 -> -1
                v2 != null -> E2E.encryptedSizeV2(info.size)
                key == null -> info.size
                else -> E2E.encryptedSize(info.size)
            }
            override fun isOneShot(): Boolean = false
            override fun writeTo(sink: BufferedSink) {
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("无法打开文件")
                input.use { inp ->
                    when {
                        v2 != null -> E2E.encryptStreamV2(v2.key, v2.senderUid, v2.senderEpoch, v2.receiverEpoch, inp, sink.outputStream())
                        key == null -> sink.writeAll(inp.source())
                        else -> E2E.encryptStream(key, inp, sink.outputStream())
                    }
                }
            }
        }
        return send(body, width, height, durationMs, thumb, info.name, onProgress)
    }

    private suspend fun send(
        raw: RequestBody, width: Int?, height: Int?, durationMs: Int?, thumb: String?, name: String?,
        onProgress: ((Float) -> Unit)?,
    ): MediaInfo {
        val url = prefs.serverUrl.toHttpUrl().newBuilder().addPathSegment("media").apply {
            width?.let { addQueryParameter("w", it.toString()) }
            height?.let { addQueryParameter("h", it.toString()) }
            durationMs?.let { addQueryParameter("d", it.toString()) }
            thumb?.let { addQueryParameter("thumb", it) }
            name?.let { addQueryParameter("name", it) }
        }.build()
        val body = if (onProgress == null) raw else ProgressBody(raw, onProgress)
        val req = Request.Builder().url(url).post(body).build()
        val text = execute(client.newCall(req))
        return ProtoJson.decodeFromString(MediaInfo.serializer(), text)
    }

    /** Runs the call so that cancelling the coroutine cancels the HTTP request (and the upload with it). */
    private suspend fun execute(call: Call): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val t = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (cont.isActive) cont.resumeWithException(ApiException(resp.code, friendly(resp.code, t)))
                    } else if (cont.isActive) {
                        cont.resume(t)
                    }
                }
            }
        })
    }

    /** GET /apk/latest.json: what the newest build on the server is. */
    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${prefs.serverUrl}/apk/latest.json").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "no manifest")
            ProtoJson.decodeFromString(ReleaseInfo.serializer(), resp.body?.string().orEmpty())
        }
    }

    /**
     * Server-side speech-to-text (1.6, `POST /stt`): for phones without a system recognizer. [file] is a voice note as
     * recorded (m4a) or any audio the server's ffmpeg can read. Throws with the server's message (503 when unconfigured).
     */
    /** This account's chat server `POST /stt`. Kept for the optional cloud address in settings. */
    suspend fun stt(file: java.io.File, language: String = "zh"): SttResult =
        sttAt("${prefs.serverUrl.trimEnd('/')}/stt", file, language)

    /**
     * Post [file] to an OpenAI-compatible transcription URL. A URL on the logged-in chat server
     * sends the account token; any other host is called without it.
     */
    suspend fun sttAt(url: String, file: java.io.File, language: String = "zh"): SttResult = withContext(Dispatchers.IO) {
        val endpoint = url.trim()
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw ApiException(0, "云端地址要以 http:// 或 https:// 开头")
        }
        val mime = when (file.extension.lowercase()) { "wav" -> "audio/wav"; "ogg", "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"; else -> "audio/mp4" }
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("language", language)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val http = if (sameOrigin(endpoint)) client else plainClient
        val text = execute(http.newCall(Request.Builder().url(endpoint).post(body).build()))
        ProtoJson.decodeFromString(SttResult.serializer(), text)
    }

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun sameOrigin(url: String): Boolean = runCatching {
        val base = prefs.serverUrl.toHttpUrl()
        val dest = url.toHttpUrl()
        base.scheme == dest.scheme && base.host.equals(dest.host, ignoreCase = true) && base.port == dest.port
    }.getOrDefault(false)

    /** Map tiles proxied by the server (1.6): standard XYZ, 256 px. */
    /** "tiles" (OpenStreetMap, WGS-84) or "amap-tiles" (Amap, GCJ-02); set from hello.features.tileDatum. */
    @Volatile var tilePath: String = "tiles"

    fun tileUrl(z: Int, x: Int, y: Int): String = "${prefs.serverUrl}/$tilePath/$z/$x/$y.png"

    // ---- places (1.6, `/geo/…`: the server talks to Amap; everything on the wire is WGS-84) ----

    /** Reverse geocode: the point's address, a short name, and up to 20 places around it (the picker's list). */
    suspend fun geoRegeo(lat: Double, lng: Double): GeoResult =
        ProtoJson.decodeFromString(GeoResult.serializer(), geo("regeo", mapOf("lat" to num(lat), "lng" to num(lng))))

    /** Keyword search within 2 km ([q] empty = popular places nearby); [page] from 1, 20 per page. */
    suspend fun geoAround(lat: Double, lng: Double, q: String = "", page: Int = 1): List<Poi> =
        ProtoJson.decodeFromString(POI_LIST, geo("around", mapOf("lat" to num(lat), "lng" to num(lng), "q" to q, "page" to page.toString())))

    /** City-wide search; [lat]/[lng] pick the city and order the results. */
    suspend fun geoSearch(q: String, lat: Double, lng: Double): List<Poi> =
        ProtoJson.decodeFromString(POI_LIST, geo("search", mapOf("q" to q, "lat" to num(lat), "lng" to num(lng))))

    private fun num(d: Double): String = "%.6f".format(java.util.Locale.US, d)

    private suspend fun geo(path: String, params: Map<String, String>): String {
        val url = prefs.serverUrl.toHttpUrl().newBuilder().addPathSegment("geo").addPathSegment(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        try {
            return execute(client.newCall(Request.Builder().url(url).get().build()))
        } catch (e: ApiException) {
            // 1.6 geo errors carry {"code": "geo_unavailable" | "geo_upstream", "message": ...} rather than {"error": ...}.
            throw ApiException(e.code, when (e.code) {
                503 -> "服务器没有配置地图服务"
                502 -> "地图服务出错，稍后再试"
                429 -> "操作太快了，稍等一下"
                else -> e.message ?: "服务器错误 ${e.code}"
            })
        }
    }

    fun mediaUrl(id: String): String = "${prefs.serverUrl}/media/$id"

    fun downloadUrl(id: String): String = "${prefs.serverUrl}/media/$id?dl=1"

    private fun friendly(code: Int, body: String): String {
        val serverError = runCatching { ProtoJson.decodeFromString(ErrorResponse.serializer(), body).error }.getOrNull()
        return when (code) {
            401 -> "用户名或密码错误"
            403 -> "密码不正确"
            413 -> "文件太大"
            415 -> "不支持的文件类型"
            429 -> "尝试次数过多，请稍后再试"
            507 -> "服务器磁盘快满了"
            else -> serverError ?: "服务器错误 $code"
        }
    }

    /** Reports bytes written as a 0..1 fraction (unknown length: stays at 0 until done). */
    private class ProgressBody(private val inner: RequestBody, private val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType(): MediaType? = inner.contentType()
        override fun contentLength(): Long = inner.contentLength()
        override fun isOneShot(): Boolean = inner.isOneShot()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var written = 0L
            var lastPct = -1
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    if (total > 0) {
                        val pct = (written * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct / 100f)
                        }
                    }
                }
            }.buffer()
            inner.writeTo(counting)
            counting.flush()
        }
    }

    /**
     * Transparently decrypts encrypted media bodies (magic "LCE1" with the legacy key, "LCE2" with the epoch
     * key named in the header) for every consumer of the authenticated client: Coil thumbnails, the gallery,
     * voice notes, file downloads. Plain blobs pass through untouched.
     */
    class DecryptInterceptor(
        private val keyProvider: () -> ByteArray?,
        private val v2KeyFor: (senderUid: Long, senderEpoch: Int, receiverEpoch: Int) -> ByteArray? = { _, _, _ -> null },
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val resp = chain.proceed(chain.request())
            val body = resp.body ?: return resp
            if (!resp.isSuccessful || !chain.request().url.encodedPath.contains("/media/")) return resp
            val src = body.source()
            if (!src.request(4)) return resp
            val head = src.buffer.snapshot(4).toByteArray()
            if (!E2E.isEncryptedStream(head)) return resp
            val plain = if (E2E.isV2Stream(head)) {
                E2E.decryptingStreamV2(src.inputStream()) { uid, a, b -> if (a == 0 && b == 0) keyProvider() else v2KeyFor(uid, a, b) }
            } else {
                val key = keyProvider() ?: return resp
                E2E.decryptingStream(key, src.inputStream())
            }
            val newBody = object : ResponseBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = -1
                override fun source() = plain.source().buffer()
            }
            return resp.newBuilder().body(newBody).removeHeader("Content-Length").removeHeader("Content-Range").build()
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val POI_LIST = kotlinx.serialization.builtins.ListSerializer(Poi.serializer())

        /** Name / MIME / size of a document picked with ACTION_OPEN_DOCUMENT. */
        fun describe(ctx: Context, uri: Uri): PickedFile {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
            if (size < 0) runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length } }
            val mime = ctx.contentResolver.getType(uri)?.takeIf { it.contains('/') } ?: "application/octet-stream"
            return PickedFile(name, mime, size)
        }
    }
}
