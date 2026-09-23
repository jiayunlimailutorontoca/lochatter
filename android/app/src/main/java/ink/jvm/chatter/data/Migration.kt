package ink.jvm.chatter.data

import kotlinx.serialization.Serializable

/** What travels in the migration QR code (encrypted with the 6-digit PIN by [ink.jvm.chatter.crypto.Migration]). */
@Serializable
data class MigrationPayload(
    val server: String,
    val token: String,
    val userId: Long,
    val userName: String,
    val peerId: Long = 0,
    val peerName: String = "",
    val botName: String = "",
    /** KeyRing JSON (identity + epoch keys); may be null on a phone that never had a key. */
    val keyRing: String? = null,
    /** Legacy identity private key when there is no ring yet. */
    val e2ePriv: String? = null,
    val e2ePub: String? = null,
    val peerPub: String? = null,
)
