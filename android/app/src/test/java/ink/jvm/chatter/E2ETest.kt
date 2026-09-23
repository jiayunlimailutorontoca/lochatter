package ink.jvm.chatter

import ink.jvm.chatter.crypto.E2E
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class E2ETest {
    private val a = E2E.generate()
    private val b = E2E.generate()

    @Test
    fun both_sides_derive_the_same_key_and_safety_number() {
        val ka = E2E.derive(a.priv, b.pub, a.pub)
        val kb = E2E.derive(b.priv, a.pub, b.pub)
        assertArrayEquals(ka, kb)
        assertEquals(32, ka.size)
        assertEquals(E2E.safetyNumber(a.pub, b.pub), E2E.safetyNumber(b.pub, a.pub))
        assertEquals(60, E2E.safetyNumber(a.pub, b.pub).count { it.isDigit() })
    }

    @Test
    fun public_key_recovers_from_private_key() {
        assertEquals(a.pub, E2E.publicOf(a.priv))
        assertTrue(E2E.isValidPublic(a.pub))
        assertTrue(!E2E.isValidPublic("nope"))
    }

    @Test
    fun text_round_trip_and_aad_binding() {
        val key = E2E.derive(a.priv, b.pub, a.pub)
        val ct = E2E.encryptText(key, "你好 👋", "msg-1")
        assertTrue(ct.startsWith("e2e:"))
        assertEquals("你好 👋", E2E.decryptText(key, ct, "msg-1"))
        assertNull("wrong message id must fail", E2E.decryptText(key, ct, "msg-2"))
        assertNull("wrong key must fail", E2E.decryptText(E2E.derive(b.priv, E2E.generate().pub, b.pub), ct, "msg-1"))
        assertNotEquals(ct, E2E.encryptText(key, "你好 👋", "msg-1"))
        val url = E2E.encryptText(key, "报告 v2.pdf", "name", urlSafe = true)
        assertTrue(!url.contains('/') && !url.contains('+'))
        assertEquals("报告 v2.pdf", E2E.decryptText(key, url, "name"))
    }

    @Test
    fun stream_round_trip_sizes_and_tamper_detection() {
        val key = E2E.derive(a.priv, b.pub, a.pub)
        for (n in listOf(0, 1, 1000, (1 shl 20) - 1, 1 shl 20, (1 shl 20) + 1, 3 * (1 shl 20) + 12345)) {
            val plain = Random(n).nextBytes(n)
            val out = ByteArrayOutputStream()
            E2E.encryptStream(key, plain.inputStream(), out)
            val blob = out.toByteArray()
            assertEquals("size for n=$n", E2E.encryptedSize(n.toLong()), blob.size.toLong())
            assertTrue(E2E.isEncryptedStream(blob.copyOf(4)))
            assertArrayEquals(plain, E2E.decryptBytes(key, blob))
            if (n > 0) {
                val bad = blob.copyOf(); bad[blob.size / 2] = (bad[blob.size / 2].toInt() xor 1).toByte()
                assertTrue(runCatching { E2E.decryptBytes(key, bad) }.isFailure)
                assertTrue("truncation must fail", runCatching { E2E.decryptBytes(key, blob.copyOf(blob.size - 20)) }.isFailure)
            }
        }
    }

    // ---- v2: epoch keys, signatures, headers ----

    @Test
    fun sign_and_verify_with_the_identity_key() {
        val data = E2E.epochSignData(3, b.pub)
        assertArrayEquals("lochatter-epoch|3|${b.pub}".toByteArray(), data)
        val sig = E2E.sign(a.priv, data)
        assertTrue(E2E.verify(a.pub, data, sig))
        assertTrue("another identity must fail", !E2E.verify(b.pub, data, sig))
        assertTrue("other data must fail", !E2E.verify(a.pub, E2E.epochSignData(4, b.pub), sig))
        val bad = E2E.unb64(sig); bad[bad.size / 2] = (bad[bad.size / 2].toInt() xor 1).toByte()
        assertTrue("flipped bit must fail", !E2E.verify(a.pub, data, E2E.b64(bad)))
        assertTrue("garbage must not throw", !E2E.verify(a.pub, data, "not base64!"))
        assertTrue(!E2E.verify(a.pub, data, ""))
        assertTrue(!E2E.verify("nope", data, sig))
        assertNotEquals("ECDSA signatures are randomised", sig, E2E.sign(a.priv, data))
    }

    @Test
    fun v2_key_is_symmetric_and_bound_to_the_direction() {
        val ea = E2E.generate(); val eb = E2E.generate() // epoch keys
        val aToB = E2E.deriveV2(ea.priv, eb.pub, ea.pub, 3, 5) // A at epoch 3 sends to B at epoch 5
        val bReads = E2E.deriveV2(eb.priv, ea.pub, eb.pub, 3, 5)
        assertArrayEquals(aToB, bReads)
        assertEquals(32, aToB.size)
        val bToA = E2E.deriveV2(eb.priv, ea.pub, eb.pub, 5, 3)
        assertTrue("the other direction has its own key", !aToB.contentEquals(bToA))
        assertTrue("v2 differs from v1 over the same pairs", !aToB.contentEquals(E2E.derive(ea.priv, eb.pub, ea.pub)))
        assertTrue(runCatching { E2E.deriveV2(ea.priv, eb.pub, ea.pub, 0, 1) }.isFailure)
    }

    @Test
    fun v2_text_round_trip_through_a_key_lookup() {
        val key = E2E.derive(a.priv, b.pub, a.pub) // any 32-byte key does for the framing
        val ct = E2E.encryptTextV2(key, 42L, 3, 5, "你好 👋", "msg-1")
        assertTrue(ct, ct.startsWith("e2e2:42:3.5:"))
        assertTrue(E2E.isEncrypted(ct))
        assertTrue(E2E.isEncrypted("e2e:xx"))
        assertTrue(!E2E.isEncrypted("plain") && !E2E.isEncrypted(null))
        assertEquals(E2E.V2Header(42L, 3, 5), E2E.textHeader(ct))
        var seen: E2E.V2Header? = null
        val lookup = { uid: Long, s: Int, r: Int -> seen = E2E.V2Header(uid, s, r); if (s == 3 && r == 5) key else null }
        assertEquals("你好 👋", E2E.decryptTextV2(ct, "msg-1", lookup))
        assertEquals(E2E.V2Header(42L, 3, 5), seen)
        assertNull("wrong message id must fail", E2E.decryptTextV2(ct, "msg-2", lookup))
        assertNull("unknown epochs give no key", E2E.decryptTextV2(ct, "msg-1") { _, _, _ -> null })
        assertNull("wrong key must fail", E2E.decryptTextV2(ct, "msg-1") { _, _, _ -> E2E.derive(b.priv, E2E.generate().pub, b.pub) })
        assertNull("v1 decrypt must not accept v2 input", E2E.decryptText(key, ct, "msg-1"))
        assertNotEquals(ct, E2E.encryptTextV2(key, 42L, 3, 5, "你好 👋", "msg-1"))
        val url = E2E.encryptTextV2(key, 42L, 3, 5, "报告 v2.pdf", "name", urlSafe = true)
        assertTrue(!url.contains('/') && !url.contains('+'))
        assertEquals("报告 v2.pdf", E2E.decryptTextV2(url, "name", lookup))
        // a v1 text goes through the same lookup as (0, 0, 0)
        val v1 = E2E.encryptText(key, "old", "m0")
        assertEquals(E2E.V2Header.LEGACY, E2E.textHeader(v1))
        assertTrue(E2E.textHeader(v1)!!.isLegacy)
        assertEquals("old", E2E.decryptTextV2(v1, "m0") { uid, s, r -> if (uid == 0L && s == 0 && r == 0) key else null })
        assertNull(E2E.decryptTextV2(v1, "m0") { _, _, _ -> null })
        // malformed headers never throw
        for (bad in listOf("e2e2:", "e2e2:42:3.5", "e2e2:x:3.5:AAAA", "e2e2:42:3:AAAA", "e2e2:42:a.5:AAAA", "e2e2:42:-1.5:AAAA", "e2e2::3.5:AAAA", "plain", "")) {
            assertNull(bad, E2E.textHeader(bad))
            assertNull(bad, E2E.decryptTextV2(bad, "m") { _, _, _ -> key })
        }
        // a well-formed header with an empty or garbage body never throws either
        assertEquals(E2E.V2Header(42L, 3, 5), E2E.textHeader("e2e2:42:3.5:"))
        assertNull(E2E.decryptTextV2("e2e2:42:3.5:", "m") { _, _, _ -> key })
        assertNull(E2E.decryptTextV2("e2e2:42:3.5:!!!", "m") { _, _, _ -> key })
    }

    @Test
    fun v2_stream_round_trip_with_header_epochs() {
        val key = E2E.derive(a.priv, b.pub, a.pub)
        val n = 5 * (1 shl 19) // 2.5 MiB: three chunks, the last one half full
        val plain = Random(7).nextBytes(n)
        val out = ByteArrayOutputStream()
        E2E.encryptStreamV2(key, 1234567890123L, 7, 2, plain.inputStream(), out)
        val blob = out.toByteArray()
        assertEquals(E2E.encryptedSizeV2(n.toLong()), blob.size.toLong())
        assertEquals(E2E.encryptedSize(n.toLong()) + 16, blob.size.toLong())
        assertEquals(E2E.STREAM_HEADER_V2, 20)
        assertTrue(E2E.isEncryptedStream(blob.copyOf(4)))
        assertTrue(E2E.isV2Stream(blob.copyOf(4)))
        assertTrue(!E2E.isV2Stream("LCE1".toByteArray()) && E2E.isEncryptedStream("LCE1".toByteArray()))
        assertTrue(!E2E.isEncryptedStream("LCE3".toByteArray()))
        assertEquals(E2E.V2Header(1234567890123L, 7, 2), E2E.streamHeader(blob.copyOf(E2E.STREAM_HEADER_V2)))
        assertNull("too short a peek", E2E.streamHeader(blob.copyOf(10)))
        var seen: E2E.V2Header? = null
        val back = E2E.decryptBytesV2(blob) { uid, s, r -> seen = E2E.V2Header(uid, s, r); if (s == 7 && r == 2) key else null }
        assertArrayEquals(plain, back)
        assertEquals(E2E.V2Header(1234567890123L, 7, 2), seen)
        assertTrue("unknown epochs must fail", runCatching { E2E.decryptBytesV2(blob) { _, _, _ -> null } }.isFailure)
        assertTrue("the v1 reader must refuse a v2 blob", runCatching { E2E.decryptBytes(key, blob) }.isFailure)
        val bad = blob.copyOf(); bad[blob.size / 2] = (bad[blob.size / 2].toInt() xor 1).toByte()
        assertTrue(runCatching { E2E.decryptBytesV2(bad) { _, _, _ -> key } }.isFailure)
        assertTrue("truncation must fail", runCatching { E2E.decryptBytesV2(blob.copyOf(blob.size - 20)) { _, _, _ -> key } }.isFailure)
        val hdr = blob.copyOf(); hdr[15] = 3 // sender epoch 7 → 3: the lookup no longer finds a key
        assertTrue(runCatching { E2E.decryptBytesV2(hdr) { _, s, r -> if (s == 7 && r == 2) key else null } }.isFailure)
        assertTrue("truncated inside the header", runCatching { E2E.decryptBytesV2(blob.copyOf(9)) { _, _, _ -> key } }.isFailure)
        // an empty file
        val empty = E2E.encryptBytesV2(key, 5L, 1, 1, ByteArray(0))
        assertEquals(E2E.encryptedSizeV2(0), empty.size.toLong())
        assertArrayEquals(ByteArray(0), E2E.decryptBytesV2(empty) { _, _, _ -> key })
        // streaming reader, small reads
        val plainStream = E2E.decryptingStreamV2(blob.inputStream()) { _, _, _ -> key }
        val buf = ByteArray(777)
        val collected = ByteArrayOutputStream()
        while (true) { val got = plainStream.read(buf); if (got < 0) break; collected.write(buf, 0, got) }
        assertArrayEquals(plain, collected.toByteArray())
    }

    @Test
    fun v2_stream_reader_accepts_a_v1_blob_through_the_legacy_lookup() {
        val key = E2E.derive(a.priv, b.pub, a.pub)
        val plain = Random(3).nextBytes(100_000)
        val blob = E2E.encryptBytes(key, plain)
        assertEquals(E2E.V2Header.LEGACY, E2E.streamHeader(blob.copyOf(4)))
        var asked: E2E.V2Header? = null
        assertArrayEquals(plain, E2E.decryptBytesV2(blob) { uid, s, r -> asked = E2E.V2Header(uid, s, r); if (s == 0 && r == 0) key else null })
        assertEquals(E2E.V2Header.LEGACY, asked)
        assertTrue(runCatching { E2E.decryptBytesV2(blob) { _, _, _ -> null } }.isFailure)
        assertTrue(runCatching { E2E.decryptBytesV2("nope".toByteArray()) { _, _, _ -> key } }.isFailure)
        assertArrayEquals(plain, E2E.decryptBytes(key, blob))
    }
}
