package ink.jvm.chatter.data

import android.net.Uri
import ink.jvm.chatter.crypto.E2E
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * The conversation itself: the on-screen window, applying server messages, and everything
 * that is a message rather than a socket, an upload, or the assistant.
 */
internal class MessageRepository(private val r: ChatRepository) {
    private val _messages = MutableStateFlow<List<LocalMessage>>(emptyList())
    val messages: StateFlow<List<LocalMessage>> = _messages.asStateFlow()
    val hasOlder = MutableStateFlow(false)
    val loadingOlder = MutableStateFlow(false)
    val reactions = MutableStateFlow<Map<String, List<Reaction>>>(emptyMap())
    val scheduledList = MutableStateFlow<List<Scheduled>>(emptyList())
    val transcripts = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveLocationId = MutableStateFlow<String?>(null)
    val voicePlayed = MutableStateFlow<Set<String>>(emptySet())
    val recalledTexts = ConcurrentHashMap<String, String>()

    @Volatile var historyBefore: Long? = null
    var historyWaiter: CompletableDeferred<Unit>? = null
    private var liveJob: kotlinx.coroutines.Job? = null
    private var liveId: String? = null

    private val sink = object : MessageSink {
        override fun delete(id: String) = r.db.delete(id)
        override fun recall(id: String) = r.db.recall(id)
        override fun clearUpTo(seq: Long) = r.db.clearUpTo(seq)
        override fun react(target: String, from: Long, emoji: String, on: Boolean) = r.db.react(target, from, emoji, on)
        override fun get(id: String) = r.db.get(id)
        override fun updateText(id: String, text: String, editedAt: Long?) = r.db.updateText(id, text, editedAt)
        override fun upsert(m: LocalMessage) = r.db.upsert(m)
        override fun setTtl(seconds: Long) {
            r.prefs.ttlSeconds = seconds
            r.ttlSeconds.value = seconds
        }
        override fun lastSeq() = r.prefs.lastSeq
        override fun setLastSeq(seq: Long) { r.prefs.lastSeq = seq }
    }

    init {
        r.scope.launch { runCatching { voicePlayed.value = r.db.playedIds() } }
    }

    internal fun expiry(toBot: Boolean = false): Long? {
        if (toBot && !r.bots.ttl.value) return null
        val ttl = r.ttlSeconds.value
        return if (ttl > 0) System.currentTimeMillis() + ttl * 1000 else null
    }

    fun apply(m: ChatMessage): LocalMessage? = MessagePipeline.apply(
        m, r.me, sink, r::dec, { name -> MessagePipeline.decryptName(name, r::dec) },
    ) { r.patIncoming.tryEmit(it) }

    fun upsertVisible(m: LocalMessage) {
        val next = MessagePipeline.upsert(_messages.value, m) ?: return
        _messages.value = next
        if (m.fromBot || m.toBot) r.bots.refresh()
        r.widgetTick.value = r.widgetTick.value + 1
    }

    fun removeVisible(id: String) {
        val next = _messages.value.filter { it.id != id }
        if (next.size != _messages.value.size) _messages.value = next
        r.bots.refresh()
    }

    fun prependVisible(older: List<LocalMessage>) {
        val next = MessagePipeline.prepend(_messages.value, older)
        if (next !== _messages.value) _messages.value = next
    }

    fun resetWindow() {
        val page = r.db.recent(ChatRepository.PAGE)
        _messages.value = page
        val oldest = page.firstOrNull { it.seq != null }?.seq
        hasOlder.value = oldest != null && r.db.hasOlderThan(oldest)
        loadingOlder.value = false
        historyBefore = null
        r.bots.refresh()
    }

    fun sweepExpired() {
        val gone = r.db.deleteExpired(System.currentTimeMillis())
        if (gone.isEmpty()) return
        val set = gone.toHashSet()
        _messages.value = _messages.value.filter { it.id !in set }
        reactions.value = r.db.reactions()
    }

    fun quoteText(reply: ReplyInfo): String {
        r.db.get(reply.id)?.let { return ChatRepository.previewOf(it) }
        if (!E2E.isEncrypted(reply.text)) return reply.text
        val plain = r.dec(reply.text, reply.id) ?: return MessagePipeline.LOCKED
        if (plain == MessagePipeline.LOCKED) return MessagePipeline.LOCKED
        return if (plain.length > 120) plain.take(120) + "…" else plain
    }

    fun sendText(text: String, replyTo: String? = null, toBot: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        val reply = replyTo?.let { r.db.get(it) }?.let { ReplyInfo(it.id, it.from, ChatRepository.previewOf(it)) }
        val id = UUID.randomUUID().toString()
        val dest = if (toBot) "bot" else null
        val m = LocalMessage(id, null, r.me, "text", t, null, System.currentTimeMillis(), LocalMessage.PENDING, reply, expiresAt = expiry(toBot), to = dest)
        r.scope.launch {
            r.db.upsert(m)
            upsertVisible(m)
            r.ws.send(MsgSend(m.id, "text", if (toBot) t else r.enc(t, id), replyTo = reply?.id, to = dest, notice = PushNotice.of("text", t)))
        }
    }

    fun sendSticker(ref: StickerRef, toBot: Boolean = false, replyTo: String? = null) {
        val id = UUID.randomUUID().toString()
        val dest = if (toBot) "bot" else null
        val wire = ref.encode()
        val reply = replyTo?.let { r.db.get(it) }?.let { ReplyInfo(it.id, it.from, ChatRepository.previewOf(it)) }
        val mediaId = (ref as? StickerRef.Media)?.mediaId
        val m = LocalMessage(id, null, r.me, "sticker", wire, null, System.currentTimeMillis(), LocalMessage.PENDING, reply, expiresAt = expiry(toBot), to = dest)
        r.stickers.touch(ref)
        r.scope.launch {
            r.db.upsert(m)
            upsertVisible(m)
            r.ws.send(MsgSend(m.id, "sticker", if (toBot) wire else r.enc(wire, id), mediaId, reply?.id, dest, notice = PushNotice.of("sticker", null)))
        }
    }

    fun sendPat() {
        val id = UUID.randomUUID().toString()
        val m = LocalMessage(id, null, r.me, "pat", "pat", null, System.currentTimeMillis(), LocalMessage.PENDING, expiresAt = expiry(false))
        r.scope.launch {
            r.db.upsert(m)
            upsertVisible(m)
            r.ws.send(MsgSend(m.id, "pat", r.enc("pat", id), notice = PushNotice.of("pat", null)))
        }
    }

    fun sendLocation(fix: ink.jvm.chatter.util.Fix, live: Boolean, toBot: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val dest = if (toBot) "bot" else null
        val text = ink.jvm.chatter.util.Locator.encode(fix, live && !toBot)
        val m = LocalMessage(id, null, r.me, "location", text, null, System.currentTimeMillis(), LocalMessage.PENDING, expiresAt = expiry(toBot), to = dest)
        r.scope.launch {
            r.db.upsert(m)
            upsertVisible(m)
            r.ws.send(MsgSend(m.id, "location", if (toBot) text else r.enc(text, id), to = dest, notice = PushNotice.of("location", text)))
        }
        if (live && !toBot) startLiveLocation(id)
    }

    private fun startLiveLocation(id: String) {
        stopLiveLocation()
        liveId = id
        liveLocationId.value = id
        liveJob = r.scope.launch {
            val end = System.currentTimeMillis() + 15 * 60_000L
            try {
                ink.jvm.chatter.util.Locator.liveUpdates(r.app, 10_000).collect { fix ->
                    if (System.currentTimeMillis() > end) throw kotlinx.coroutines.CancellationException("done")
                    updateLocation(id, ink.jvm.chatter.util.Locator.encode(fix, true))
                }
            } finally {
                r.db.get(id)?.let { last ->
                    ink.jvm.chatter.util.Locator.decode(last.text)?.let { (f, _) -> updateLocation(id, ink.jvm.chatter.util.Locator.encode(f, false)) }
                }
                if (liveId == id) { liveId = null; liveLocationId.value = null }
            }
        }
    }

    fun stopLiveLocation() {
        liveJob?.cancel()
        liveJob = null
    }

    private fun updateLocation(id: String, text: String) {
        val m = r.db.get(id) ?: return
        if (m.from != r.me || m.kind != "location") return
        val now = System.currentTimeMillis()
        r.db.updateText(id, text, now)
        r.db.get(id)?.let { upsertVisible(it) }
        control("edit", id + "|" + (if (m.toBot) text else (r.enc(text, id) ?: text)))
    }

    fun remind(preview: String, at: Long) {
        val clean = preview.replace("|", " ").replace("\n", " ").take(80).ifBlank { "一条消息" }
        val cur = r.prefs.reminders.lineSequence().filter { it.isNotBlank() }.toMutableList()
        cur.add("$at|$clean")
        r.prefs.reminders = cur.joinToString("\n")
        ink.jvm.chatter.service.ReminderReceiver.arm(r.app)
    }

    suspend fun albumMessages(): List<LocalMessage> = withContext(Dispatchers.IO) { r.db.albumMessages() }

    fun favorites(): List<Favorite> = r.db.favorites()
    fun isFavorite(id: String): Boolean = r.db.isFavorite(id)
    fun toggleFavorite(m: LocalMessage): Boolean {
        val on = r.db.isFavorite(m.id)
        if (on) r.db.removeFavorite(m.id) else r.db.addFavorite(m)
        return !on
    }
    fun removeFavorite(id: String) = r.db.removeFavorite(id)

    fun schedule(text: String, at: Long, toBot: Boolean) {
        val s = Scheduled(UUID.randomUUID().toString(), text.trim(), at, toBot)
        if (s.text.isEmpty()) return
        r.db.addScheduled(s)
        scheduledList.value = r.db.scheduled()
        ink.jvm.chatter.service.ScheduledSendReceiver.arm(r.app, scheduledList.value)
    }

    fun cancelScheduled(id: String) {
        r.db.removeScheduled(id)
        scheduledList.value = r.db.scheduled()
        ink.jvm.chatter.service.ScheduledSendReceiver.arm(r.app, scheduledList.value)
    }

    fun fireDueScheduled(): Int {
        val now = System.currentTimeMillis() + 1500
        var n = 0
        for (s in r.db.scheduled()) if (s.at <= now) {
            sendText(s.text, toBot = s.toBot)
            r.db.removeScheduled(s.id)
            n++
        }
        scheduledList.value = r.db.scheduled()
        ink.jvm.chatter.service.ScheduledSendReceiver.arm(r.app, scheduledList.value)
        return n
    }

    suspend fun transcribe(m: LocalMessage): String? {
        r.db.transcript(m.id)?.let { return it }
        val media = m.media ?: return null
        val f = ink.jvm.chatter.util.MediaSaver.fetch(r.app, r, media)
        val text = ink.jvm.chatter.media.Speech.transcribe(r.app, r, f)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        r.db.setTranscript(m.id, text)
        transcripts.value = transcripts.value + (m.id to text)
        return text
    }

    fun recordCall(video: Boolean, seconds: Long, bytes: Long) {
        r.db.addCallStat(CallStat(System.currentTimeMillis(), video, seconds, bytes))
    }

    fun callStatsThisMonth(): List<CallStat> {
        val start = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return r.db.callStatsSince(start)
    }

    fun viewOnceOpened(m: LocalMessage) {
        if (!m.once || m.from == r.me || r.db.onceSeen(m.id)) return
        r.db.markOnceSeen(m.id)
        r.scope.launch {
            delay(10_000)
            deleteForBoth(m.id)
        }
    }

    fun onceSeen(id: String): Boolean = r.db.onceSeen(id)

    fun editText(id: String, newText: String) {
        val t = newText.trim()
        if (t.isEmpty()) return
        r.scope.launch {
            val m = r.db.get(id) ?: return@launch
            if (m.from != r.me || m.kind != "text" || m.text == t) return@launch
            val now = System.currentTimeMillis()
            r.db.updateText(id, t, now)
            r.db.get(id)?.let { upsertVisible(it) }
            control("edit", id + "|" + (if (m.toBot) t else (r.enc(t, id) ?: t)))
        }
    }

    fun setTtl(seconds: Long) {
        r.prefs.ttlSeconds = seconds
        r.ttlSeconds.value = seconds
        val m = LocalMessage(UUID.randomUUID().toString(), null, r.me, "ttl", seconds.toString(), null, System.currentTimeMillis(), LocalMessage.PENDING)
        r.scope.launch {
            r.db.upsert(m)
            upsertVisible(m)
            r.ws.send(MsgSend(m.id, "ttl", m.text))
        }
    }

    fun toggleReaction(targetId: String, emoji: String) {
        val mine = reactions.value[targetId].orEmpty().any { it.from == r.me && it.emoji == emoji }
        r.scope.launch {
            r.db.react(targetId, r.me, emoji, !mine)
            reactions.value = r.db.reactions()
            control("react", "$targetId|$emoji|${if (mine) 0 else 1}", encrypt = true)
        }
    }

    fun retry(id: String) {
        r.scope.launch {
            val m = r.db.get(id) ?: return@launch
            if (m.status != LocalMessage.FAILED && m.status != LocalMessage.PENDING) return@launch
            r.db.markPending(id)
            r.db.get(id)?.let { upsertVisible(it) }
            when {
                m.kind in ChatRepository.MEDIA_KINDS -> {
                    if (!m.media?.id.isNullOrEmpty()) {
                        sendStored(m)
                    } else {
                        val uri = r.uploader.localUri(id)
                        if (uri == null) {
                            r.db.markFailed(id)
                            r.db.get(id)?.let { upsertVisible(it) }
                            return@launch
                        }
                        runCatching { r.uploader.uploadMedia(id, uri, image = m.kind == "image" || m.kind == "album") }
                            .onFailure {
                                r.db.markFailed(id)
                                r.db.get(id)?.let { upsertVisible(it) }
                            }
                    }
                }
                else -> sendStored(m)
            }
        }
    }

    fun sendStored(m: LocalMessage) {
        val text = when {
            m.toBot -> m.text
            else -> when (m.kind) {
                "text", "image", "album", "video", "file", "audio", "call", "sticker", "pat", "location" -> r.enc(m.text, m.id)
                "react" -> r.enc(m.text, m.id)
                "edit" -> m.text?.let { t ->
                    val bar = t.indexOf('|')
                    if (bar > 0) t.substring(0, bar) + "|" + (r.enc(t.substring(bar + 1), t.substring(0, bar)) ?: t.substring(bar + 1)) else t
                }
                else -> m.text
            }
        }
        val mediaId = m.media?.id?.takeIf { it.isNotEmpty() } ?: (StickerRef.parse(m.text) as? StickerRef.Media)?.mediaId
        r.ws.send(MsgSend(m.id, m.kind, text, mediaId, m.reply?.id, m.to, once = if (m.once) true else null, notice = PushNotice.of(m.kind, m.text, m.once)))
    }

    suspend fun reveal(id: String): Boolean = withContext(Dispatchers.Default) {
        fun visible() = _messages.value.any { it.id == id }
        if (visible()) return@withContext true
        while (!visible()) {
            val oldest = _messages.value.firstOrNull { it.seq != null }?.seq ?: break
            val local = r.db.before(oldest, ChatRepository.PAGE)
            if (local.isEmpty()) break
            prependVisible(local)
            if (local.size < ChatRepository.PAGE) break
        }
        if (visible()) return@withContext true
        if (r.db.get(id) != null) return@withContext visible()
        var guard = 0
        while (!visible() && r.connection.value == WsClient.State.CONNECTED && guard++ < 25) {
            val oldest = _messages.value.firstOrNull { it.seq != null }?.seq ?: break
            val waiter = CompletableDeferred<Unit>()
            historyWaiter = waiter
            historyBefore = oldest
            loadingOlder.value = true
            r.ws.send(Sync(0, ChatRepository.PAGE, oldest))
            if (withTimeoutOrNull(8_000) { waiter.await() } == null) return@withContext false
            if (!hasOlder.value) break
        }
        visible()
    }

    fun sendCallLog(text: String) {
        val id = UUID.randomUUID().toString()
        val m = LocalMessage(id, null, r.me, "call", text, null, System.currentTimeMillis(), LocalMessage.PENDING, expiresAt = expiry())
        r.scope.launch {
            r.db.upsert(m)
            upsertVisible(m)
            r.ws.send(MsgSend(m.id, "call", r.enc(text, id), notice = PushNotice.of("call", text)))
        }
    }

    fun deleteLocal(id: String) = r.scope.launch {
        r.db.delete(id)
        removeVisible(id)
    }

    fun deleteForBoth(id: String) = r.scope.launch {
        r.db.delete(id)
        removeVisible(id)
        control("del", id)
    }

    fun recall(id: String) = r.scope.launch {
        val m = r.db.get(id) ?: return@launch
        if (m.kind == "text") recalledTexts[id] = m.text ?: ""
        r.db.recall(id)
        r.db.get(id)?.let { upsertVisible(it) }
        control("recall", id)
    }

    fun markPlayed(id: String) {
        if (id in voicePlayed.value) return
        voicePlayed.value = voicePlayed.value + id
        r.scope.launch { r.db.markPlayed(id) }
    }

    suspend fun forward(m: LocalMessage, toBot: Boolean) {
        when (m.kind) {
            "text", "card" -> sendText(m.text ?: return, null, toBot)
            "sticker" -> StickerRef.parse(m.text)?.let { sendSticker(it, toBot) }
            "location" -> ink.jvm.chatter.util.Locator.decode(m.text)?.let { (fix, _) -> sendLocation(fix, false, toBot) }
            "image", "album", "video", "file", "audio" -> {
                val media = m.media?.takeIf { it.id.isNotEmpty() } ?: return
                val f = ink.jvm.chatter.util.MediaSaver.fetch(r.app, r, media)
                val uri = androidx.core.content.FileProvider.getUriForFile(r.app, r.app.packageName + ".files", f)
                when (m.kind) {
                    "image", "album", "video" -> r.uploader.sendMedia(listOf(uri), null, original = true, toBot = toBot, album = m.kind == "album")
                    "audio" -> r.uploader.sendVoice(f, media.durationMs ?: 0, toBot)
                    else -> r.uploader.sendFile(uri, toBot)
                }
            }
        }
    }

    fun clearLocal() = r.scope.launch {
        r.prefs.lastSeq = r.syncCursor()
        r.db.clear()
        resetWindow()
        hasOlder.value = false
    }

    fun clearForBoth() = r.scope.launch {
        r.prefs.lastSeq = r.syncCursor()
        r.db.clear()
        resetWindow()
        hasOlder.value = false
        control("clear", null)
    }

    fun control(kind: String, text: String?, encrypt: Boolean = false, aadOverride: String? = null) {
        val id = UUID.randomUUID().toString()
        val m = LocalMessage(id, null, r.me, kind, text, null, System.currentTimeMillis(), LocalMessage.PENDING)
        r.db.upsert(m)
        val wire = if (encrypt) r.enc(text, aadOverride ?: id) else text
        r.ws.send(MsgSend(m.id, kind, wire))
    }

    suspend fun search(q: String): List<LocalMessage> = withContext(Dispatchers.IO) { r.db.search(q) }
    suspend fun searchTyped(q: String, kinds: List<String>?, linksOnly: Boolean = false): List<LocalMessage> =
        withContext(Dispatchers.IO) { r.db.searchTyped(q, kinds, linksOnly) }
    suspend fun messagesOnDay(start: Long, end: Long): List<LocalMessage> = withContext(Dispatchers.IO) { r.db.onDay(start, end) }
    suspend fun allMessages(): List<LocalMessage> = withContext(Dispatchers.IO) { r.db.all() }
    suspend fun mediaMessages(): List<LocalMessage> = withContext(Dispatchers.IO) { r.db.mediaMessages() }

    fun markRead() {
        val maxSeq = max(r.db.maxSeqFrom(r.peerId), r.db.maxSeqFrom(LocalMessage.BOT_ID))
        if (maxSeq > r.lastReadSent && r.connection.value == WsClient.State.CONNECTED) {
            r.lastReadSent = maxSeq
            r.prefs.readUpto = maxSeq
            r.ws.send(ReadMark(maxSeq))
        }
    }

    fun loadOlder() {
        if (loadingOlder.value || !hasOlder.value) return
        val oldest = _messages.value.firstOrNull { it.seq != null }?.seq ?: return
        loadingOlder.value = true
        r.scope.launch {
            val local = r.db.before(oldest, ChatRepository.PAGE)
            if (local.isNotEmpty()) prependVisible(local)
            if (local.size < ChatRepository.PAGE && r.connection.value == WsClient.State.CONNECTED) {
                historyBefore = oldest
                r.ws.send(Sync(0, ChatRepository.PAGE, oldest))
            } else {
                hasOlder.value = local.size >= ChatRepository.PAGE || (local.isEmpty() && r.connection.value != WsClient.State.CONNECTED)
                loadingOlder.value = false
            }
        }
    }
}
