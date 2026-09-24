package ink.jvm.chatter.data

import android.app.Application
import android.net.Uri
import android.os.Build
import ink.jvm.chatter.crypto.E2E
import ink.jvm.chatter.media.ImageUtil
import ink.jvm.chatter.media.VoicePlayer
import ink.jvm.chatter.util.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Single source of truth for the conversation: local DB (plaintext) + live socket (end-to-end encrypted once
 * both users have published keys). Lives in the Application; the foreground service keeps the process alive.
 */
class ChatRepository(internal val app: Application, val prefs: Prefs, internal val db: Db) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** AES key shared with the peer, or null while either side has no key yet (messages then go in the clear). */
    @Volatile var sessionKey: ByteArray? = null
        private set

    /** end-to-end state for the UI: off (no keys yet) / on / peer key changed since we last verified. */
    enum class E2eState { OFF, ON, PEER_KEY_CHANGED }
    val e2eState = MutableStateFlow(E2eState.OFF)
    /** Server holds a different key for *me* than this phone has: the user must import or overwrite. */
    val keyConflict = MutableStateFlow<String?>(null)
    /** Disappearing-message timer in effect (seconds, 0 = off). */
    val ttlSeconds = MutableStateFlow(prefs.ttlSeconds)
    /** In-chat assistant: name chosen by the two of you (empty = server too old), and whether Hermes is connected. */
    val botName get() = bots.name
    val botOnline get() = bots.online
    /** The assistant's messages follow the disappearing timer (server setting shared by both). */
    val botTtl get() = bots.ttl
    /** What the server offers (1.6): transcription, places, map tiles. Reset from every hello. */
    val features = MutableStateFlow(Features())
    /** The two humans as the server names them (for the assistant page's author labels). */
    val peerBattery = MutableStateFlow<Int?>(null)
    val peerCharging = MutableStateFlow<Boolean?>(null)

    /** Assistant quick commands (plaintext, both users). */
    val quickCommands get() = sharedStore.quickCommands
    /** Anniversaries (end-to-end encrypted in the store). */
    val anniversaries get() = sharedStore.anniversaries
    /** Custom sticker favourites (end-to-end encrypted in the store). */
    val stickerFavorites get() = sharedStore.stickerFavorites
    /** 1.8: human conversation pinned for both phones. */
    val chatPinned get() = sharedStore.chatPinned
    /** This account's avatar media id, or null. The peer has their own. */
    val myAvatarId get() = sharedStore.myAvatarId
    val peerAvatarId get() = sharedStore.peerAvatarId
    val mySignature get() = sharedStore.mySignature
    val peerSignature get() = sharedStore.peerSignature
    /** This phone wants the human / assistant row to look unread. */
    val markUnreadPeer = MutableStateFlow(prefs.markUnreadPeer)
    val markUnreadBot = MutableStateFlow(prefs.markUnreadBot)
    val pinBot = MutableStateFlow(prefs.pinBot)
    /** Pats from the peer, for the vibration and the notification. */
    val patIncoming = MutableSharedFlow<LocalMessage>(extraBufferCapacity = 8)
    /** Bumped whenever something the home-screen widget shows may have changed. */
    val widgetTick = MutableStateFlow(0L)
    /** Pending scheduled sends (phone-local). */
    val scheduledList get() = inbox.scheduledList
    /** Message id → transcript once a voice note has been transcribed on this phone. */
    val transcripts get() = inbox.transcripts

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Same client plus a bearer header for requests to our own server, and transparent decryption of media bodies. */
    val authHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS) // large downloads through the decrypting interceptor
        .writeTimeout(0, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val token = prefs.token
            if (token != null && req.url.toString().startsWith(prefs.serverUrl)) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
            } else {
                chain.proceed(req)
            }
        }
        .addInterceptor(Api.DecryptInterceptor({ sessionKey }, { uid, a, b -> keys.keyFor(uid, a, b) }))
        .build()

    /** Identity + rotating epoch keys (1.5 forward secrecy). */
    val keys = KeyManager(prefs)

    val api = Api(authHttp, prefs, { sessionKey }, { mediaKeyV2() })
    /** Sticker library (catalog cached on the phone; images served by nginx next to the API). */
    val stickers = StickerCatalog(app, http) { prefs.serverUrl }

    val messages: StateFlow<List<LocalMessage>> get() = inbox.messages
    val hasOlder: StateFlow<Boolean> get() = inbox.hasOlder
    val loadingOlder: StateFlow<Boolean> get() = inbox.loadingOlder
    val connection = MutableStateFlow(WsClient.State.DISCONNECTED)
    val peerOnline = MutableStateFlow(false)
    val peerLastSeen = MutableStateFlow<Long?>(null)
    val peerTyping = MutableStateFlow(false)
    val peerReadUpto = MutableStateFlow(0L)
    val uploading: StateFlow<Int> get() = uploader.uploading
    /** message id → 0..1 upload progress while a media message is being sent. */
    val uploadProgress: StateFlow<Map<String, Float>> get() = uploader.progress
    /** message id → reactions on it (both users). */
    val reactions: StateFlow<Map<String, List<Reaction>>> get() = inbox.reactions
    val voice = VoicePlayer(app, authHttp)

    /** Emitted when the server rejects our token; UI returns to login. */
    val authLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Messages from the peer as they arrive; the app turns these into notifications. */
    val incoming = MutableSharedFlow<LocalMessage>(extraBufferCapacity = 32)

    /** Call signaling frames (already decrypted), consumed by CallManager on the main thread. */
    val callFrames = MutableSharedFlow<Frame>(extraBufferCapacity = 64)

    /** Text handed to us by another app via the share sheet; the composer picks it up. */
    val sharedText = MutableStateFlow<String?>(null)
    /** Media handed to us via the share sheet or the system picker; the send-preview screen picks them up. */
    val sharedMedia = MutableStateFlow<List<Uri>>(emptyList())
    /** A document picked with the system file picker (owned by the Activity so a call's PiP cannot drop it). */
    val pickedFile = MutableStateFlow<Uri?>(null)
    /** A picture picked to become a custom sticker. */
    val pickedSticker = MutableStateFlow<Uri?>(null)
    /** A picture picked as the chat wallpaper (settings). */
    val pickedWallpaper = MutableStateFlow<Uri?>(null)

    /** True while the chat screen is resumed; read marks are sent only then. */
    @Volatile var chatVisible = false

    /** True while any of our activities is started; call notifications are posted only when false. */
    @Volatile var appVisible = false
        private set

    private var typingJob: Job? = null
    /** The assistant is composing a reply. */
    val botTyping get() = bots.typing
    /** Bumped whenever a message to or from the assistant changes; the assistant page re-queries on it. */
    val botTick get() = bots.tick
    /** Question ids the assistant has answered (an answer quoting them exists). */
    val botReplied get() = bots.replied
    /** Assistant replies newer than the last time its page was open. */
    val botUnread get() = bots.unread
    /** True while the assistant page is in front: its replies are read at once and never notify. */
    var botVisible: Boolean
        get() = bots.visible
        set(value) { bots.visible = value }
    /** A streamed assistant reply changed its text; the notification refreshes from this. */
    val botEdited get() = bots.edited
    private var lastTypingSent = 0L
    internal var lastReadSent = prefs.readUpto
    /** Highest peer seq we have told the server we read; the unread divider starts after it. */
    val myReadUpto: Long get() = lastReadSent
    /** Catch-up sync after a reconnect notifies for messages above this seq; -1 = fresh login, stay quiet. */
    @Volatile private var notifyAbove = -1L
    internal val ws = WsClient(
        client = http,
        scope = scope,
        onFrame = ::handle,
        onState = { st ->
            connection.value = st
            Diag.log(TAG, "socket ${st.name.lowercase()}")
            if (st == WsClient.State.CONNECTED) prefs.lastConnectedAt = System.currentTimeMillis()
        },
        onAuthFailed = {
            Diag.warn(TAG, "token rejected; logging out")
            prefs.token = null
            authLost.tryEmit(Unit)
        },
    )

    val me: Long get() = prefs.userId
    val peerId: Long get() = prefs.peerId

    internal val sharedStore = SharedStore(this)
    internal val bots = BotRepository(this)
    internal val uploader = MediaUploader(this)
    internal val inbox = MessageRepository(this)

    init {
        rebuildSessionKey()
        sharedStore.restore()
        scope.launch {
            inbox.resetWindow()
            inbox.reactions.value = db.reactions()
            inbox.scheduledList.value = db.scheduled()
            // Local sweep for disappearing messages; the server sends del entries too, this just keeps the screen honest.
            while (true) {
                delay(30_000)
                inbox.sweepExpired()
                if (connection.value == WsClient.State.CONNECTED) runCatching { sharedStore.refresh() }
            }
        }
    }

    fun connect() {
        val token = prefs.token ?: return
        ws.connect(wsUrl(prefs.serverUrl), token)
    }

    fun disconnect() = ws.disconnect()

    fun kick() = ws.kick()

    /** Reconnect if down; otherwise ping and drop the socket if the server does not answer. */
    fun probe() = ws.probe()

    /** Wall-clock time of the last frame from the server. */
    val lastRxAt: Long get() = ws.lastRxAt

    private var battery: Int? = null
    private var charging: Boolean? = null

    /** Foreground state drives the peer's online / last-seen line, so the server hears every change. */
    fun setForeground(fg: Boolean) {
        appVisible = fg
        if (connection.value == WsClient.State.CONNECTED) ws.send(Active(fg, battery, charging))
        if (fg && prefs.token != null) scope.launch { runCatching { sharedStore.refresh() } }
    }

    /** Battery level for the peer's top bar; only re-sent on a 5 % step or a charging change. */
    fun reportBattery(level: Int, isCharging: Boolean) {
        val changed = battery == null || kotlin.math.abs((battery ?: 0) - level) >= 5 || charging != isCharging
        battery = level
        charging = isCharging
        if (changed && connection.value == WsClient.State.CONNECTED) ws.send(Active(appVisible, level, isCharging))
    }

    suspend fun login(server: String, name: String, password: String) {
        val base = server.trim().trimEnd('/')
        val r = api.login(base, name, password, Build.MODEL ?: "android")
        prefs.serverUrl = base
        prefs.token = r.token
        prefs.userId = r.user.id
        prefs.userName = r.user.name
        r.peer?.let {
            prefs.peerId = it.id
            prefs.peerName = it.name
        }
        keys.load(r.user.id)
        lastReadSent = 0
        prefs.readUpto = 0
        inbox.resetWindow()
        connect()
    }

    fun logout() {
        disconnect()
        prefs.clear()
        peerOnline.value = false
        peerReadUpto.value = 0
        peerBattery.value = null
        peerCharging.value = null
        peerTyping.value = false
        pinBot.value = false
        sessionKey = null
        e2eState.value = E2eState.OFF
        sharedStore.clear()
        bots.reset()
        scope.launch {
            db.clear()
            inbox.resetWindow()
            inbox.reactions.value = emptyMap()
        }
    }

    // ---- end-to-end keys ----

    val e2eOn: Boolean get() = sessionKey != null

    /** Safety number for the current identity keys, or null before both exist. Rotation never changes it. */
    fun safetyNumber(): String? {
        val mine = keys.identityPub() ?: prefs.e2ePub ?: return null
        val peer = keys.peerIdentityPub() ?: prefs.peerPub ?: return null
        return E2E.safetyNumber(mine, peer)
    }

    /** For the settings page: my epoch, the peer's, last rotation. */
    data class RingInfo(val epoch: Int, val peerEpoch: Int, val lastRotationAt: Long)
    fun keyRingInfo(): RingInfo = RingInfo(keys.myEpoch(), keys.peerEpoch(), prefs.lastRotationAt)

    /** Manual rotation from settings: new epoch key, published at once. */
    suspend fun rotateNow() = withContext(Dispatchers.IO) {
        keys.ensureIdentity(me)
        keys.rotateNow()
        keys.bundle()?.let { api.publishKey(it) }
        rebuildSessionKey()
    }

    /**
     * The legacy v1 key stays the "session key" (shared-store values from 1.3, media from 1.3, peers on 1.3).
     * New content uses v2 keys as soon as both sides have an epoch key; decryption looks both up.
     */
    private fun rebuildSessionKey() {
        if (keys.ring == null) keys.load(me)
        sessionKey = keys.legacyKey() ?: run {
            val priv = prefs.e2ePriv; val pub = prefs.e2ePub; val peer = prefs.peerPub
            if (priv != null && pub != null && peer != null) runCatching { E2E.derive(priv, peer, pub) }.getOrElse { Diag.warn(TAG, "key derivation failed", it); null } else null
        }
        if (sessionKey == null) e2eState.value = E2eState.OFF
        else if (e2eState.value == E2eState.OFF) e2eState.value = E2eState.ON
        // Encrypted shared values can only be read once the key exists.
        if (sessionKey != null) sharedStore.reapply()
        maybeUploadPushKeys()
    }

    private var pushedKeyFp = ""

    /** Hands the server the session keys it needs to open a ciphertext for an offline push. The identity private key stays on the phone. */
    private fun maybeUploadPushKeys() {
        if (prefs.token == null) return
        val rows = keys.exportPushKeys()
        if (rows.isEmpty()) return
        val fp = rows.joinToString("|") { "${it.first}.${it.second}." + android.util.Base64.encodeToString(it.third, android.util.Base64.NO_WRAP) }
        if (fp == pushedKeyFp) return
        scope.launch {
            runCatching {
                api.putPushKeys(rows.map { PushKeyUp(it.first, it.second, android.util.Base64.encodeToString(it.third, android.util.Base64.NO_WRAP)) })
            }.onSuccess { pushedKeyFp = fp }.onFailure { Diag.warn(TAG, "push key upload failed", it) }
        }
    }

    /**
     * Called with the Hello frame. The peer's key is adopted synchronously (cheap) so the sync that follows
     * can already decrypt; generating / publishing our own bundle happens in the background.
     */
    private fun syncKeys(myServerKey: String?, peerServerKey: String?) {
        runCatching {
            if (keys.ensureIdentity(me)) Diag.log(TAG, "generated e2e identity")
            adoptPeerKey(peerServerKey)
            rebuildSessionKey()
        }.onFailure { Diag.warn(TAG, "key adoption failed", it) }
        scope.launch {
            try {
                val myIdentity = keys.identityPub() ?: return@launch
                val serverBundle = myServerKey?.let { ink.jvm.chatter.crypto.KeyRing.Bundle.parse(it) }
                val serverIdentity = serverBundle?.identityPub
                when {
                    serverIdentity != null && serverIdentity != myIdentity -> {
                        // Another phone of mine published first. The user decides: import that key or overwrite it.
                        keyConflict.value = myServerKey
                        Diag.warn(TAG, "server holds a different key for me")
                    }
                    else -> {
                        // Re-install / pasted key: never publish an epoch below what the server already shows for me.
                        if (serverBundle != null && serverBundle.epoch > keys.myEpoch()) keys.catchUp(serverBundle.epoch)
                        val rotated = keys.maybeRotate()
                        // No key on the server, a bare legacy key, or an outdated chain: publish our current bundle.
                        if (myServerKey == null || serverBundle == null || serverBundle.isLegacy || rotated || serverBundle.epoch < keys.myEpoch()) {
                            keys.bundle()?.let { api.publishKey(it); Diag.log(TAG, "published e2e bundle (epoch ${keys.myEpoch()})") }
                            rebuildSessionKey()
                        }
                    }
                }
            } catch (e: Exception) {
                Diag.warn(TAG, "key sync failed", e)
            }
        }
    }

    private fun adoptPeerKey(peerKey: String?) {
        if (peerKey == null) return
        when (keys.adoptPeer(peerKey)) {
            KeyManager.Adopt.FIRST -> {
                prefs.peerPub = keys.peerIdentityPub()
                prefs.e2eVerified = false
                rebuildSessionKey()
                Diag.log(TAG, "adopted peer key (first time)")
            }
            KeyManager.Adopt.IDENTITY_CHANGED -> {
                prefs.peerPub = keys.peerIdentityPub()
                prefs.e2eVerified = false
                rebuildSessionKey()
                e2eState.value = E2eState.PEER_KEY_CHANGED
                Diag.warn(TAG, "peer identity changed")
            }
            KeyManager.Adopt.SAME -> {
                // Same identity, maybe a new epoch: rebuild so new messages use the freshest pair.
                rebuildSessionKey()
                // The peer just started speaking v2: publish our own chain so they can answer in v2.
                if (keys.myEpoch() == 0 && keys.peerEpoch() > 0) scope.launch { runCatching { keys.bundle()?.let { api.publishKey(it) } } }
            }
            KeyManager.Adopt.BAD -> Diag.warn(TAG, "ignored a peer key bundle that does not verify")
            KeyManager.Adopt.NONE -> {}
        }
    }

    /** User accepted the new safety number after the peer's key changed. */
    fun acknowledgePeerKey() {
        if (sessionKey != null) e2eState.value = E2eState.ON
    }

    fun setVerified(v: Boolean) { prefs.e2eVerified = v }

    /** My private key, for moving to another phone (the QR migration carries the whole ring instead). */
    fun exportKey(): String? = prefs.e2ePriv

    /** Use a key exported from another phone; republishes it and pulls the history again so old messages decrypt. */
    suspend fun importKey(priv: String): Boolean = withContext(Dispatchers.IO) {
        val pub = runCatching { E2E.publicOf(priv.trim()) }.getOrNull() ?: return@withContext false
        prefs.e2ePriv = priv.trim()
        prefs.e2ePub = pub
        prefs.keyRing = null
        keys.load(me)
        keys.ensureIdentity(me)
        keyConflict.value = null
        rebuildSessionKey()
        runCatching { keys.bundle()?.let { api.publishKey(it) } }.onFailure { Diag.warn(TAG, "publish after import failed", it) }
        resync()
        true
    }

    /** Keep this phone's key and overwrite what the server has (other phone of mine loses new messages). */
    suspend fun overwriteKey() = withContext(Dispatchers.IO) {
        runCatching { keys.bundle()?.let { api.publishKey(it) } }.onFailure { Diag.warn(TAG, "publish failed", it) }
        keyConflict.value = null
    }

    // ---- QR migration to a new phone ----

    /**
     * Phone confirmed a webpage QR. Issues a separate web token (the phone's own token stays put),
     * seals the account and key ring to the public key printed in the QR, and uploads that box.
     * The browser keeps the result in memory only.
     */
    suspend fun approveWebLogin(ticketId: String, webPub: String) = withContext(Dispatchers.IO) {
        val token = api.issueWebToken()
        val payload = ProtoJson.encodeToString(
            MigrationPayload.serializer(),
            MigrationPayload(
                server = prefs.serverUrl.trimEnd('/'), token = token, userId = me, userName = prefs.userName,
                peerId = peerId, peerName = prefs.peerName, botName = prefs.botName,
                keyRing = keys.ring?.toJson() ?: prefs.keyRing, e2ePriv = prefs.e2ePriv, e2ePub = prefs.e2ePub, peerPub = prefs.peerPub,
            ),
        )
        api.approveWebTicket(ticketId, E2E.sealWebLogin(webPub, ticketId, payload))
    }

    /** Everything the new phone needs, as JSON (the dialog encrypts it with the PIN). */
    fun migrationPayload(): String = ProtoJson.encodeToString(
        MigrationPayload.serializer(),
        MigrationPayload(
            server = prefs.serverUrl, token = prefs.token ?: "", userId = me, userName = prefs.userName,
            peerId = peerId, peerName = prefs.peerName, botName = prefs.botName,
            keyRing = keys.ring?.toJson() ?: prefs.keyRing, e2ePriv = prefs.e2ePriv, e2ePub = prefs.e2ePub, peerPub = prefs.peerPub,
        ),
    )

    /** New phone: adopt the scanned account + keys, then pull the history. False when the payload is not ours. */
    suspend fun importMigration(json: String): Boolean = withContext(Dispatchers.IO) {
        val p = runCatching { ProtoJson.decodeFromString(MigrationPayload.serializer(), json) }.getOrNull() ?: return@withContext false
        if (p.token.isBlank() || p.userId <= 0) return@withContext false
        disconnect()
        prefs.serverUrl = p.server.trimEnd('/')
        prefs.token = p.token
        prefs.userId = p.userId
        prefs.userName = p.userName
        prefs.peerId = p.peerId
        prefs.peerName = p.peerName
        prefs.botName = p.botName
        prefs.e2ePriv = p.e2ePriv
        prefs.e2ePub = p.e2ePub
        prefs.peerPub = p.peerPub
        prefs.keyRing = p.keyRing
        prefs.e2eVerified = false
        keys.load(p.userId)
        keyConflict.value = null
        lastReadSent = 0
        prefs.readUpto = 0
        rebuildSessionKey()
        db.clear()
        prefs.lastSeq = 0
        withContext(Dispatchers.Main) { inbox.resetWindow(); inbox.reactions.value = emptyMap() }
        connect()
        true
    }

    /** Wipes the local copy and pulls everything from the server again (after a key import). */
    suspend fun resync() {
        db.clear()
        prefs.lastSeq = 0
        withContext(Dispatchers.Main) { inbox.resetWindow(); inbox.reactions.value = emptyMap() }
        if (connection.value == WsClient.State.CONNECTED) ws.send(Sync(0)) else connect()
    }

    /** Encrypts for the peer: v2 (epoch keys, forward secrecy) when both sides have them, else the legacy key. */
    internal fun enc(plain: String?, aad: String, urlSafe: Boolean = false): String? {
        if (plain == null) return null
        if (keys.v2Ready()) {
            val k = keys.currentKey()
            if (k != null) return E2E.encryptTextV2(k, me, keys.myEpoch(), keys.peerEpoch(), plain, aad, urlSafe)
        }
        val key = sessionKey ?: return plain
        return E2E.encryptText(key, plain, aad, urlSafe)
    }

    /** Decrypts an incoming text of either format; keeps a readable marker when the key is missing or wrong. */
    internal fun dec(text: String?, aad: String): String? =
        MessagePipeline.decrypt(text, aad, sessionKey) { uid, a, b -> keys.keyFor(uid, a, b) }

    /** Media key + epochs for an upload (null = legacy v1 stream with [sessionKey]). */
    internal fun mediaKeyV2(): Api.MediaKey? = if (keys.v2Ready()) keys.currentKey()?.let { Api.MediaKey(it, me, keys.myEpoch(), keys.peerEpoch()) } else null
    internal fun mediaKeyFor(uid: Long, a: Int, b: Int): ByteArray? = keys.keyFor(uid, a, b)

    fun quoteText(reply: ReplyInfo): String = inbox.quoteText(reply)

    fun sendText(text: String, replyTo: String? = null, toBot: Boolean = false) = inbox.sendText(text, replyTo, toBot)
    fun sendSticker(ref: StickerRef, toBot: Boolean = false, replyTo: String? = null) = inbox.sendSticker(ref, toBot, replyTo)
    fun sendPat() = inbox.sendPat()
    val liveLocationId get() = inbox.liveLocationId
    fun sendLocation(fix: ink.jvm.chatter.util.Fix, live: Boolean, toBot: Boolean = false) = inbox.sendLocation(fix, live, toBot)
    fun stopLiveLocation() = inbox.stopLiveLocation()

    suspend fun renameBot(name: String) = bots.rename(name)
    suspend fun setBotTtl(on: Boolean) = bots.setTtl(on)

    suspend fun setQuickCommands(list: List<String>) = sharedStore.setQuickCommands(list)
    suspend fun setAnniversaries(list: List<Anniversary>) = sharedStore.setAnniversaries(list)
    suspend fun addStickerFavorite(ref: StickerRef) = sharedStore.addStickerFavorite(ref)
    suspend fun removeStickerFavorite(ref: StickerRef) = sharedStore.removeStickerFavorite(ref)
    suspend fun setPinned(on: Boolean) = sharedStore.setPinned(on)
    suspend fun setSignature(text: String) = sharedStore.setSignature(text)

    /** Pull avatar and signature again. Used when a profile screen opens. */
    suspend fun refreshShared() = sharedStore.refresh()
    suspend fun setAvatar(uri: Uri) = sharedStore.setAvatar(uri)

    fun setMarkUnreadPeer(on: Boolean) { prefs.markUnreadPeer = on; markUnreadPeer.value = on }
    fun setMarkUnreadBot(on: Boolean) { prefs.markUnreadBot = on; markUnreadBot.value = on }
    fun setPinBot(on: Boolean) { prefs.pinBot = on; pinBot.value = on }

    fun remind(preview: String, at: Long) = inbox.remind(preview, at)
    suspend fun albumMessages(): List<LocalMessage> = inbox.albumMessages()
    suspend fun addCustomSticker(uri: Uri) = sharedStore.addCustomSticker(uri)
    suspend fun askBotAbout(m: LocalMessage, question: String) = bots.askAbout(m, question)

    fun favorites(): List<Favorite> = inbox.favorites()
    fun isFavorite(id: String): Boolean = inbox.isFavorite(id)
    fun toggleFavorite(m: LocalMessage): Boolean = inbox.toggleFavorite(m)
    fun removeFavorite(id: String) = inbox.removeFavorite(id)

    fun schedule(text: String, at: Long, toBot: Boolean) = inbox.schedule(text, at, toBot)
    fun cancelScheduled(id: String) = inbox.cancelScheduled(id)
    fun fireDueScheduled(): Int = inbox.fireDueScheduled()

    suspend fun transcribe(m: LocalMessage): String? = inbox.transcribe(m)
    fun recordCall(video: Boolean, seconds: Long, bytes: Long) = inbox.recordCall(video, seconds, bytes)
    fun callStatsThisMonth(): List<CallStat> = inbox.callStatsThisMonth()

    fun viewOnceOpened(m: LocalMessage) = inbox.viewOnceOpened(m)
    fun onceSeen(id: String): Boolean = inbox.onceSeen(id)
    fun editText(id: String, newText: String) = inbox.editText(id, newText)
    suspend fun botHistory(limit: Int): List<LocalMessage> = bots.history(limit)
    fun markBotRead() = bots.markRead()
    fun setTtl(seconds: Long) = inbox.setTtl(seconds)

    fun sendMedia(uris: List<Uri>, caption: String?, original: Boolean, toBot: Boolean = false, once: Boolean = false, album: Boolean = false) =
        uploader.sendMedia(uris, caption, original, toBot, once, album)
    suspend fun sendVoice(file: java.io.File, durationMs: Int, toBot: Boolean = false) = uploader.sendVoice(file, durationMs, toBot)
    suspend fun sendFile(uri: Uri, toBot: Boolean = false) = uploader.sendFile(uri, toBot)
    fun cancelUpload(id: String) = uploader.cancelUpload(id)

    fun toggleReaction(targetId: String, emoji: String) = inbox.toggleReaction(targetId, emoji)
    fun retry(id: String) = inbox.retry(id)
    suspend fun reveal(id: String): Boolean = inbox.reveal(id)
    fun sendCallLog(text: String) = inbox.sendCallLog(text)
    fun deleteLocal(id: String) = inbox.deleteLocal(id)
    fun deleteForBoth(id: String) = inbox.deleteForBoth(id)
    val recalledTexts get() = inbox.recalledTexts
    fun recall(id: String) = inbox.recall(id)
    val voicePlayed get() = inbox.voicePlayed
    fun markPlayed(id: String) = inbox.markPlayed(id)
    suspend fun forward(m: LocalMessage, toBot: Boolean) = inbox.forward(m, toBot)
    fun clearLocal() = inbox.clearLocal()
    fun clearForBoth() = inbox.clearForBoth()

    /**
     * @param e2e encrypt SDP and ICE with the couple's session key. Assistant calls pass false: the assistant
     * has no copy of that key. The media path is still DTLS-SRTP.
     */
    fun send(frame: Frame, e2e: Boolean = true): Boolean {
        val key = sessionKey
        val out: Frame = if (!e2e || key == null) frame else when (frame) {
            is CallSdp -> frame.copy(sdp = E2E.encryptText(key, frame.sdp, frame.callId))
            is CallIce -> frame.copy(candidate = E2E.encryptText(key, frame.candidate, frame.callId))
            else -> frame
        }
        return ws.send(out)
    }

    fun sendTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent > 3000 && connection.value == WsClient.State.CONNECTED) {
            lastTypingSent = now
            ws.send(Typing())
        }
    }

    suspend fun search(q: String): List<LocalMessage> = inbox.search(q)
    suspend fun searchTyped(q: String, kinds: List<String>?, linksOnly: Boolean = false): List<LocalMessage> = inbox.searchTyped(q, kinds, linksOnly)
    suspend fun messagesOnDay(start: Long, end: Long): List<LocalMessage> = inbox.messagesOnDay(start, end)
    suspend fun allMessages(): List<LocalMessage> = inbox.allMessages()
    suspend fun mediaMessages(): List<LocalMessage> = inbox.mediaMessages()

    fun onMobileData(): Boolean {
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun markAllRead() = inbox.markRead()
    fun markRead() = inbox.markRead()
    fun loadOlder() = inbox.loadOlder()
    internal fun syncCursor(): Long = max(prefs.lastSeq, db.maxSeq())


    private fun handle(f: Frame) {
        when (f) {
            is Hello -> {
                features.value = f.features ?: Features()
                api.tilePath = if (features.value.tileDatum == "gcj02") "amap-tiles" else "tiles"
                f.peer?.let {
                    prefs.peerId = it.id
                    prefs.peerName = it.name
                    peerOnline.value = it.online
                    peerLastSeen.value = it.lastSeen
                    peerBattery.value = it.battery
                    peerCharging.value = it.charging
                }
                peerReadUpto.value = f.peerReadUpto
                lastReadSent = max(lastReadSent, f.myReadUpto)
                prefs.readUpto = lastReadSent
                prefs.ttlSeconds = f.ttlSeconds
                ttlSeconds.value = f.ttlSeconds
                f.bot?.let { bots.onHello(it) }
                f.shared?.let { list -> sharedStore.apply(list.associate { it.key to it.value }, persist = true) }
                syncKeys(f.myPubKey, f.peer?.pubKey)
                if (appVisible) ws.send(Active(true, battery, charging))
                fireDueScheduled()
                db.pending().forEach { p ->
                    val needsUpload = p.kind in MEDIA_KINDS && p.media?.id.isNullOrEmpty()
                    if (needsUpload) {
                        if (uploader.isUploading(p.id)) return@forEach // still uploading; it will send itself
                        val uri = uploader.localUri(p.id) ?: run {
                            db.markFailed(p.id) // process restarted: the picked URI is gone
                            db.get(p.id)?.let { inbox.upsertVisible(it) }
                            return@forEach
                        }
                        scope.launch { runCatching { uploader.uploadMedia(p.id, uri, image = p.kind == "image" || p.kind == "album") } }
                    } else {
                        inbox.sendStored(p)
                    }
                }
                val cursor = syncCursor()
                // Only an existing session gets catch-up notifications; a fresh login would replay old history.
                notifyAbove = if (cursor > 0) max(cursor, f.myReadUpto) else -1
                if (f.lastSeq > cursor) ws.send(Sync(cursor)) else if (chatVisible) markRead()
                widgetTick.value = widgetTick.value + 1
            }
            is BotFrame -> bots.onFrame(f)
            is SharedFrame -> sharedStore.merge(f.key, f.value)
            is Keys -> {
                if (f.user == peerId) adoptPeerKey(f.pubKey)
                else if (f.user == me) {
                    val mine = keys.identityPub()
                    val theirs = ink.jvm.chatter.crypto.KeyRing.Bundle.parse(f.pubKey)?.identityPub
                    if (mine != null && theirs != null && theirs != mine) keyConflict.value = f.pubKey
                }
            }
            is MsgAck -> {
                db.ack(f.id, f.seq, f.ts)
                if (f.seq > prefs.lastSeq) prefs.lastSeq = f.seq
                db.get(f.id)?.let { inbox.upsertVisible(it) }
            }
            is MsgNew -> {
                val fresh = inbox.apply(f.msg)
                when (f.msg.kind) {
                    "del" -> f.msg.text?.let { inbox.removeVisible(it); inbox.reactions.value = db.reactions() }
                    "recall" -> f.msg.text?.let { id -> db.get(id)?.let { inbox.upsertVisible(it) }; inbox.reactions.value = db.reactions() }
                    "clear" -> { inbox.resetWindow(); inbox.reactions.value = db.reactions() }
                    "react" -> inbox.reactions.value = db.reactions()
                    "edit" -> {
                        val target = f.msg.text?.substringBefore('|')
                        target?.let { id -> db.get(id)?.let { inbox.upsertVisible(it); if (it.fromBot) botEdited.tryEmit(it) } }
                    }
                    "ttl" -> db.get(f.msg.id)?.let { inbox.upsertVisible(it) }
                    else -> if (fresh != null) inbox.upsertVisible(fresh)
                }
                if (fresh != null) {
                    incoming.tryEmit(fresh)
                    if (chatVisible) markRead()
                }
            }
            is MsgBatch -> {
                if (f.before != null) {
                    f.messages.forEach { inbox.apply(it) }
                    inbox.prependVisible(db.before(f.before, PAGE))
                    inbox.hasOlder.value = f.hasMore
                    if (inbox.historyBefore == f.before) {
                        inbox.historyBefore = null
                        inbox.loadingOlder.value = false
                    }
                    inbox.historyWaiter?.complete(Unit)
                    inbox.historyWaiter = null
                } else {
                    // Forward catch-up persists everything, then the UI keeps only the newest page.
                    val floor = notifyAbove
                    val fresh = ArrayList<LocalMessage>()
                    f.messages.forEach { m -> inbox.apply(m)?.let { if (floor >= 0 && m.seq > floor) fresh += it } }
                    if (f.hasMore) {
                        ws.send(Sync(syncCursor(), 200))
                    } else {
                        inbox.resetWindow()
                        inbox.reactions.value = db.reactions()
                        if (chatVisible) markRead()
                    }
                    // Messages that arrived while the socket was dead still deserve a notification.
                    if (!chatVisible) fresh.takeLast(10).forEach { incoming.tryEmit(it) }
                }
            }
            is ReadMark -> {
                if (f.user == peerId && f.upto > peerReadUpto.value) peerReadUpto.value = f.upto
            }
            is Typing -> {
                if (f.user == LocalMessage.BOT_ID) { bots.noteTyping(); return }
                peerTyping.value = true
                typingJob?.cancel()
                typingJob = scope.launch {
                    delay(4000)
                    peerTyping.value = false
                }
            }
            is Presence -> {
                if (f.user == peerId) {
                    peerOnline.value = f.online
                    peerLastSeen.value = f.lastSeen
                    if (f.battery != null) peerBattery.value = f.battery
                    if (f.charging != null) peerCharging.value = f.charging
                    if (!f.online) peerTyping.value = false
                    widgetTick.value = widgetTick.value + 1
                }
            }
            is ErrorFrame -> {
                Diag.warn(TAG, "server error ${f.code}: ${f.message} (ref ${f.ref ?: "-"})")
                f.ref?.let {
                    db.markFailed(it)
                    db.get(it)?.let { m -> inbox.upsertVisible(m) }
                }
            }
            is CallSdp -> callFrames.tryEmit(f.copy(sdp = dec(f.sdp, f.callId) ?: f.sdp))
            is CallIce -> callFrames.tryEmit(f.copy(candidate = dec(f.candidate, f.callId) ?: f.candidate))
            is CallInvite, is CallAccept, is CallReject, is CallHangup, is CallMedia, is CallEmoji, is CallCaption, is TurnCreds ->
                callFrames.tryEmit(f)
            else -> {}
        }
    }

    private fun wsUrl(server: String): String {
        val s = server.trimEnd('/')
        val base = when {
            s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
            s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
            else -> "wss://$s"
        }
        return "$base/ws"
    }

    companion object {
        private const val TAG = "ChatRepository"
        const val PAGE = 40
        /** Shown in place of a text we cannot decrypt (key missing or replaced). */
        const val LOCKED = "🔒 无法解密的消息"
        internal val MEDIA_KINDS = setOf("image", "album", "file", "video", "audio")

        /** Same summary the server produces for quotes. */
        fun previewOf(m: LocalMessage): String = when (m.kind) {
            "image" -> if (m.text.isNullOrBlank()) (if (m.once) "[图片·看一次]" else "[图片]") else "[图片] " + m.text.take(100)
            "album" -> if (m.text.isNullOrBlank()) "[相册]" else "[相册] " + m.text.take(100)
            "audio" -> "[语音]"
            "video" -> if (m.once) "[视频·看一次]" else "[视频]"
            "file" -> "[文件] " + (m.media?.name ?: "")
            "call" -> "[通话]"
            "sticker" -> "[表情]"
            "pat" -> "[拍一拍]"
            "recall" -> "[已撤回]"
            "location" -> "[位置] " + (ink.jvm.chatter.util.Locator.decode(m.text)?.first?.address ?: "")
            "card" -> (m.text ?: "").lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "[播报]"
            else -> (m.text ?: "").let { if (it.length > 120) it.take(120) + "…" else it }
        }

        /** Media / sticker kinds that the media library and the "forward to assistant" action understand. */
        val MEDIA_KINDS_PUBLIC: Set<String> get() = MEDIA_KINDS
    }
}
