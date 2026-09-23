package ink.jvm.chatter.data

import ink.jvm.chatter.crypto.E2E
import ink.jvm.chatter.crypto.KeyRing
import ink.jvm.chatter.util.Diag

/**
 * Owns the end-to-end key material for the repository: the identity key (safety number), the rotating epoch
 * keys (forward secrecy, weekly) and the peer's verified epochs. Everything persists through [Prefs].
 * Text and media are encrypted in the v2 format once both sides have an epoch key; before that (or with a
 * 1.3 peer) the legacy v1 identity-derived key is used, and both formats always decrypt.
 */
class KeyManager(private val prefs: Prefs, private val log: (String) -> Unit = { Diag.log("KeyManager", it) }) {
    @Volatile var ring: KeyRing? = null
        private set

    /** (Re)loads the persisted ring or builds one from the legacy identity key (identity keys are never regenerated here). */
    fun load(userId: Long) {
        if (userId <= 0) { ring = null; return }
        val json = prefs.keyRing
        val r = if (json != null) runCatching { KeyRing.fromJson(json, userId) }.onFailure { log("ring json unreadable: ${it.message}") }.getOrNull() else null
        if (r != null && r.myUserId() == userId) { ring = r; return }
        ring = null
        val priv = prefs.e2ePriv
        val pub = prefs.e2ePub
        if (priv != null && pub != null) {
            val fresh = KeyRing(priv, pub, userId)
            prefs.peerPub?.let { legacy -> runCatching { fresh.adoptPeer(legacy) } }
            ring = fresh
            save()
            log("ring built from legacy identity")
        }
    }

    /** The server still shows a newer epoch of mine (re-install / pasted key): continue above it so the peer sees no replay. */
    fun catchUp(serverEpoch: Int) {
        val r = ring ?: return
        if (serverEpoch > r.currentEpoch()) {
            r.rotateTo(serverEpoch + 1)
            prefs.lastRotationAt = System.currentTimeMillis()
            save()
            log("caught up to epoch ${serverEpoch + 1}")
        }
    }

    fun save() { ring?.let { prefs.keyRing = it.toJson() } }

    /** Derived session keys for the server's offline-push decrypt. Empty until the ring is loaded. */
    fun exportPushKeys(): List<Triple<Int, Int, ByteArray>> = ring?.exportPushKeys().orEmpty()

    /** Ensures an identity exists; returns true when a new one had to be generated. */
    fun ensureIdentity(userId: Long): Boolean {
        if (ring != null) return false
        if (prefs.e2ePriv != null && prefs.e2ePub != null) { load(userId); return false }
        val kp = E2E.generate()
        prefs.e2ePriv = kp.priv
        prefs.e2ePub = kp.pub
        ring = KeyRing(kp.priv, kp.pub, userId)
        save()
        return true
    }

    /** What to publish through POST /keys: the v2 bundle (rotating first if we never did). */
    fun bundle(): String? {
        val r = ring ?: return null
        val b = r.publishBundle()
        if (prefs.lastRotationAt == 0L) prefs.lastRotationAt = System.currentTimeMillis()
        save()
        return b
    }

    /** Weekly rotation, only when the peer already speaks v2 (otherwise a rotation buys nothing). */
    fun maybeRotate(): Boolean {
        val r = ring ?: return false
        if (r.peerEpoch() == 0) return false
        if (!KeyRing.shouldRotate(System.currentTimeMillis(), prefs.lastRotationAt)) return false
        r.rotate()
        prefs.lastRotationAt = System.currentTimeMillis()
        save()
        log("rotated to epoch ${r.currentEpoch()}")
        return true
    }

    fun rotateNow() {
        val r = ring ?: return
        r.rotate()
        prefs.lastRotationAt = System.currentTimeMillis()
        save()
    }

    /** Result of adopting whatever the server holds for the peer. */
    enum class Adopt { NONE, FIRST, SAME, IDENTITY_CHANGED, BAD }

    /** Feeds the peer's published key (bare legacy key or v2 bundle) into the ring; returns what changed. */
    fun adoptPeer(published: String?): Adopt {
        val r = ring ?: return Adopt.NONE
        if (published.isNullOrBlank()) return Adopt.NONE
        val before = r.peerIdentityPub()
        val res = runCatching { r.adoptPeer(published) }.getOrElse { log("adopt failed: ${it.message}"); return Adopt.BAD }
        val identity = when (res) {
            is KeyRing.AdoptResult.Legacy -> res.identityPub
            is KeyRing.AdoptResult.Verified -> res.identityPub
            is KeyRing.AdoptResult.Replay -> { log("peer bundle: replay (epoch ${res.epoch} < ${res.highestSeen}), ignored"); return Adopt.SAME }
            is KeyRing.AdoptResult.BadSignature -> { log("peer bundle: bad signature"); return Adopt.BAD }
            is KeyRing.AdoptResult.Malformed -> { log("peer bundle: malformed"); return Adopt.BAD }
        }
        save()
        return when {
            before == null -> Adopt.FIRST
            before != identity -> Adopt.IDENTITY_CHANGED
            else -> Adopt.SAME
        }
    }

    // ---- what the repository encrypts with ----

    /** Legacy v1 key (identity × identity), or null before both identities are known. */
    fun legacyKey(): ByteArray? = ring?.let { it.keyFor(it.myUserId(), 0, 0) }

    /** True when both sides have an epoch key: new content goes out as v2. */
    fun v2Ready(): Boolean = ring?.let { it.currentEpoch() > 0 && it.peerEpoch() > 0 } == true

    fun myEpoch(): Int = ring?.currentEpoch() ?: 0
    fun peerEpoch(): Int = ring?.peerEpoch() ?: 0
    fun myUserId(): Long = ring?.myUserId() ?: 0

    /** Key for a v2 blob written by [senderUid] with epochs (a, b), or null when we lack one of them. */
    fun keyFor(senderUid: Long, senderEpoch: Int, receiverEpoch: Int): ByteArray? = ring?.keyFor(senderUid, senderEpoch, receiverEpoch)

    fun currentKey(): ByteArray? = ring?.currentKey()

    fun peerIdentityPub(): String? = ring?.peerIdentityPub()
    fun identityPub(): String? = ring?.identityPub() ?: prefs.e2ePub
}
