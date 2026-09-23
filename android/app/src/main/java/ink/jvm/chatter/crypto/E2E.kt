package ink.jvm.chatter.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption between the two users.
 *
 * Identity: one ECDH P-256 key pair per user (software key, stored in the encrypted prefs so it can be
 * exported to a new phone). v1 session key: HKDF-SHA256 over the ECDH shared secret of the two identity
 * keys, salted with both public keys, so both phones derive the same 256-bit AES key without anything
 * secret ever crossing the server.
 * Texts: AES-256-GCM, random 96-bit nonce, the message id as associated data ("e2e:" + base64(nonce ‖ ct)).
 * Files: "LCE1" ‖ base nonce ‖ 1 MiB chunks of AES-GCM, so 200 MB videos stream both ways without buffering.
 * Safety number: 60 digits from SHA-512 of both identity public keys, compared in person to rule out a swapped key.
 *
 * v2, forward secrecy (key material lives in [KeyRing]): each user rotates a short-lived P-256 "epoch key"
 * (epoch 1, 2, …) and publishes it signed by the identity key (SHA256withECDSA over [epochSignData]), so
 * the peer can check continuity without a safety-number change. A message from A (epoch a) to B (epoch b)
 * is keyed with HKDF-SHA256(ECDH(A_a, B_b), salt = SHA-256(sorted(pub A_a, pub B_b)), info = "lochatter-e2e-v2|a.b"),
 * the sender's epoch first, so the two directions use different keys once the epochs differ ([deriveV2]).
 * Every v2 blob names its sender and the two epochs, so the receiver can pick the key without further context:
 * texts "e2e2:<senderUid>:<senderEpoch>.<receiverEpoch>:" + base64(nonce ‖ ct), files "LCE2" ‖ senderUid(8) ‖
 * senderEpoch(4) ‖ receiverEpoch(4) ‖ the LCE1 body. [decryptTextV2] / [decryptingStreamV2] read that header and ask
 * a lookup for the key. While either side still has no epoch key (epoch 0) everything stays v1.
 */
object E2E {
    const val PREFIX = "e2e:"
    const val PREFIX_V2 = "e2e2:"
    /** Bytes of a v2 blob header (magic ‖ senderUid ‖ senderEpoch ‖ receiverEpoch): peek this many to call [streamHeader]. */
    const val STREAM_HEADER_V2 = 4 + 8 + 4 + 4
    private const val INFO = "lochatter-e2e-v1"
    private const val INFO_V2 = "lochatter-e2e-v2"
    private const val SIGN_ALG = "SHA256withECDSA"
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private val MAGIC_V2 = byteArrayOf('L'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), '2'.code.toByte())
    private const val CHUNK = 1 shl 20
    private const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    /** What a v2 blob carries after the magic: senderUid(8) ‖ senderEpoch(4) ‖ receiverEpoch(4). */
    private const val V2_EXTRA = STREAM_HEADER_V2 - 4
    private val rnd = SecureRandom()

    class KeyPairB64(val pub: String, val priv: String)

    /** Who encrypted a v2 blob and with which epoch pair; (0, 0, 0) stands for a v1 blob (identity keys, no header). */
    data class V2Header(val senderUid: Long, val senderEpoch: Int, val receiverEpoch: Int) {
        val isLegacy: Boolean get() = senderEpoch == 0 && receiverEpoch == 0

        companion object {
            val LEGACY = V2Header(0L, 0, 0)
        }
    }

    fun generate(): KeyPairB64 {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        return KeyPairB64(b64(kp.public.encoded), b64(kp.private.encoded))
    }

    /** Public half of a stored private key (used when importing a key on a new phone). */
    fun publicOf(privB64: String): String {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(unb64(privB64))) as java.security.interfaces.ECPrivateKey
        // Derive Q = d·G via a throwaway agreement is not possible; regenerate with BouncyCastle-free math instead:
        val params = priv.params
        val q = EcMath.multiply(params, priv.s)
        val spec = java.security.spec.ECPublicKeySpec(q, params)
        return b64(kf.generatePublic(spec).encoded)
    }

    fun isValidPublic(pubB64: String): Boolean = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(pubB64))); true
    }.getOrDefault(false)

    /** 32-byte AES key shared by both users (v1: from the two identity keys). */
    fun derive(myPrivB64: String, peerPubB64: String, myPubB64: String): ByteArray {
        val secret = agree(myPrivB64, peerPubB64)
        val salt = MessageDigest.getInstance("SHA-256").digest(sortedPubs(myPubB64, peerPubB64))
        return hkdf(secret, salt, INFO.toByteArray(), 32)
    }

    /**
     * v2 session key of one message direction, from the two epoch keys involved. [senderEpoch] and
     * [receiverEpoch] are the numbers written into the blob header: the sender passes its own epoch as
     * [senderEpoch] and the peer's as [receiverEpoch]; the receiver passes the peer's epoch as [senderEpoch]
     * and its own as [receiverEpoch]. Both get the same key. Epochs must be ≥ 1 (epoch 0 means [derive]).
     */
    fun deriveV2(myEpochPrivB64: String, peerEpochPubB64: String, myEpochPubB64: String, senderEpoch: Int, receiverEpoch: Int): ByteArray {
        require(senderEpoch > 0 && receiverEpoch > 0) { "v2 needs epoch keys on both sides" }
        val secret = agree(myEpochPrivB64, peerEpochPubB64)
        val salt = MessageDigest.getInstance("SHA-256").digest(sortedPubs(myEpochPubB64, peerEpochPubB64))
        return hkdf(secret, salt, "$INFO_V2|$senderEpoch.$receiverEpoch".toByteArray(), 32)
    }

    /** 12 groups of 5 digits; identical on both phones when nobody tampered with the keys. */
    fun safetyNumber(pubA: String, pubB: String): String {
        val d = MessageDigest.getInstance("SHA-512").digest(sortedPubs(pubA, pubB))
        val sb = StringBuilder()
        for (i in 0 until 12) {
            var n = 0L
            for (j in 0 until 5) n = (n shl 8) or (d[i * 5 + j].toLong() and 0xff)
            if (i > 0) sb.append(if (i % 4 == 0) '\n' else ' ')
            sb.append("%05d".format(n % 100000))
        }
        return sb.toString()
    }

    // ---- identity signatures (epoch key continuity) ----

    /** What the identity key signs when epoch [n] with public key [epochPubB64] is published: "lochatter-epoch|n|pub" as UTF-8. */
    fun epochSignData(n: Int, epochPubB64: String): ByteArray = "lochatter-epoch|$n|$epochPubB64".toByteArray()

    /** SHA256withECDSA signature by the identity private key, base64 (DER, 70–72 bytes). */
    fun sign(identityPrivB64: String, data: ByteArray): String {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(identityPrivB64)))
        val s = Signature.getInstance(SIGN_ALG)
        s.initSign(priv, rnd)
        s.update(data)
        return b64(s.sign())
    }

    /** false for a wrong key, altered data, or anything that is not a well-formed signature (never throws). */
    fun verify(identityPubB64: String, data: ByteArray, sigB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(identityPubB64)))
        val s = Signature.getInstance(SIGN_ALG)
        s.initVerify(pub)
        s.update(data)
        s.verify(unb64(sigB64))
    }.getOrDefault(false)

    // ---- text ----

    /** [urlSafe] uses the URL-safe alphabet (no '/' or '+'), for values that travel in query strings such as file names. */
    fun encryptText(key: ByteArray, plain: String, aad: String, urlSafe: Boolean = false): String =
        PREFIX + encodeBody(seal(key, plain, aad), urlSafe)

    /** v2 framing: "e2e2:<senderUid>:<senderEpoch>.<receiverEpoch>:" + base64(nonce ‖ ct); [aad] stays the message id. */
    fun encryptTextV2(key: ByteArray, senderUid: Long, senderEpoch: Int, receiverEpoch: Int, plain: String, aad: String, urlSafe: Boolean = false): String {
        require(senderEpoch >= 0 && receiverEpoch >= 0) { "negative epoch" }
        return "$PREFIX_V2$senderUid:$senderEpoch.$receiverEpoch:" + encodeBody(seal(key, plain, aad), urlSafe)
    }

    /** null when [text] is not a v1 e2e blob (v2 texts included: use [decryptTextV2]) or does not decrypt with this key. Accepts both base64 alphabets. */
    fun decryptText(key: ByteArray, text: String, aad: String): String? {
        if (!text.startsWith(PREFIX)) return null
        return runCatching { open(key, decodeBody(text.substring(PREFIX.length)), aad) }.getOrNull()
    }

    /**
     * Decrypts a text of either generation after looking up its key: [keyFor] gets the sender id and epoch pair
     * from a v2 header, or (0, 0, 0) for a v1 "e2e:" text (return the legacy identity-derived key there), and
     * answers null for an unknown pair. null when the text is not an e2e blob, the header is malformed, no key is
     * known, or GCM rejects it (wrong key or [aad]).
     */
    fun decryptTextV2(text: String, aad: String, keyFor: (senderUid: Long, senderEpoch: Int, receiverEpoch: Int) -> ByteArray?): String? {
        val h = textHeader(text) ?: return null
        val key = keyFor(h.senderUid, h.senderEpoch, h.receiverEpoch) ?: return null
        if (h.isLegacy && text.startsWith(PREFIX)) return decryptText(key, text, aad)
        val enc = text.substring(text.indexOf(':', text.indexOf(':', PREFIX_V2.length) + 1) + 1)
        return runCatching { open(key, decodeBody(enc), aad) }.getOrNull()
    }

    /** Header of a v2 text, [V2Header.LEGACY] for a v1 text, null for anything else (incl. a malformed v2 header). */
    fun textHeader(text: String?): V2Header? {
        if (text == null) return null
        // "e2e2:" also starts with "e2e:", so the v2 header has to be recognised first.
        if (text.startsWith(PREFIX_V2)) {
            val uidEnd = text.indexOf(':', PREFIX_V2.length)
            if (uidEnd < 0) return null
            val end = text.indexOf(':', uidEnd + 1)
            if (end < 0) return null
            val dot = text.indexOf('.', uidEnd + 1)
            if (dot < 0 || dot > end) return null
            val uid = text.substring(PREFIX_V2.length, uidEnd).let { if (it.length in 1..20) it.toLongOrNull() else null } ?: return null
            val s = epochNumber(text.substring(uidEnd + 1, dot)) ?: return null
            val r = epochNumber(text.substring(dot + 1, end)) ?: return null
            return V2Header(uid, s, r)
        }
        if (text.startsWith(PREFIX)) return V2Header.LEGACY
        return null
    }

    fun isEncrypted(text: String?): Boolean = text != null && (text.startsWith(PREFIX) || text.startsWith(PREFIX_V2))

    // ---- files: chunked, streamable ----

    /** true for both generations ("LCE1" and "LCE2"). */
    fun isEncryptedStream(head: ByteArray): Boolean = hasMagic(head, MAGIC) || hasMagic(head, MAGIC_V2)

    /** true only for the v2 magic ("LCE2"). */
    fun isV2Stream(head: ByteArray): Boolean = hasMagic(head, MAGIC_V2)

    fun isEncryptedStreamV2(head: ByteArray): Boolean = isV2Stream(head)

    /**
     * Header of a blob from its first bytes: [V2Header.LEGACY] for "LCE1" (4 bytes suffice), sender and epochs for
     * "LCE2" (needs [STREAM_HEADER_V2] bytes), null otherwise. Lets a download resolve the key before streaming.
     */
    fun streamHeader(head: ByteArray): V2Header? = when {
        hasMagic(head, MAGIC) -> V2Header.LEGACY
        hasMagic(head, MAGIC_V2) && head.size >= STREAM_HEADER_V2 -> V2Header(readLong(head, 4), readInt(head, 12), readInt(head, 16))
        else -> null
    }

    /** Exact size of the encrypted form of [plainSize] bytes (so uploads can send Content-Length and show progress). */
    fun encryptedSize(plainSize: Long): Long {
        val chunks = if (plainSize == 0L) 1 else (plainSize + CHUNK - 1) / CHUNK
        return 4 + NONCE_BYTES + chunks * (4 + TAG_BYTES) + plainSize
    }

    /** v2 blobs carry the sender id and the two epoch numbers after the magic: 16 bytes more than v1. */
    fun encryptedSizeV2(plainSize: Long): Long = encryptedSize(plainSize) + V2_EXTRA

    /** Frame: MAGIC ‖ baseNonce(12) ‖ { len(4) ‖ AES-GCM(chunk, nonce = base ⊕ index, aad = index ‖ last) }* */
    fun encryptStream(key: ByteArray, input: InputStream, out: OutputStream) {
        out.write(MAGIC)
        encryptBody(key, input, out)
    }

    /** Frame: "LCE2" ‖ senderUid(8) ‖ senderEpoch(4) ‖ receiverEpoch(4) ‖ the LCE1 body (base nonce and chunks, see [encryptStream]). */
    fun encryptStreamV2(key: ByteArray, senderUid: Long, senderEpoch: Int, receiverEpoch: Int, input: InputStream, out: OutputStream) {
        require(senderEpoch >= 0 && receiverEpoch >= 0) { "negative epoch" }
        out.write(MAGIC_V2)
        out.write(longBytes(senderUid))
        out.write(intBytes(senderEpoch))
        out.write(intBytes(receiverEpoch))
        encryptBody(key, input, out)
    }

    /** Streams plaintext out of a v1 ("LCE1") blob; throws on any tampering (including truncation) and on a v2 blob (use [decryptingStreamV2]). */
    fun decryptingStream(key: ByteArray, input: InputStream): InputStream = DecryptingStream(input, false) { _, _, _ -> key }

    /**
     * Streams plaintext out of a blob of either generation: reads the header, resolves the key with [keyFor]
     * (sender id and epochs of an "LCE2" header; (0, 0, 0) for an "LCE1" blob, so a v1 blob still decrypts when
     * keyFor(0, 0, 0) returns the legacy key), then behaves like [decryptingStream]. The header is consumed on the
     * first read; an unknown epoch pair (keyFor → null) surfaces there as an IOException, like tampering does.
     */
    fun decryptingStreamV2(input: InputStream, keyFor: (senderUid: Long, senderEpoch: Int, receiverEpoch: Int) -> ByteArray?): InputStream =
        DecryptingStream(input, true, keyFor)

    fun encryptBytes(key: ByteArray, plain: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(plain.size + 64)
        encryptStream(key, plain.inputStream(), out)
        return out.toByteArray()
    }

    fun encryptBytesV2(key: ByteArray, senderUid: Long, senderEpoch: Int, receiverEpoch: Int, plain: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(plain.size + 64)
        encryptStreamV2(key, senderUid, senderEpoch, receiverEpoch, plain.inputStream(), out)
        return out.toByteArray()
    }

    fun decryptBytes(key: ByteArray, blob: ByteArray): ByteArray = decryptingStream(key, blob.inputStream()).use { it.readBytes() }

    fun decryptBytesV2(blob: ByteArray, keyFor: (senderUid: Long, senderEpoch: Int, receiverEpoch: Int) -> ByteArray?): ByteArray =
        decryptingStreamV2(blob.inputStream(), keyFor).use { it.readBytes() }

    // ---- stream implementation ----

    private fun encryptBody(key: ByteArray, input: InputStream, out: OutputStream) {
        val base = ByteArray(NONCE_BYTES).also { rnd.nextBytes(it) }
        out.write(base)
        val buf = ByteArray(CHUNK)
        var index = 0
        var pending = readFully(input, buf)
        while (true) {
            val nextBuf = ByteArray(CHUNK)
            val next = readFully(input, nextBuf)
            val last = next == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(base, index)))
            c.updateAAD(aadFor(index, last))
            val ct = c.doFinal(buf, 0, pending)
            out.write(intBytes(ct.size))
            out.write(ct)
            if (last) break
            System.arraycopy(nextBuf, 0, buf, 0, next)
            pending = next
            index++
        }
        out.flush()
    }

    /** The header (magic, v2 sender and epochs, base nonce) is consumed on the first read; then chunk after chunk, each authenticated. */
    private class DecryptingStream(
        private val input: InputStream,
        private val acceptV2: Boolean,
        private val keyFor: (Long, Int, Int) -> ByteArray?,
    ) : InputStream() {
        private lateinit var key: ByteArray
        private val base = ByteArray(NONCE_BYTES)
        private var index = 0
        private var chunk = ByteArray(0)
        private var pos = 0
        private var done = false
        private var started = false

        private fun start() {
            started = true
            val magic = ByteArray(4)
            if (readFully(input, magic) != 4) throw IOException("not an encrypted blob")
            val resolved = when {
                hasMagic(magic, MAGIC) -> keyFor(0L, 0, 0)
                hasMagic(magic, MAGIC_V2) -> {
                    if (!acceptV2) throw IOException("v2 blob: use decryptingStreamV2")
                    val h = ByteArray(V2_EXTRA)
                    if (readFully(input, h) != V2_EXTRA) throw EOFException("truncated header")
                    keyFor(readLong(h, 0), readInt(h, 8), readInt(h, 12))
                }
                else -> throw IOException("not an encrypted blob")
            }
            key = resolved ?: throw IOException("no key for this blob's epochs")
            if (readFully(input, base) != NONCE_BYTES) throw EOFException("truncated header")
        }

        private fun fill(): Boolean {
            if (done) return false
            if (!started) start()
            val lenB = ByteArray(4)
            if (readFully(input, lenB) != 4) throw EOFException("truncated (no final chunk)")
            val len = readInt(lenB, 0)
            if (len < TAG_BYTES || len > CHUNK + TAG_BYTES) throw IOException("bad chunk length")
            val ct = ByteArray(len)
            if (readFully(input, ct) != len) throw EOFException("truncated chunk")
            // Try "last" first when the chunk is short; a full-size chunk is normally not the last one.
            val plain = tryDecrypt(ct, last = len - TAG_BYTES < CHUNK) ?: tryDecrypt(ct, last = len - TAG_BYTES >= CHUNK)
                ?: throw IOException("decryption failed")
            chunk = plain.first
            pos = 0
            if (plain.second) done = true
            index++
            return true
        }

        private fun tryDecrypt(ct: ByteArray, last: Boolean): Pair<ByteArray, Boolean>? = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(base, index)))
            c.updateAAD(aadFor(index, last))
            c.doFinal(ct) to last
        }.getOrNull()

        override fun read(): Int {
            while (pos >= chunk.size) if (!fill()) return -1
            return chunk[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!fill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }

    // ---- helpers ----

    private fun agree(privB64: String, pubB64: String): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(unb64(privB64)))
        val pub = kf.generatePublic(X509EncodedKeySpec(unb64(pubB64)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /** nonce(12) ‖ AES-GCM(plain, aad) */
    private fun seal(key: ByteArray, plain: String, aad: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray())
        return nonce + c.doFinal(plain.toByteArray())
    }

    private fun open(key: ByteArray, raw: ByteArray, aad: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, NONCE_BYTES))
        c.updateAAD(aad.toByteArray())
        return String(c.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES))
    }

    private fun encodeBody(body: ByteArray, urlSafe: Boolean): String =
        if (urlSafe) java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body) else b64(body)

    /** Accepts both base64 alphabets. */
    private fun decodeBody(enc: String): ByteArray =
        if (enc.contains('-') || enc.contains('_')) java.util.Base64.getUrlDecoder().decode(enc) else unb64(enc)

    private fun epochNumber(s: String): Int? =
        if (s.isEmpty() || s.length > 9 || !s.all { it in '0'..'9' }) null else s.toInt()

    private fun hasMagic(head: ByteArray, magic: ByteArray): Boolean =
        head.size >= 4 && head[0] == magic[0] && head[1] == magic[1] && head[2] == magic[2] && head[3] == magic[3]

    private fun nonceFor(base: ByteArray, index: Int): ByteArray {
        val n = base.copyOf()
        n[8] = (n[8].toInt() xor (index ushr 24)).toByte()
        n[9] = (n[9].toInt() xor (index ushr 16)).toByte()
        n[10] = (n[10].toInt() xor (index ushr 8)).toByte()
        n[11] = (n[11].toInt() xor index).toByte()
        return n
    }

    private fun aadFor(index: Int, last: Boolean): ByteArray = intBytes(index) + byteArrayOf(if (last) 1 else 0)

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun longBytes(v: Long) = intBytes((v ushr 32).toInt()) + intBytes(v.toInt())

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    private fun readLong(b: ByteArray, off: Int): Long =
        ((readInt(b, off).toLong() and 0xffffffffL) shl 32) or (readInt(b, off + 4).toLong() and 0xffffffffL)

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun sortedPubs(a: String, b: String): ByteArray {
        val x = unb64(a); val y = unb64(b)
        val first = if (compare(x, y) <= 0) x else y
        val second = if (first === x) y else x
        return first + second
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0)
        var filled = 0
        var i = 1
        while (filled < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - filled)
            System.arraycopy(t, 0, out, filled, n)
            filled += n
            i++
        }
        return out
    }

    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s.trim())
}

/** Tiny affine-coordinate scalar multiplication for P-256, only used to recover a public key from an imported private key. */
internal object EcMath {
    fun multiply(params: java.security.spec.ECParameterSpec, d: java.math.BigInteger): java.security.spec.ECPoint {
        val p = (params.curve.field as java.security.spec.ECFieldFp).p
        val a = params.curve.a
        var result: java.security.spec.ECPoint? = null
        var addend: java.security.spec.ECPoint? = params.generator
        var k = d
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend, p, a)
            addend = add(addend, addend, p, a)
            k = k.shiftRight(1)
        }
        return result ?: throw IllegalArgumentException("zero key")
    }

    private fun add(p1: java.security.spec.ECPoint?, p2: java.security.spec.ECPoint?, p: java.math.BigInteger, a: java.math.BigInteger): java.security.spec.ECPoint? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        val (x1, y1) = p1.affineX to p1.affineY
        val (x2, y2) = p2.affineX to p2.affineY
        val lambda = if (x1 == x2) {
            if (y1 != y2 || y1.signum() == 0) return null
            (x1 * x1 * java.math.BigInteger.valueOf(3) + a) * (y1 * java.math.BigInteger.valueOf(2)).modInverse(p)
        } else {
            (y2 - y1) * (x2 - x1).modInverse(p)
        }.mod(p)
        val x3 = (lambda * lambda - x1 - x2).mod(p)
        val y3 = (lambda * (x1 - x3) - y1).mod(p)
        return java.security.spec.ECPoint(x3, y3)
    }
}
