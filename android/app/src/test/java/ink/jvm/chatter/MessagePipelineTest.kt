package ink.jvm.chatter

import ink.jvm.chatter.crypto.E2E
import ink.jvm.chatter.data.ChatMessage
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.MessagePipeline
import ink.jvm.chatter.data.MessageSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message apply, de-duplication, and the locked-text fallback. These run before the repository split. */
class MessagePipelineTest {
    private val me = 1L
    private val peer = 2L

    @Test
    fun plaintext_and_null_pass_through() {
        assertEquals("你好", MessagePipeline.decrypt("你好", "m", null) { _, _, _ -> null })
        assertNull(MessagePipeline.decrypt(null, "m", null) { _, _, _ -> null })
    }

    @Test
    fun v1_decrypts_with_the_session_key_and_locks_otherwise() {
        val a = E2E.generate()
        val b = E2E.generate()
        val key = E2E.derive(a.priv, b.pub, a.pub)
        val ct = E2E.encryptText(key, "hello", "id-1")
        assertTrue(ct.startsWith("e2e:"))
        assertEquals("hello", MessagePipeline.decrypt(ct, "id-1", key) { _, _, _ -> null })
        assertEquals(MessagePipeline.LOCKED, MessagePipeline.decrypt(ct, "id-1", null) { _, _, _ -> key })
        assertEquals(MessagePipeline.LOCKED, MessagePipeline.decrypt(ct, "id-1", ByteArray(32)) { _, _, _ -> null })
        assertEquals(MessagePipeline.LOCKED, MessagePipeline.decrypt(ct, "other", key) { _, _, _ -> null })
    }

    @Test
    fun v2_decrypts_via_epoch_lookup_and_locks_when_the_key_or_aad_is_wrong() {
        val mine = E2E.generate()
        val theirs = E2E.generate()
        val key = E2E.deriveV2(mine.priv, theirs.pub, mine.pub, 2, 3)
        val ct = E2E.encryptTextV2(key, 7L, 2, 3, "你好", "m1")
        assertTrue(ct.startsWith("e2e2:7:2.3:"))
        val good = MessagePipeline.decrypt(ct, "m1", null) { uid, s, r -> if (uid == 7L && s == 2 && r == 3) key else null }
        assertEquals("你好", good)
        assertEquals(MessagePipeline.LOCKED, MessagePipeline.decrypt(ct, "m1", null) { _, _, _ -> null })
        assertEquals(MessagePipeline.LOCKED, MessagePipeline.decrypt(ct, "nope", null) { _, _, _ -> key })
        // A v1 session key must not be used to open a v2 blob.
        assertEquals(MessagePipeline.LOCKED, MessagePipeline.decrypt(ct, "m1", key) { _, _, _ -> null })
    }

    @Test
    fun file_name_fallback_is_readable() {
        val a = E2E.generate()
        val b = E2E.generate()
        val key = E2E.derive(a.priv, b.pub, a.pub)
        val enc = E2E.encryptText(key, "报告.pdf", "name", urlSafe = true)
        val dec = { text: String?, aad: String -> MessagePipeline.decrypt(text, aad, key) { _, _, _ -> null } }
        assertEquals("报告.pdf", MessagePipeline.decryptName(enc, dec))
        assertEquals("加密文件", MessagePipeline.decryptName(enc) { _, _ -> MessagePipeline.LOCKED })
        assertEquals("plain.jpg", MessagePipeline.decryptName("plain.jpg", dec))
        assertNull(MessagePipeline.decryptName(null, dec))
    }

    @Test
    fun apply_stores_decrypted_text_once_and_notifies_only_the_peer() {
        val key = session()
        val sink = Mem()
        val first = msg("a", peer, "text", E2E.encryptText(key, "一", "a"), seq = 4)
        val notify = apply(sink, first, key)
        assertEquals("一", notify?.text)
        assertEquals(1, sink.rows.size)
        val again = apply(sink, msg("a", peer, "text", E2E.encryptText(key, "二", "a"), seq = 4), key)
        assertEquals("二", again?.text)
        assertEquals("same id is one row", 1, sink.rows.size)
        assertEquals("二", sink.rows["a"]?.text)
        assertNull(apply(sink, msg("b", me, "text", E2E.encryptText(key, "我", "b"), seq = 5), key))
        assertEquals(5L, sink.seq)
        apply(sink, msg("c", peer, "text", "明文", seq = 3), key)
        assertEquals("明文", sink.rows["c"]?.text)
        assertEquals("lower seq does not rewind the cursor", 5L, sink.seq)
    }

    @Test
    fun apply_control_messages_and_locked_react_is_ignored() {
        val key = session()
        val sink = Mem()
        apply(sink, msg("t", peer, "text", "原文", seq = 1, ts = 10), key)
        apply(sink, msg("loc", peer, "location", "1,2|0|公园|0", seq = 2, ts = 11), key)

        apply(sink, msg("e1", peer, "edit", "t|" + E2E.encryptText(key, "改过", "t"), seq = 3, ts = 20), key)
        assertEquals("改过", sink.rows["t"]?.text)
        assertEquals(20L, sink.rows["t"]?.editedAt)

        apply(sink, msg("e2", peer, "edit", "loc|" + E2E.encryptText(key, "1,2|0|湖|0", "loc"), seq = 4, ts = 30), key)
        assertEquals("1,2|0|湖|0", sink.rows["loc"]?.text)
        assertNull("live location edits stay unmarked", sink.rows["loc"]?.editedAt)

        apply(sink, msg("e3", me, "edit", "t|别人改", seq = 5, ts = 40), key)
        assertEquals("改过", sink.rows["t"]?.text)

        apply(sink, msg("r1", peer, "react", E2E.encryptText(key, "t|👍|1", "r1"), seq = 6), key)
        assertEquals(listOf("👍"), sink.reactions["t"])
        apply(sink, msg("r2", peer, "react", E2E.encryptText(key, "t|👍|0", "r2"), seq = 7), key)
        assertTrue(sink.reactions["t"].isNullOrEmpty())
        val foreign = E2E.encryptText(E2E.derive(E2E.generate().priv, E2E.generate().pub, E2E.generate().pub), "t|🔥|1", "r3")
        apply(sink, msg("r3", peer, "react", foreign, seq = 8), key)
        assertTrue("undecryptable react is not applied", sink.reactions["t"].isNullOrEmpty())

        apply(sink, msg("rc", peer, "recall", "t", seq = 9), key)
        assertEquals("recall", sink.rows["t"]?.kind)
        assertNull(sink.rows["t"]?.text)

        apply(sink, msg("d", peer, "del", "loc", seq = 10), key)
        assertNull(sink.rows["loc"])

        apply(sink, msg("ttl1", me, "ttl", "60", seq = 11), key)
        assertEquals(60L, sink.ttlSeconds)
        assertEquals("60", sink.rows["ttl1"]?.text)

        sink.rows["x"] = local("x", seq = 2)
        sink.rows["y"] = local("y", seq = 12)
        apply(sink, msg("cl", peer, "clear", "10", seq = 13), key)
        assertNull(sink.rows["x"])
        assertEquals("unacked-or-newer stays", "y", sink.rows["y"]?.id)
    }

    @Test
    fun pat_from_the_peer_is_reported_and_media_names_fall_back() {
        val key = session()
        val sink = Mem()
        val pats = ArrayList<String>()
        val name = E2E.encryptText(key, "a.jpg", "name", urlSafe = true)
        val media = MediaInfo("abc", "image/jpeg", 10, name = name)
        val m = ChatMessage(1, "img", peer, "image", null, media, 5)
        val notify = MessagePipeline.apply(m, me, sink, dec(key), { n -> MessagePipeline.decryptName(n, dec(key)) }) { pats.add(it.id) }
        assertEquals("img", notify?.id)
        assertEquals("a.jpg", sink.rows["img"]?.media?.name)
        assertTrue(pats.isEmpty())
        val pat = apply(sink, msg("p", peer, "pat", E2E.encryptText(key, "pat", "p"), seq = 2), key) { pats.add(it.id) }
        assertEquals("p", pat?.id)
        assertEquals(listOf("p"), pats)
        val bad = MediaInfo("abc", "image/jpeg", 10, name = E2E.encryptText(key, "a.jpg", "name", urlSafe = true))
        MessagePipeline.apply(
            ChatMessage(3, "img2", peer, "image", null, bad, 6),
            me, sink, { _, _ -> MessagePipeline.LOCKED }, { n -> MessagePipeline.decryptName(n) { _, _ -> MessagePipeline.LOCKED } },
        )
        assertEquals("加密文件", sink.rows["img2"]?.media?.name)
    }

    @Test
    fun window_dedup_orders_by_seq_and_prepend_drops_ids_already_visible() {
        var w = emptyList<LocalMessage>()
        w = MessagePipeline.upsert(w, local("a", seq = 2, ts = 20))!!
        w = MessagePipeline.upsert(w, local("b", seq = 1, ts = 50))!!
        w = MessagePipeline.upsert(w, local("p", seq = null, ts = 1))!!
        assertEquals(listOf("b", "a", "p"), w.map { it.id })
        w = MessagePipeline.upsert(w, local("a", seq = 2, ts = 20, text = "新"))!!
        assertEquals(3, w.size)
        assertEquals("新", w[1].text)
        assertNull(MessagePipeline.upsert(w, local("ctl", kind = "del", text = "a")))
        assertNull(MessagePipeline.upsert(w, local("rc", kind = "recall", text = "a")))
        val shown = MessagePipeline.upsert(w, local("ph", kind = "recall", text = null, seq = 9))
        assertEquals(listOf("b", "a", "ph", "p"), shown!!.map { it.id })
        val older = listOf(local("z", seq = 0), local("b", seq = 1), local("b", seq = 1))
        val next = MessagePipeline.prepend(w, older)
        assertEquals("z", next.first().id)
        assertEquals(1, next.count { it.id == "b" })
        assertTrue(MessagePipeline.prepend(w, emptyList()) === w)
        assertTrue(MessagePipeline.prepend(w, listOf(local("b", seq = 1))) === w)
    }

    private fun session(): ByteArray {
        val a = E2E.generate()
        val b = E2E.generate()
        return E2E.derive(a.priv, b.pub, a.pub)
    }

    private fun dec(key: ByteArray): (String?, String) -> String? =
        { text, aad -> MessagePipeline.decrypt(text, aad, key) { _, _, _ -> null } }

    private fun apply(sink: Mem, m: ChatMessage, key: ByteArray, onPat: (LocalMessage) -> Unit = {}): LocalMessage? =
        MessagePipeline.apply(m, me, sink, dec(key), { n -> MessagePipeline.decryptName(n, dec(key)) }, onPat)

    private fun msg(id: String, from: Long, kind: String, text: String?, seq: Long = 1, ts: Long = 1) =
        ChatMessage(seq, id, from, kind, text, ts = ts)

    private fun local(id: String, seq: Long? = 1, ts: Long = 1, text: String? = null, kind: String = "text") =
        LocalMessage(id, seq, peer, kind, text, null, ts, LocalMessage.SENT)

    private class Mem : MessageSink {
        val rows = LinkedHashMap<String, LocalMessage>()
        val reactions = HashMap<String, MutableList<String>>()
        var ttlSeconds = -1L
        var seq = 0L
        override fun delete(id: String) { rows.remove(id); reactions.remove(id) }
        override fun recall(id: String) {
            val m = rows[id] ?: return
            if (m.kind == "del" || m.kind == "clear" || m.kind == "recall") return
            rows[id] = m.copy(kind = "recall", text = null, media = null)
            reactions.remove(id)
        }
        override fun clearUpTo(seq: Long) {
            val gone = rows.filterValues { it.seq != null && it.seq <= seq }.keys.toList()
            gone.forEach { rows.remove(it); reactions.remove(it) }
        }
        override fun react(target: String, from: Long, emoji: String, on: Boolean) {
            val list = reactions.getOrPut(target) { ArrayList() }
            if (on) { if (emoji !in list) list.add(emoji) } else list.remove(emoji)
        }
        override fun get(id: String) = rows[id]
        override fun updateText(id: String, text: String, editedAt: Long?) {
            val m = rows[id] ?: return
            rows[id] = if (editedAt == null) m.copy(text = text) else m.copy(text = text, editedAt = editedAt)
        }
        override fun upsert(m: LocalMessage) { rows[m.id] = m }
        override fun setTtl(seconds: Long) { ttlSeconds = seconds }
        override fun lastSeq() = seq
        override fun setLastSeq(seq: Long) { this.seq = seq }
    }
}
