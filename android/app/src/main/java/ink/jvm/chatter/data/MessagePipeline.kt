package ink.jvm.chatter.data

import ink.jvm.chatter.crypto.E2E

/**
 * Message apply, window de-duplication, and decrypt fallback.
 * No Android and no database: [ChatRepository] supplies the sink and the keys.
 * Covered by JVM tests before the repository was split.
 */
internal object MessagePipeline {
    const val LOCKED = "🔒 无法解密的消息"

    /**
     * Plaintext passes through. A v2 blob is opened with [keyFor]; a v1 blob with [sessionKey].
     * Missing or rejected keys become [LOCKED] so the bubble stays readable.
     */
    fun decrypt(
        text: String?,
        aad: String,
        sessionKey: ByteArray?,
        keyFor: (uid: Long, senderEpoch: Int, receiverEpoch: Int) -> ByteArray?,
    ): String? {
        if (text == null || !E2E.isEncrypted(text)) return text
        if (text.startsWith(E2E.PREFIX_V2)) return E2E.decryptTextV2(text, aad, keyFor) ?: LOCKED
        val key = sessionKey ?: return LOCKED
        return E2E.decryptText(key, text, aad) ?: LOCKED
    }

    /** File names use AAD "name". A failure is still something the UI can show. */
    fun decryptName(name: String?, decrypt: (String?, String) -> String?): String? {
        if (name == null || !E2E.isEncrypted(name)) return name
        val plain = decrypt(name, "name")
        return if (plain == null || plain == LOCKED) "加密文件" else plain
    }

    /**
     * Applies one server message. Returns it when it is a new visible message from someone else.
     * Pats from someone else are handed to [onPat].
     */
    fun apply(
        m: ChatMessage,
        me: Long,
        sink: MessageSink,
        dec: (String?, String) -> String?,
        decName: (String?) -> String?,
        onPat: (LocalMessage) -> Unit = {},
    ): LocalMessage? {
        var notify: LocalMessage? = null
        when (m.kind) {
            "del" -> m.text?.let { sink.delete(it) }
            "recall" -> m.text?.let { sink.recall(it) }
            "clear" -> m.text?.toLongOrNull()?.let { sink.clearUpTo(it) }
            "react" -> {
                val parts = (dec(m.text, m.id) ?: "").split('|')
                if (parts.size == 3) sink.react(parts[0], m.from, parts[1], parts[2] == "1")
            }
            "edit" -> {
                val t = m.text ?: ""
                val bar = t.indexOf('|')
                if (bar > 0) {
                    val target = t.substring(0, bar)
                    val newText = dec(t.substring(bar + 1), target) ?: ""
                    sink.get(target)?.let { if (it.from == m.from) sink.updateText(target, newText, if (it.kind == "location") null else m.ts) }
                }
            }
            "ttl" -> {
                sink.setTtl(m.text?.toLongOrNull() ?: 0)
                sink.upsert(m.toLocal())
            }
            else -> {
                val lm = m.toLocal().let { it.copy(text = dec(it.text, it.id), media = it.media?.copy(name = decName(it.media.name))) }
                sink.upsert(lm)
                if (lm.from != me) notify = lm
                if (lm.kind == "pat" && lm.from != me) onPat(lm)
            }
        }
        if (m.seq > sink.lastSeq()) sink.setLastSeq(m.seq)
        return notify
    }

    /** Same id replaces in place. A new id is ordered by seq, then timestamp. Control rows stay off screen. */
    fun upsert(cur: List<LocalMessage>, m: LocalMessage): List<LocalMessage>? {
        if (m.isControl) return null
        val i = cur.indexOfFirst { it.id == m.id }
        return if (i >= 0) {
            cur.toMutableList().also { it[i] = m }
        } else {
            (cur + m).sortedWith(compareBy({ it.seq ?: Long.MAX_VALUE }, { it.ts }))
        }
    }

    /** Older pages go in front. An id already on screen is dropped so a sync overlap cannot duplicate it. */
    fun prepend(cur: List<LocalMessage>, older: List<LocalMessage>): List<LocalMessage> {
        if (older.isEmpty()) return cur
        val have = cur.map { it.id }.toHashSet()
        val extra = older.filter { it.id !in have }
        return if (extra.isEmpty()) cur else extra + cur
    }
}

/** Storage [MessagePipeline.apply] is allowed to touch. The phone database and the unit-test memory both implement it. */
internal interface MessageSink {
    fun delete(id: String)
    fun recall(id: String)
    fun clearUpTo(seq: Long)
    fun react(target: String, from: Long, emoji: String, on: Boolean)
    fun get(id: String): LocalMessage?
    fun updateText(id: String, text: String, editedAt: Long?)
    fun upsert(m: LocalMessage)
    fun setTtl(seconds: Long)
    fun lastSeq(): Long
    fun setLastSeq(seq: Long)
}
