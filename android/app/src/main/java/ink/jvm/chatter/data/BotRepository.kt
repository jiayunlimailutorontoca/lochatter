package ink.jvm.chatter.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The in-chat assistant: name, presence, unread, and "ask about this message". */
internal class BotRepository(private val r: ChatRepository) {
    val name = MutableStateFlow(r.prefs.botName)
    val online = MutableStateFlow(false)
    val ttl = MutableStateFlow(true)
    val typing = MutableStateFlow(false)
    val tick = MutableStateFlow(0L)
    val replied = MutableStateFlow<Set<String>>(emptySet())
    val unread = MutableStateFlow(0)
    val edited = MutableSharedFlow<LocalMessage>(extraBufferCapacity = 16)
    @Volatile var visible = false
    private var typingJob: Job? = null

    fun onHello(b: BotInfo) {
        r.prefs.botName = b.name
        name.value = b.name
        online.value = b.online
        ttl.value = b.ttl
    }

    fun onFrame(f: BotFrame) {
        r.prefs.botName = f.name
        name.value = f.name
        online.value = f.online
        ttl.value = f.ttl
        ink.jvm.chatter.util.Diag.log("BotRepository", "assistant '${f.name}' ${if (f.online) "online" else "offline"}")
    }

    /** Drop assistant presence kept in memory so the next account does not inherit it. */
    fun reset() {
        typingJob?.cancel()
        typingJob = null
        typing.value = false
        online.value = false
        unread.value = 0
        replied.value = emptySet()
    }

    fun noteTyping() {
        typing.value = true
        typingJob?.cancel()
        typingJob = r.scope.launch {
            delay(8000)
            typing.value = false
        }
    }

    internal fun refresh() {
        tick.value = tick.value + 1
        replied.value = r.db.botRepliedIds()
        unread.value = if (visible) 0 else r.db.botAnswersAfter(r.prefs.botReadSeq)
    }

    suspend fun rename(newName: String) {
        val b = r.api.renameBot(newName)
        r.prefs.botName = b.name
        name.value = b.name
        online.value = b.online
        ttl.value = b.ttl
    }

    suspend fun setTtl(on: Boolean) {
        val b = r.api.setBotTtl(on)
        ttl.value = b.ttl
    }

    suspend fun history(limit: Int): List<LocalMessage> = withContext(Dispatchers.IO) { r.db.botMessages(limit) }

    fun markRead() {
        val max = r.db.maxSeqFrom(LocalMessage.BOT_ID)
        if (max > r.prefs.botReadSeq) r.prefs.botReadSeq = max
        unread.value = 0
    }

    /** Quotes a chat message at the assistant. Media is downloaded and sent again in the clear. */
    suspend fun askAbout(m: LocalMessage, question: String) {
        val q = question.trim()
        when {
            m.kind == "text" || m.kind == "card" -> r.inbox.sendText(buildString {
                append("「").append((m.text ?: "").take(2000)).append("」")
                if (q.isNotEmpty()) append("\n").append(q)
            }, toBot = true)
            m.kind == "sticker" -> StickerRef.parse(m.text)?.let { r.inbox.sendSticker(it, toBot = true) }
            m.media != null && m.media.id.isNotEmpty() -> {
                val f = ink.jvm.chatter.util.MediaSaver.fetch(r.app, r, m.media)
                when (m.kind) {
                    "image", "album", "video" -> r.uploader.sendMedia(listOf(Uri.fromFile(f)), q.ifEmpty { null }, original = false, toBot = true)
                    else -> {
                        r.uploader.sendFile(Uri.fromFile(f), toBot = true)
                        if (q.isNotEmpty()) r.inbox.sendText(q, toBot = true)
                    }
                }
            }
            else -> if (q.isNotEmpty()) r.inbox.sendText(q, toBot = true)
        }
    }
}
