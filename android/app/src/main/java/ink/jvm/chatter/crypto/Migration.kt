package ink.jvm.chatter.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone-to-phone migration payload: the whole account (token, ids, key ring) as one QR code, encrypted with a
 * 6-digit PIN shown next to it. PBKDF2-HMAC-SHA256 (200 000 rounds) → AES-256-GCM. The code is shown face to
 * face for a few seconds; the PIN keeps a photographed QR from being enough on its own.
 */
object Migration {
    const val PREFIX = "lochatter1:"
    private const val ROUNDS = 200_000
    private val rnd = SecureRandom()

    fun randomPin(): String = "%06d".format(rnd.nextInt(1_000_000))

    fun pack(payload: String, pin: String): String {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(pin, salt), "AES"), GCMParameterSpec(128, nonce))
        val ct = c.doFinal(payload.toByteArray())
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(salt + nonce + ct)
    }

    /** null on a wrong PIN, a foreign prefix, or garbage. */
    fun unpack(text: String, pin: String): String? {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        return runCatching {
            val raw = java.util.Base64.getUrlDecoder().decode(t.substring(PREFIX.length))
            if (raw.size < 16 + 12 + 16) return null
            val salt = raw.copyOfRange(0, 16)
            val nonce = raw.copyOfRange(16, 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(pin, salt), "AES"), GCMParameterSpec(128, nonce))
            String(c.doFinal(raw, 28, raw.size - 28))
        }.getOrNull()
    }

    private fun key(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.trim().toCharArray(), salt, ROUNDS, 256)).encoded
}
