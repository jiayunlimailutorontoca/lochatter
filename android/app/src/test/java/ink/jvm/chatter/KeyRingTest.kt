package ink.jvm.chatter

import ink.jvm.chatter.crypto.E2E
import ink.jvm.chatter.crypto.KeyRing
import ink.jvm.chatter.crypto.KeyRing.AdoptResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KeyRingTest {
    private val ia = E2E.generate()
    private val ib = E2E.generate()
    private val a = KeyRing(ia.priv, ia.pub, 1L)
    private val b = KeyRing(ib.priv, ib.pub, 2L)

    /** Both publish, both adopt: the normal hello exchange. */
    private fun pair(): Pair<AdoptResult, AdoptResult> = b.adoptPeer(a.publishBundle()) to a.adoptPeer(b.publishBundle())

    @Test
    fun two_rings_derive_the_same_v2_key_from_each_others_bundles() {
        assertEquals(0, a.currentEpoch())
        assertNull(a.currentKey())
        assertNull(a.currentSession())
        assertEquals(emptyList<Int>(), a.epochs())
        val (ra, rb) = pair()
        assertEquals(AdoptResult.Verified(ia.pub, 1, a.myEpochPub(1)!!, false, 1), ra)
        assertEquals(AdoptResult.Verified(ib.pub, 1, b.myEpochPub(1)!!, false, 1), rb)
        assertEquals(1, a.currentEpoch()); assertEquals(1, a.peerEpoch()); assertEquals(ib.pub, a.peerIdentityPub())
        assertEquals(listOf(1), a.epochs()); assertEquals(1L, a.myUserId()); assertEquals(ia.pub, a.identityPub()); assertEquals(ia.priv, a.identityPriv())
        assertEquals(b.myEpochPub(1), a.peerEpochPub(1)); assertNull(a.peerEpochPub(2))
        val sa = a.currentSession()!!
        assertTrue(sa.v2); assertEquals(1, sa.senderEpoch); assertEquals(1, sa.receiverEpoch); assertEquals(1L, sa.senderUid)
        // A sends with (1, 1); B, as receiver, gets the same key from the header
        assertArrayEquals(sa.key, b.keyFor(1L, 1, 1))
        assertArrayEquals(a.keyFor(1, 1, iAmSender = true), b.keyFor(1, 1, iAmSender = false))
        assertArrayEquals(a.currentKey(), b.currentKey()) // equal epochs on both sides: one key for both directions
        assertEquals(32, sa.key.size)
        assertTrue("v2 is not the identity key", !sa.key.contentEquals(E2E.derive(ia.priv, ib.pub, ia.pub)))
        // the safety number stays a function of the identity keys
        assertEquals(E2E.safetyNumber(ia.pub, ib.pub), E2E.safetyNumber(a.identityPub(), a.peerIdentityPub()!!))
        // one side at 0, or an unknown number: no key
        assertNull(a.keyFor(0, 1, true)); assertNull(a.keyFor(1, 0, false)); assertNull(a.keyFor(2, 1, true)); assertNull(a.keyFor(1, 9, true))
        assertNull(a.keyFor(-1, 1, true))
        // both at 0: the legacy identity key
        assertArrayEquals(E2E.derive(ia.priv, ib.pub, ia.pub), a.keyFor(0L, 0, 0))
        assertArrayEquals(a.keyFor(0L, 0, 0), b.keyFor(0L, 0, 0))
        // cached lookups return the same key
        assertArrayEquals(a.keyFor(1L, 1, 1), a.keyFor(1L, 1, 1))
    }

    @Test
    fun v2_text_and_stream_round_trip_between_rings() {
        pair()
        val ct = a.encryptText("你好 👋", "msg-1")!!
        assertTrue(ct, ct.startsWith("e2e2:1:1.1:"))
        assertEquals("你好 👋", b.decryptText(ct, "msg-1"))
        assertEquals("你好 👋", a.decryptText(ct, "msg-1")) // my own message, resolved as sender
        assertNull("wrong aad", b.decryptText(ct, "msg-2"))
        assertNull(b.decryptText("plain", "msg-1"))
        val url = a.encryptText("报告.pdf", "name", urlSafe = true)!!
        assertTrue(!url.substringAfterLast(':').contains('/') && !url.contains('+'))
        assertEquals("报告.pdf", b.decryptText(url, "name"))
        val plain = Random(1).nextBytes(300_000)
        val s = a.currentSession()!!
        val blob = s.encryptBytes(plain)
        assertEquals(s.encryptedSize(plain.size.toLong()), blob.size.toLong())
        assertEquals(E2E.encryptedSizeV2(plain.size.toLong()), blob.size.toLong())
        assertArrayEquals(plain, b.decryptBytes(blob))
        assertArrayEquals(plain, b.decryptingStream(blob.inputStream()).use { it.readBytes() })
        assertEquals(E2E.V2Header(1L, 1, 1), E2E.streamHeader(blob.copyOf(E2E.STREAM_HEADER_V2)))
        val out = java.io.ByteArrayOutputStream()
        s.encryptStream(plain.inputStream(), out)
        assertArrayEquals(plain, a.decryptBytes(out.toByteArray()))
        // v1 blobs from before the upgrade still resolve through the ring (legacy lookup)
        val legacy = E2E.derive(ib.priv, ia.pub, ib.pub)
        assertArrayEquals(plain, a.decryptBytes(E2E.encryptBytes(legacy, plain)))
        assertEquals("v1", a.decryptText(E2E.encryptText(legacy, "v1", "m"), "m"))
    }

    @Test
    fun legacy_peer_key_gives_a_v1_session_until_the_peer_upgrades() {
        a.publishBundle()
        assertEquals(AdoptResult.Legacy(ib.pub, false), a.adoptPeer(ib.pub))
        assertEquals(0, a.peerEpoch()); assertEquals(ib.pub, a.peerIdentityPub())
        val s = a.currentSession()!!
        assertTrue(!s.v2); assertEquals(0, s.senderEpoch); assertEquals(0, s.receiverEpoch)
        assertArrayEquals(E2E.derive(ia.priv, ib.pub, ia.pub), s.key)
        assertArrayEquals(s.key, a.currentKey())
        val ct = a.encryptText("hi", "m1")!!
        assertTrue(ct.startsWith("e2e:"))
        assertEquals("hi", E2E.decryptText(E2E.derive(ib.priv, ia.pub, ib.pub), ct, "m1"))
        assertEquals(E2E.encryptedSize(10), s.encryptedSize(10))
        assertTrue(E2E.streamHeader(s.encryptBytes(ByteArray(10)).copyOf(4))!!.isLegacy)
        // re-sent legacy key: no change
        assertEquals(AdoptResult.Legacy(ib.pub, false), a.adoptPeer(ib.pub))
        // the peer upgrades: same identity, now with an epoch key
        val bundle = b.publishBundle()
        assertEquals(AdoptResult.Verified(ib.pub, 1, b.myEpochPub(1)!!, false, 1), a.adoptPeer(bundle))
        assertTrue(a.currentSession()!!.v2)
        assertEquals(1, a.peerEpoch())
    }

    @Test
    fun bundle_format_and_parse() {
        a.rotate(); a.rotate(); a.rotate()
        val text = a.publishBundle()
        val bundle = KeyRing.Bundle.parse(text)!!
        assertEquals(ia.pub, bundle.identityPub)
        assertEquals(listOf(3, 2, 1), bundle.entries.map { it.epoch })
        assertEquals(3, bundle.epoch)
        assertEquals(a.myEpochPub(3), bundle.epochPub)
        assertTrue(!bundle.isLegacy)
        assertEquals(text, bundle.format())
        assertTrue(text.startsWith("v2|${ia.pub}|3|${a.myEpochPub(3)}|"))
        assertEquals("deterministic: signatures are stored, not recomputed", text, a.publishBundle())
        for (e in bundle.entries) {
            assertEquals(a.myEpochPub(e.epoch), e.pub)
            assertTrue(E2E.verify(ia.pub, E2E.epochSignData(e.epoch, e.pub), e.sig))
        }
        // legacy: a bare key, whitespace tolerated
        val legacy = KeyRing.Bundle.parse(" ${ib.pub}\n")!!
        assertTrue(legacy.isLegacy); assertEquals(0, legacy.epoch); assertNull(legacy.epochPub); assertEquals(ib.pub, legacy.identityPub)
        assertEquals(ib.pub, legacy.format())
        // malformed
        for (bad in listOf("", " ", "v2|${ia.pub}", "v2|${ia.pub}|1|x", "v3|${ia.pub}|1|x|y", "v2|${ia.pub}|0|x|y", "v2|${ia.pub}|1|x|y|2|x|y",
            "v2|${ia.pub}|2|x|y|2|x|y", "v2|${ia.pub}|a|x|y", "v2|${ia.pub}|1|x y|z", "v2||1|x|y", "v2|${ia.pub}|1||y", "a|b", "e2e:abc|1")) {
            assertNull(bad, KeyRing.Bundle.parse(bad))
        }
        // the chain is capped at 12 entries, newest first, and fits the 4096-char server field
        repeat(12) { a.rotate() }
        val long = a.publishBundle()
        val chain = KeyRing.Bundle.parse(long)!!
        assertEquals(12, chain.entries.size)
        assertEquals((15 downTo 4).toList(), chain.entries.map { it.epoch })
        assertTrue("bundle is ${long.length} chars", long.length <= 4096)
        assertEquals(KeyRing.Bundle.MAX_CHAIN, 12)
    }

    @Test
    fun tampered_signature_is_rejected() {
        val text = a.publishBundle()
        val bundle = KeyRing.Bundle.parse(text)!!
        val e = bundle.entries[0]
        val sig = E2E.unb64(e.sig); sig[sig.size / 2] = (sig[sig.size / 2].toInt() xor 1).toByte()
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(KeyRing.Bundle(ia.pub, listOf(e.copy(sig = E2E.b64(sig)))).format()))
        assertNull(b.peerIdentityPub()); assertEquals(0, b.peerEpoch()); assertNull(b.peerEpochPub(1))
        // the same signature over another epoch number or another key
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(KeyRing.Bundle(ia.pub, listOf(e.copy(epoch = 2))).format()))
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(KeyRing.Bundle(ia.pub, listOf(e.copy(pub = E2E.generate().pub))).format()))
        // signed by somebody else's identity while claiming A's
        val c = E2E.generate()
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(KeyRing.Bundle(ia.pub, listOf(e.copy(sig = E2E.sign(c.priv, E2E.epochSignData(e.epoch, e.pub))))).format()))
        // the server swaps the identity but keeps A's signature
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(KeyRing.Bundle(c.pub, listOf(e)).format()))
        // malformed material
        assertEquals(AdoptResult.Malformed, b.adoptPeer("v2"))
        assertEquals(AdoptResult.Malformed, b.adoptPeer("nope!"))
        assertEquals(AdoptResult.Malformed, b.adoptPeer(""))
        assertEquals(AdoptResult.Malformed, b.adoptPeer("v2|${ia.pub}|1|x"))
        assertEquals(AdoptResult.Malformed, b.adoptPeer(KeyRing.Bundle("QUJD", listOf(e)).format())) // identity is not a key
        val junk = "QUJD" // validly signed, but not a P-256 point
        assertEquals(AdoptResult.Malformed, b.adoptPeer(KeyRing.Bundle(ia.pub, listOf(KeyRing.Bundle.Entry(1, junk, E2E.sign(ia.priv, E2E.epochSignData(1, junk))))).format()))
        assertNull(b.peerIdentityPub())
        // the genuine bundle still goes through afterwards
        assertTrue(b.adoptPeer(text) is AdoptResult.Verified)
        assertEquals(1, b.peerEpoch())
    }

    @Test
    fun replayed_lower_epoch_is_rejected_but_a_resend_is_fine() {
        val first = a.publishBundle() // epoch 1
        assertTrue(b.adoptPeer(first) is AdoptResult.Verified)
        a.rotate()
        val second = a.publishBundle() // epochs 2, 1
        assertEquals(AdoptResult.Verified(ia.pub, 2, a.myEpochPub(2)!!, false, 1), b.adoptPeer(second))
        assertEquals(2, b.peerEpoch())
        // the server replays the old bundle
        assertEquals(AdoptResult.Replay(ia.pub, 1, 2), b.adoptPeer(first))
        assertEquals(2, b.peerEpoch())
        // re-sending the current one: fine, nothing new
        assertEquals(AdoptResult.Verified(ia.pub, 2, a.myEpochPub(2)!!, false, 0), b.adoptPeer(second))
        // same epoch numbers, different (validly signed) keys: the peer's ring was reset, so this is not "the same one"
        val reset = KeyRing(ia.priv, ia.pub, 1L)
        reset.rotate(); reset.rotate()
        assertEquals(AdoptResult.Replay(ia.pub, 2, 2), b.adoptPeer(reset.publishBundle()))
        assertEquals(a.myEpochPub(2), b.peerEpochPub(2))
        // continuing above the highest is accepted
        a.rotate()
        assertEquals(AdoptResult.Verified(ia.pub, 3, a.myEpochPub(3)!!, false, 1), b.adoptPeer(a.publishBundle()))
        // a new number above the highest whose chain contradicts a known entry is rejected as a whole
        reset.rotateTo(9)
        assertEquals(listOf(9, 2, 1), KeyRing.Bundle.parse(reset.publishBundle())!!.entries.map { it.epoch })
        assertEquals(AdoptResult.Replay(ia.pub, 2, 3), b.adoptPeer(reset.publishBundle()))
        assertEquals(3, b.peerEpoch()); assertNull(b.peerEpochPub(9))
    }

    @Test
    fun rotation_changes_the_key_and_old_messages_still_decrypt() {
        pair()
        val k11 = a.currentKey()!!
        val m1 = a.encryptText("first", "m1")!!
        val media1 = a.currentSession()!!.encryptBytes("media one".toByteArray())
        val k = a.rotate()
        assertEquals(2, k.n); assertEquals(a.myEpochPub(2), k.pub); assertEquals(a.myEpochPriv(2), k.priv)
        assertTrue(E2E.verify(ia.pub, E2E.epochSignData(2, k.pub), k.sig))
        assertEquals(2, a.currentEpoch())
        assertEquals(listOf(1, 2), a.epochs())
        assertNotNull(a.myEpochPriv(1)); assertNotNull(a.myEpochPriv(2)); assertNull(a.myEpochPriv(3))
        assertTrue("B has not seen the rotation yet", a.currentSession()!!.senderEpoch == 2)
        assertEquals(AdoptResult.Verified(ia.pub, 2, a.myEpochPub(2)!!, false, 1), b.adoptPeer(a.publishBundle()))
        assertEquals(2, b.peerEpoch())
        val k21 = a.currentKey()!!
        assertTrue(!k11.contentEquals(k21))
        val m2 = a.encryptText("second", "m2")!!
        assertTrue(m2, m2.startsWith("e2e2:1:2.1:"))
        // B reads both, the old one through the older epoch pair
        assertEquals("first", b.decryptText(m1, "m1"))
        assertEquals("second", b.decryptText(m2, "m2"))
        assertArrayEquals("media one".toByteArray(), b.decryptBytes(media1))
        assertArrayEquals(k11, b.keyFor(1L, 1, 1))
        assertArrayEquals(k21, b.keyFor(1L, 2, 1))
        assertArrayEquals(k11, a.keyFor(1, 1, iAmSender = true))
        // B answers from its epoch 1 to A's epoch 2: the other direction has its own key
        val reply = b.encryptText("reply", "m3")!!
        assertTrue(reply, reply.startsWith("e2e2:2:1.2:"))
        assertTrue(!b.currentKey()!!.contentEquals(k21))
        assertEquals("reply", a.decryptText(reply, "m3"))
        // a slow B still encrypting to A's epoch 1: A keeps that key
        val late = E2E.encryptTextV2(b.keyFor(1, 1, iAmSender = true)!!, 2L, 1, 1, "late", "m4")
        assertEquals("late", a.decryptText(late, "m4"))
        // destroying the old epoch key makes its traffic unreadable, and only its traffic
        a.forgetMyEpochsBelow(2)
        assertEquals(listOf(2), a.epochs())
        assertNull(a.myEpochPriv(1))
        assertNull(a.keyFor(1L, 1, 1))
        assertNull(a.decryptText(m1, "m1"))
        assertNull(a.decryptText(late, "m4"))
        assertEquals("second", a.decryptText(m2, "m2"))
        a.forgetMyEpochsBelow(99)
        assertEquals("never the current one", listOf(2), a.epochs())
    }

    @Test
    fun chain_of_three_rotations_published_at_once_decrypts_every_epoch() {
        a.adoptPeer(b.publishBundle()) // A knows B; B goes offline for a while
        val texts = ArrayList<String>()
        val blobs = ArrayList<ByteArray>()
        for (i in 1..3) {
            a.rotate()
            texts += a.encryptText("epoch $i", "m$i")!!
            blobs += a.currentSession()!!.encryptBytes("blob $i".toByteArray())
        }
        assertEquals(3, a.currentEpoch())
        assertTrue(texts[0].startsWith("e2e2:1:1.1:")); assertTrue(texts[2].startsWith("e2e2:1:3.1:"))
        // B comes back and gets one bundle carrying the whole chain
        val bundle = a.publishBundle()
        assertEquals(listOf(3, 2, 1), KeyRing.Bundle.parse(bundle)!!.entries.map { it.epoch })
        assertEquals(AdoptResult.Verified(ia.pub, 3, a.myEpochPub(3)!!, false, 3), b.adoptPeer(bundle))
        assertEquals(3, b.peerEpoch())
        for (i in 1..3) {
            assertEquals(a.myEpochPub(i), b.peerEpochPub(i))
            assertEquals("epoch $i", b.decryptText(texts[i - 1], "m$i"))
            assertArrayEquals("blob $i".toByteArray(), b.decryptBytes(blobs[i - 1]))
        }
        // re-sending the chain records nothing new
        assertEquals(AdoptResult.Verified(ia.pub, 3, a.myEpochPub(3)!!, false, 0), b.adoptPeer(bundle))
        // B's reply goes to A's newest epoch
        assertTrue(b.encryptText("back", "m9")!!.startsWith("e2e2:2:1.3:"))
    }

    @Test
    fun chain_with_one_tampered_middle_signature_is_rejected_as_a_whole() {
        a.rotate(); a.rotate(); a.rotate()
        val good = KeyRing.Bundle.parse(a.publishBundle())!!
        val middle = good.entries[1]
        assertEquals(2, middle.epoch)
        val sig = E2E.unb64(middle.sig); sig[sig.size / 2] = (sig[sig.size / 2].toInt() xor 1).toByte()
        val bad = KeyRing.Bundle(ia.pub, listOf(good.entries[0], middle.copy(sig = E2E.b64(sig)), good.entries[2])).format()
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(bad))
        assertEquals(0, b.peerEpoch()); assertNull(b.peerEpochPub(3)); assertNull(b.peerEpochPub(1)); assertNull(b.peerIdentityPub())
        assertNull(b.currentKey())
        // a swapped middle key (signature of another epoch) fails the same way
        val swapped = KeyRing.Bundle(ia.pub, listOf(good.entries[0], middle.copy(pub = good.entries[2].pub), good.entries[2])).format()
        assertEquals(AdoptResult.BadSignature, b.adoptPeer(swapped))
        assertTrue(b.adoptPeer(good.format()) is AdoptResult.Verified)
        assertEquals(3, b.peerEpoch())
    }

    @Test
    fun new_peer_identity_is_verified_reported_and_earlier_epochs_stay_resolvable() {
        pair()
        b.rotate()
        assertTrue(a.adoptPeer(b.publishBundle()) is AdoptResult.Verified)
        val old = a.encryptText("old", "m1")!! // A epoch 1 → B epoch 2
        assertTrue(old.startsWith("e2e2:1:1.2:"))
        // the peer reinstalled: a new identity, numbering starts over at 1
        val ic = E2E.generate()
        val c = KeyRing(ic.priv, ic.pub, 2L)
        c.adoptPeer(a.publishBundle())
        val bundle = c.publishBundle()
        // the caller may look before switching: verified and reported, nothing recorded
        assertEquals(AdoptResult.Verified(ic.pub, 1, c.myEpochPub(1)!!, true, 1), a.adoptPeer(bundle, acceptNewIdentity = false))
        assertEquals(ib.pub, a.peerIdentityPub()); assertEquals(2, a.peerEpoch()); assertEquals(b.myEpochPub(1), a.peerEpochPub(1))
        // switch
        assertEquals(AdoptResult.Verified(ic.pub, 1, c.myEpochPub(1)!!, true, 1), a.adoptPeer(bundle))
        assertEquals(ic.pub, a.peerIdentityPub()); assertEquals(1, a.peerEpoch())
        assertEquals(c.myEpochPub(1), a.peerEpochPub(1)) // the new identity's epoch 1 …
        assertEquals(b.myEpochPub(2), a.peerEpochPub(2)) // … and the earlier identity's epoch 2, which C does not have
        assertEquals("old", a.decryptText(old, "m1"))
        assertEquals(E2E.safetyNumber(ia.pub, ic.pub), E2E.safetyNumber(a.identityPub(), a.peerIdentityPub()!!))
        val fresh = a.encryptText("new", "m2")!!
        assertTrue(fresh.startsWith("e2e2:1:1.1:"))
        assertEquals("new", c.decryptText(fresh, "m2"))
        assertEquals("hi", a.decryptText(c.encryptText("hi", "m3")!!, "m3"))
        // the legacy key follows the trusted identity
        assertArrayEquals(E2E.derive(ia.priv, ic.pub, ia.pub), a.keyFor(0L, 0, 0))
        // a bare legacy key from yet another identity
        val id = E2E.generate()
        assertEquals(AdoptResult.Legacy(id.pub, true), a.adoptPeer(id.pub, acceptNewIdentity = false))
        assertEquals(ic.pub, a.peerIdentityPub())
        assertEquals(AdoptResult.Legacy(id.pub, true), a.adoptPeer(id.pub))
        assertEquals(id.pub, a.peerIdentityPub()); assertEquals(0, a.peerEpoch())
        assertTrue(!a.currentSession()!!.v2)
        assertArrayEquals(E2E.derive(ia.priv, id.pub, ia.pub), a.currentKey())
        // back to C: its epoch record comes out of retirement, nothing new in it
        assertEquals(AdoptResult.Verified(ic.pub, 1, c.myEpochPub(1)!!, true, 0), a.adoptPeer(bundle))
        assertEquals(1, a.peerEpoch()); assertEquals("new", a.decryptText(fresh, "m2"))
        a.forgetPeer()
        assertNull(a.peerIdentityPub()); assertEquals(0, a.peerEpoch()); assertNull(a.currentKey()); assertNull(a.peerEpochPub(2))
        assertNull(a.keyFor(1L, 1, 1)); assertNull(a.decryptText(fresh, "m2"))
        assertEquals(1, a.currentEpoch()) // my own keys stay
    }

    @Test
    fun json_round_trip_preserves_everything() {
        pair()
        a.rotate(); b.rotate(); b.rotate()
        a.adoptPeer(b.publishBundle()); b.adoptPeer(a.publishBundle())
        val old = a.encryptText("keep me", "m1")!! // A epoch 2 → B epoch 3
        // an earlier identity ends up in the retired list
        val ic = E2E.generate(); val c = KeyRing(ic.priv, ic.pub, 2L); c.adoptPeer(a.publishBundle())
        assertTrue(a.adoptPeer(c.publishBundle()) is AdoptResult.Verified)
        val json = a.toJson()
        assertTrue(json.contains("\"myUserId\":1"))
        assertTrue(json.contains(ia.priv)) // private keys travel: encrypted prefs only
        val back = KeyRing.fromJson(json)
        assertEquals(a.myUserId(), back.myUserId())
        assertEquals(a.identityPub(), back.identityPub()); assertEquals(a.identityPriv(), back.identityPriv())
        assertEquals(listOf(1, 2), back.epochs())
        for (n in a.epochs()) { assertEquals(a.myEpochPriv(n), back.myEpochPriv(n)); assertEquals(a.myEpochPub(n), back.myEpochPub(n)) }
        assertEquals(a.lastRotationAt(), back.lastRotationAt())
        assertEquals(a.currentEpoch(), back.currentEpoch())
        assertEquals(a.publishBundle(), back.publishBundle())
        assertEquals(ic.pub, back.peerIdentityPub()); assertEquals(1, back.peerEpoch())
        for (n in 1..3) assertEquals(a.peerEpochPub(n), back.peerEpochPub(n))
        assertArrayEquals(a.currentKey(), back.currentKey())
        assertEquals("keep me", back.decryptText(old, "m1")) // the retired identity's epoch 3 survived
        assertEquals("c", back.decryptText(c.encryptText("c", "m2")!!, "m2"))
        assertEquals(json, back.toJson())
        // replay protection survives too
        assertTrue(back.adoptPeer(KeyRing.Bundle.parse(b.publishBundle())!!.let { KeyRing.Bundle(it.identityPub, it.entries.drop(1)).format() }) is AdoptResult.Replay)
        // an older export without the user id
        val stripped = json.replace(Regex("\"myUserId\":1,?"), "")
        assertTrue(!stripped.contains("myUserId"))
        assertTrue(runCatching { KeyRing.fromJson(stripped) }.isFailure)
        assertEquals(1L, KeyRing.fromJson(stripped, 1L).myUserId())
        assertEquals(a.epochs(), KeyRing.fromJson(stripped, 1L).epochs())
        assertEquals(1L, KeyRing.fromJson(json, 7L).myUserId()) // the stored id wins over the fallback
        assertTrue(runCatching { KeyRing.fromJson("{}") }.isFailure)
        assertTrue(runCatching { KeyRing.fromJson("nope") }.isFailure)
        // fields from a newer version are ignored
        assertEquals(a.epochs(), KeyRing.fromJson(json.replaceFirst("{", "{\"future\":true,")).epochs())
        // a ring without any peer or epoch
        val bare = KeyRing.fromJson(KeyRing(ia.priv, ia.pub, 5L).toJson())
        assertEquals(0, bare.currentEpoch()); assertNull(bare.peerIdentityPub()); assertEquals(5L, bare.myUserId())
    }

    @Test
    fun rotate_to_continues_numbering_after_an_identity_import() {
        pair(); a.rotate(); a.rotate() // A is at 3
        assertTrue(b.adoptPeer(a.publishBundle()) is AdoptResult.Verified)
        assertEquals(3, b.peerEpoch())
        // A's new phone: identity imported, epoch keys lost; the server still shows A's bundle at epoch 3
        val shown = KeyRing.Bundle.parse(a.publishBundle())!!.epoch
        val fresh = KeyRing(ia.priv, ia.pub, 1L)
        assertTrue(runCatching { fresh.rotateTo(0) }.isFailure)
        fresh.rotateTo(shown + 1)
        assertEquals(4, fresh.currentEpoch()); assertEquals(listOf(4), fresh.epochs())
        assertEquals(AdoptResult.Verified(ia.pub, 4, fresh.myEpochPub(4)!!, false, 1), b.adoptPeer(fresh.publishBundle()))
        fresh.adoptPeer(b.publishBundle())
        assertEquals("ok", b.decryptText(fresh.encryptText("ok", "m")!!, "m"))
        assertTrue(runCatching { fresh.rotateTo(4) }.isFailure)
        assertEquals(5, fresh.rotate().n)
    }

    @Test
    fun rotation_policy() {
        val week = 7L * 24 * 3600 * 1000
        assertEquals(week, KeyRing.DEFAULT_ROTATION_MS)
        assertTrue("never rotated", KeyRing.shouldRotate(now = 1_000L, lastRotationAt = 0L))
        assertTrue(!KeyRing.shouldRotate(now = 1_000L, lastRotationAt = 1_000L))
        assertTrue(!KeyRing.shouldRotate(now = 1_000L + week - 1, lastRotationAt = 1_000L))
        assertTrue(KeyRing.shouldRotate(now = 1_000L + week, lastRotationAt = 1_000L))
        assertTrue("clock went backwards", KeyRing.shouldRotate(now = 500L, lastRotationAt = 1_000L))
        assertTrue(KeyRing.shouldRotate(now = 1_000L + 60_000, lastRotationAt = 1_000L, intervalMs = 60_000))
        assertTrue(!KeyRing.shouldRotate(now = 1_000L + 59_999, lastRotationAt = 1_000L, intervalMs = 60_000))
        assertTrue(a.shouldRotate(5L, 0L))
        assertTrue(!a.shouldRotate(5L, 5L))
        // against the ring's own record of the current epoch key
        assertEquals(0L, a.lastRotationAt())
        assertTrue(a.rotationDue(now = 10L))
        a.rotate(now = 10L)
        assertEquals(10L, a.lastRotationAt())
        assertTrue(!a.rotationDue(now = 10L + week - 1))
        assertTrue(a.rotationDue(now = 10L + week))
        assertTrue(a.rotationDue(now = 11L, intervalMs = 1))
        assertEquals(10L, a.myEpochs().single().createdAt)
    }
}
