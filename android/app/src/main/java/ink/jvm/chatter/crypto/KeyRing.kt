package ink.jvm.chatter.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Forward-secrecy key material of this user for the chat with the other user. Pure JVM; the app keeps [toJson]
 * (it contains private keys) in the encrypted prefs and restores it with [fromJson].
 *
 * - Identity: the long-term ECDH P-256 pair. The 60-digit safety number stays a function of the two identity public keys.
 * - Epoch keys: fresh P-256 pairs numbered 1, 2, … ([rotate]). Each is published with a SHA256withECDSA signature by
 *   the identity key ([publishBundle], format in [Bundle]) so the peer verifies continuity ([adoptPeer]) without a
 *   safety-number change. Old epoch private keys are kept so media on the server stays readable; call
 *   [forgetMyEpochsBelow] to actually destroy them once they are no longer needed.
 * - Session keys ([keyFor]): a message from A at epoch a to B at epoch b uses
 *   HKDF(ECDH(A_a, B_b), salt = SHA-256(sorted pubs), info = "lochatter-e2e-v2|a.b") — see [E2E.deriveV2]. When both
 *   epochs are 0 the legacy v1 key from the identity keys is used ([E2E.derive]); one side at 0 has no key (null).
 * - The peer's identity: adopting a bundle signed by a different identity switches to it and reports
 *   `identityChanged` (the safety number changed; the caller shows that). The previous identity's epoch public keys are
 *   kept aside so blobs encrypted under them still resolve when the current identity has no key of that number.
 * - Replay: a bundle whose newest epoch is lower than the highest already verified from that identity, or that names a
 *   known epoch number with a different key, is rejected ([AdoptResult.Replay]); re-sending a known chain is fine.
 *
 * All methods are synchronized: the download interceptor and the repository call in from different threads.
 */
class KeyRing(identityPriv: String, identityPub: String, myUserId: Long) {

    /** One of my epoch keys. [sig] is the identity signature over [E2E.epochSignData] that travels in the bundle. */
    class EpochKey(val n: Int, val pub: String, val priv: String, val createdAt: Long = 0L, val sig: String = "")

    /** Outcome of [adoptPeer]. Only [Legacy] and [Verified] record anything. */
    sealed class AdoptResult {
        /** A bare legacy public key: identity only, no epoch key yet (the peer still runs v1). */
        data class Legacy(val identityPub: String, val identityChanged: Boolean) : AdoptResult()

        /** Every entry of the chain verified against the bundle's identity key; [newEpochs] of them were not known before. */
        data class Verified(val identityPub: String, val epoch: Int, val epochPub: String, val identityChanged: Boolean, val newEpochs: Int) : AdoptResult()

        /** Newest epoch below the highest already verified from this identity, or a known epoch number with a different key. Nothing recorded. */
        data class Replay(val identityPub: String, val epoch: Int, val highestSeen: Int) : AdoptResult()

        /** Some entry's signature does not verify against the bundle's identity key. Nothing recorded. */
        data object BadSignature : AdoptResult()

        /** Not a bundle, not a public key, or an epoch key that is not a valid P-256 point. */
        data object Malformed : AdoptResult()
    }

    /**
     * What to encrypt an outgoing message with right now: the key plus the header values. v2 when both sides have an
     * epoch key, otherwise the legacy v1 formats. Take one and use it for the whole upload so size and stream agree.
     */
    class Session(val key: ByteArray, val senderUid: Long, val senderEpoch: Int, val receiverEpoch: Int) {
        val v2: Boolean get() = senderEpoch > 0 && receiverEpoch > 0

        fun encryptText(plain: String, aad: String, urlSafe: Boolean = false): String =
            if (v2) E2E.encryptTextV2(key, senderUid, senderEpoch, receiverEpoch, plain, aad, urlSafe) else E2E.encryptText(key, plain, aad, urlSafe)

        fun encryptedSize(plainSize: Long): Long = if (v2) E2E.encryptedSizeV2(plainSize) else E2E.encryptedSize(plainSize)

        fun encryptStream(input: InputStream, out: OutputStream) =
            if (v2) E2E.encryptStreamV2(key, senderUid, senderEpoch, receiverEpoch, input, out) else E2E.encryptStream(key, input, out)

        fun encryptBytes(plain: ByteArray): ByteArray =
            if (v2) E2E.encryptBytesV2(key, senderUid, senderEpoch, receiverEpoch, plain) else E2E.encryptBytes(key, plain)
    }

    /**
     * What travels in the `/keys` pubKey field. v2: `v2|<identityPub>|<n>|<pub_n>|<sig_n>|<n-1>|<pub_n-1>|<sig_n-1>|…`
     * — the recent epoch chain, newest first, at most [MAX_CHAIN] entries, each signed individually (see
     * [E2E.epochSignData]). A bare X.509 base64 public key (no `|`) is the legacy v1 form: [epoch] 0, no [epochPub].
     * With 12 entries a bundle is about 2.9 k characters (the server allows 4096).
     */
    data class Bundle(val identityPub: String, val entries: List<Entry>) {
        data class Entry(val epoch: Int, val pub: String, val sig: String)

        /** Newest epoch in the bundle, 0 for a legacy key. */
        val epoch: Int get() = entries.firstOrNull()?.epoch ?: 0
        val epochPub: String? get() = entries.firstOrNull()?.pub
        val isLegacy: Boolean get() = entries.isEmpty()

        fun format(): String {
            if (isLegacy) return identityPub
            val sb = StringBuilder("v2|").append(identityPub)
            for (e in entries) sb.append('|').append(e.epoch).append('|').append(e.pub).append('|').append(e.sig)
            return sb.toString()
        }

        companion object {
            const val MAX_CHAIN = 12

            /** null when the text is neither a v2 bundle (strictly descending epochs ≥ 1) nor a single base64 token. Does no crypto. */
            fun parse(text: String): Bundle? {
                val t = text.trim()
                if (t.isEmpty()) return null
                if (!t.contains('|')) return if (isB64(t)) Bundle(t, emptyList()) else null
                val p = t.split('|')
                if (p[0] != "v2" || p.size < 5 || (p.size - 2) % 3 != 0 || !isB64(p[1])) return null
                val entries = ArrayList<Entry>((p.size - 2) / 3)
                var prev = Int.MAX_VALUE
                var i = 2
                while (i < p.size) {
                    val n = p[i].toIntOrNull() ?: return null
                    if (n < 1 || n >= prev || !isB64(p[i + 1]) || !isB64(p[i + 2])) return null
                    entries.add(Entry(n, p[i + 1], p[i + 2]))
                    prev = n
                    i += 3
                }
                return Bundle(p[1], entries)
            }

            private fun isB64(s: String): Boolean =
                s.isNotEmpty() && s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
        }
    }

    private class PeerRecord(val pubs: HashMap<Int, String> = HashMap(), var highest: Int = 0)
    private class RetiredPeer(val identityPub: String, val record: PeerRecord)

    private val idPriv = identityPriv
    private val idPub = identityPub
    private val uid = myUserId
    /** Ascending by number. */
    private val mine = ArrayList<EpochKey>()
    private var peerId: String? = null
    private var peer = PeerRecord()
    /** Earlier peer identities (oldest first) with their verified epoch keys, for blobs from before a peer key change. */
    private val retired = ArrayList<RetiredPeer>()
    private val cache = HashMap<String, ByteArray>()

    // ---- me ----

    @Synchronized fun identityPub(): String = idPub
    @Synchronized fun identityPriv(): String = idPriv
    @Synchronized fun myUserId(): Long = uid

    /** My newest epoch number, 0 before the first [rotate]. */
    @Synchronized fun currentEpoch(): Int = mine.lastOrNull()?.n ?: 0

    /** My epoch numbers, ascending. */
    @Synchronized fun epochs(): List<Int> = mine.map { it.n }

    @Synchronized fun myEpochs(): List<EpochKey> = mine.toList()

    @Synchronized fun myEpochPriv(n: Int): String? = mine.firstOrNull { it.n == n }?.priv

    @Synchronized fun myEpochPub(n: Int): String? = mine.firstOrNull { it.n == n }?.pub

    /** When the current epoch key was made (0 before the first rotation); feed this to [shouldRotate]. */
    @Synchronized fun lastRotationAt(): Long = mine.lastOrNull()?.createdAt ?: 0L

    /** Generates epoch currentEpoch() + 1 and makes it current. Publish the new [publishBundle] afterwards. */
    @Synchronized fun rotate(now: Long = System.currentTimeMillis()): EpochKey = rotateTo(currentEpoch() + 1, now)

    /**
     * Like [rotate] but numbers the new key [epoch] (> [currentEpoch]). For a ring rebuilt around an imported identity:
     * continue above the epoch the server still shows for me, so the peer does not see a replay.
     */
    @Synchronized fun rotateTo(epoch: Int, now: Long = System.currentTimeMillis()): EpochKey {
        require(epoch > currentEpoch()) { "epoch $epoch is not above ${currentEpoch()}" }
        val kp = E2E.generate()
        val k = EpochKey(epoch, kp.pub, kp.priv, now, E2E.sign(idPriv, E2E.epochSignData(epoch, kp.pub)))
        mine.add(k)
        return k
    }

    /** Destroys my epoch private keys numbered below [n] (never the current one): blobs keyed with them become unreadable. */
    @Synchronized fun forgetMyEpochsBelow(n: Int) {
        val limit = minOf(n, currentEpoch())
        if (mine.removeAll { it.n < limit }) cache.clear()
    }

    /** v2 bundle with my newest [Bundle.MAX_CHAIN] epoch keys (rotates first when I have none). Deterministic for unchanged state. */
    @Synchronized fun publishBundle(): String {
        if (mine.isEmpty()) rotate()
        val from = maxOf(0, mine.size - Bundle.MAX_CHAIN)
        for (i in from until mine.size) {
            val k = mine[i]
            if (k.sig.isEmpty()) mine[i] = EpochKey(k.n, k.pub, k.priv, k.createdAt, E2E.sign(idPriv, E2E.epochSignData(k.n, k.pub)))
        }
        val chain = mine.subList(from, mine.size).asReversed().map { Bundle.Entry(it.n, it.pub, it.sig) }
        return Bundle(idPub, chain).format()
    }

    // ---- peer ----

    @Synchronized fun peerIdentityPub(): String? = peerId

    /** The peer's newest verified epoch, 0 while the peer has no verified epoch key. */
    @Synchronized fun peerEpoch(): Int = peer.highest

    /** Verified epoch public key [n] of the current peer identity, else of an earlier identity (newest first), else null. */
    @Synchronized fun peerEpochPub(n: Int): String? = peer.pubs[n] ?: retired.asReversed().firstNotNullOfOrNull { it.record.pubs[n] }

    /**
     * Takes in what the server holds as the peer's key. Verifies every chain entry against the bundle's own identity
     * key, checks for replay, then records the epoch keys and, when the identity differs from the trusted one and
     * [acceptNewIdentity] is set, switches to it (the result says `identityChanged`; the caller shows the new safety
     * number). With [acceptNewIdentity] false a bundle from a new identity is verified and reported but not recorded.
     */
    @Synchronized fun adoptPeer(bundleText: String, acceptNewIdentity: Boolean = true): AdoptResult {
        val b = Bundle.parse(bundleText) ?: return AdoptResult.Malformed
        if (!E2E.isValidPublic(b.identityPub)) return AdoptResult.Malformed
        val changed = peerId != null && peerId != b.identityPub
        if (b.isLegacy) {
            if (!changed || acceptNewIdentity) switchPeer(b.identityPub)
            return AdoptResult.Legacy(b.identityPub, changed)
        }
        for (e in b.entries) if (!E2E.verify(b.identityPub, E2E.epochSignData(e.epoch, e.pub), e.sig)) return AdoptResult.BadSignature
        if (b.entries.any { !E2E.isValidPublic(it.pub) }) return AdoptResult.Malformed
        val rec = recordFor(b.identityPub)
        if (b.epoch < rec.highest) return AdoptResult.Replay(b.identityPub, b.epoch, rec.highest)
        for (e in b.entries) {
            val known = rec.pubs[e.epoch]
            if (known != null && known != e.pub) return AdoptResult.Replay(b.identityPub, e.epoch, rec.highest)
        }
        val fresh = b.entries.count { !rec.pubs.containsKey(it.epoch) }
        if (changed && !acceptNewIdentity) return AdoptResult.Verified(b.identityPub, b.epoch, b.epochPub!!, true, fresh)
        switchPeer(b.identityPub)
        for (e in b.entries) peer.pubs[e.epoch] = e.pub
        peer.highest = maxOf(peer.highest, b.epoch)
        return AdoptResult.Verified(b.identityPub, b.epoch, b.epochPub!!, changed, fresh)
    }

    /** Drops everything known about the peer (identity, epoch keys, earlier identities). */
    @Synchronized fun forgetPeer() {
        peerId = null
        peer = PeerRecord()
        retired.clear()
        cache.clear()
    }

    // ---- session keys ----

    /**
     * Key for a blob whose header says [senderUid] sent it with epochs ([senderEpoch], [receiverEpoch]); I am the
     * sender when [senderUid] is my user id. (0, 0) → legacy key; one side 0 or an unknown epoch → null.
     */
    @Synchronized fun keyFor(senderUid: Long, senderEpoch: Int, receiverEpoch: Int): ByteArray? =
        keyFor(senderEpoch, receiverEpoch, iAmSender = senderUid == uid)

    /** Same with the role given explicitly: my epoch is [senderEpoch] when [iAmSender], else [receiverEpoch]. */
    @Synchronized fun keyFor(senderEpoch: Int, receiverEpoch: Int, iAmSender: Boolean): ByteArray? {
        if (senderEpoch == 0 && receiverEpoch == 0) return legacyKey()
        if (senderEpoch <= 0 || receiverEpoch <= 0) return null
        val id = "$senderEpoch.$receiverEpoch.$iAmSender"
        cache[id]?.let { return it }
        val myN = if (iAmSender) senderEpoch else receiverEpoch
        val my = mine.firstOrNull { it.n == myN } ?: return null
        val peerPub = peerEpochPub(if (iAmSender) receiverEpoch else senderEpoch) ?: return null
        val key = E2E.deriveV2(my.priv, peerPub, my.pub, senderEpoch, receiverEpoch)
        remember(id, key)
        return key
    }

    /** Key and header for a message I send now: my current epoch × the peer's; legacy while either side is at 0. null without a peer. */
    @Synchronized fun currentSession(): Session? {
        val m = currentEpoch()
        val p = peer.highest
        if (m == 0 || p == 0) return legacyKey()?.let { Session(it, uid, 0, 0) }
        return keyFor(m, p, iAmSender = true)?.let { Session(it, uid, m, p) }
    }

    @Synchronized fun currentKey(): ByteArray? = currentSession()?.key

    /** Encrypts with [currentSession]; null when there is no key yet. */
    @Synchronized fun encryptText(plain: String, aad: String, urlSafe: Boolean = false): String? =
        currentSession()?.encryptText(plain, aad, urlSafe)

    /**
     * Session keys this phone can derive, so the server can open a ciphertext for an offline push.
     * (0, 0) is the legacy key. Each pair is the epoch numbers written in an e2e2 header.
     */
    @Synchronized fun exportPushKeys(): List<Triple<Int, Int, ByteArray>> {
        val out = LinkedHashMap<Pair<Int, Int>, ByteArray>()
        keyFor(0, 0, iAmSender = true)?.let { out[0 to 0] = it }
        val mineNs = mine.map { it.n }.filter { it > 0 }
        val peerNs = if (peer.highest > 0) (1..peer.highest).toList() else emptyList()
        for (s in mineNs) for (r in peerNs) keyFor(s, r, iAmSender = true)?.let { out[s to r] = it }
        for (s in peerNs) for (r in mineNs) keyFor(s, r, iAmSender = false)?.let { out[s to r] = it }
        return out.entries.take(80).map { Triple(it.key.first, it.key.second, it.value) }
    }

    /** Decrypts a text of either generation through [keyFor]; null when it is not e2e, the key is unknown, or GCM rejects it. */
    fun decryptText(text: String, aad: String): String? = E2E.decryptTextV2(text, aad) { uid, s, r -> keyFor(uid, s, r) }

    /** Plaintext stream of a blob of either generation, keyed through [keyFor] (throws on the first read when the key is unknown). */
    fun decryptingStream(input: InputStream): InputStream = E2E.decryptingStreamV2(input) { uid, s, r -> keyFor(uid, s, r) }

    fun decryptBytes(blob: ByteArray): ByteArray = E2E.decryptBytesV2(blob) { uid, s, r -> keyFor(uid, s, r) }

    // ---- rotation policy ----

    /** Due when never rotated, when [intervalMs] has passed since [lastRotationAt], or when the clock went backwards. */
    fun shouldRotate(now: Long, lastRotationAt: Long, intervalMs: Long = DEFAULT_ROTATION_MS): Boolean =
        Companion.shouldRotate(now, lastRotationAt, intervalMs)

    /** [shouldRotate] against my current epoch key's [lastRotationAt]. */
    @Synchronized fun rotationDue(now: Long = System.currentTimeMillis(), intervalMs: Long = DEFAULT_ROTATION_MS): Boolean =
        Companion.shouldRotate(now, lastRotationAt(), intervalMs)

    // ---- persistence (contains private keys: encrypted prefs only) ----

    @Synchronized fun toJson(): String {
        val dto = RingDto(
            myUserId = uid,
            identityPub = idPub,
            identityPriv = idPriv,
            epochs = mine.map { EpochDto(it.n, it.pub, it.priv, it.createdAt, it.sig) },
            peerIdentityPub = peerId,
            peer = peer.toDto(),
            retired = retired.map { RetiredDto(it.identityPub, it.record.toDto()) },
        )
        return JSON.encodeToString(RingDto.serializer(), dto)
    }

    // ---- internals ----

    private fun legacyKey(): ByteArray? {
        cache["legacy"]?.let { return it }
        val p = peerId ?: return null
        val key = E2E.derive(idPriv, p, idPub)
        remember("legacy", key)
        return key
    }

    private fun remember(id: String, key: ByteArray) {
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[id] = key
    }

    private fun recordFor(identityPub: String): PeerRecord =
        if (peerId == identityPub) peer else retired.firstOrNull { it.identityPub == identityPub }?.record ?: PeerRecord()

    /** Makes [identityPub] the trusted peer identity, parking the previous one (with its epoch keys) in [retired]. */
    private fun switchPeer(identityPub: String) {
        val old = peerId
        if (old == identityPub) return
        if (old != null) {
            retired.removeAll { it.identityPub == old }
            retired.add(RetiredPeer(old, peer))
            while (retired.size > MAX_RETIRED) retired.removeAt(0)
        }
        val back = retired.indexOfFirst { it.identityPub == identityPub }
        peer = if (back >= 0) retired.removeAt(back).record else PeerRecord()
        peerId = identityPub
        cache.clear()
    }

    private fun PeerRecord.toDto() = PeerDto(highest, pubs.toSortedMap())

    companion object {
        const val DEFAULT_ROTATION_MS: Long = 7L * 24 * 3600 * 1000
        private const val MAX_CACHE = 64
        private const val MAX_RETIRED = 4
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Rotation policy: due when never rotated ([lastRotationAt] ≤ 0), after [intervalMs], or when the clock went backwards. */
        fun shouldRotate(now: Long, lastRotationAt: Long, intervalMs: Long = DEFAULT_ROTATION_MS): Boolean =
            lastRotationAt <= 0L || now < lastRotationAt || now - lastRotationAt >= intervalMs

        /** Restores [toJson]; throws IllegalArgumentException when the JSON is not a ring or lacks the user id (use the two-argument form then). */
        fun fromJson(json: String): KeyRing = restore(json, null)

        /** Like [fromJson] but a JSON without `myUserId` (older export) gets [fallbackUserId]. */
        fun fromJson(json: String, fallbackUserId: Long): KeyRing = restore(json, fallbackUserId)

        private fun restore(text: String, fallbackUserId: Long?): KeyRing {
            val dto = runCatching { JSON.decodeFromString(RingDto.serializer(), text) }
                .getOrElse { throw IllegalArgumentException("not a key ring", it) }
            val uid = dto.myUserId ?: fallbackUserId ?: throw IllegalArgumentException("key ring JSON lacks myUserId; use fromJson(json, fallbackUserId)")
            val ring = KeyRing(dto.identityPriv, dto.identityPub, uid)
            for (e in dto.epochs.sortedBy { it.n }) ring.mine.add(EpochKey(e.n, e.pub, e.priv, e.createdAt, e.sig))
            ring.peerId = dto.peerIdentityPub
            ring.peer = dto.peer?.toRecord() ?: PeerRecord()
            for (r in dto.retired) ring.retired.add(RetiredPeer(r.identityPub, r.peer.toRecord()))
            return ring
        }

        private fun PeerDto.toRecord() = PeerRecord(HashMap(pubs), highest)
    }
}

@Serializable
private class RingDto(
    val v: Int = 1,
    val myUserId: Long? = null,
    val identityPub: String,
    val identityPriv: String,
    val epochs: List<EpochDto> = emptyList(),
    val peerIdentityPub: String? = null,
    val peer: PeerDto? = null,
    val retired: List<RetiredDto> = emptyList(),
)

@Serializable
private class EpochDto(val n: Int, val pub: String, val priv: String, val createdAt: Long = 0L, val sig: String = "")

@Serializable
private class PeerDto(val highest: Int = 0, val pubs: Map<Int, String> = emptyMap())

@Serializable
private class RetiredDto(val identityPub: String, val peer: PeerDto)
