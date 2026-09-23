package ink.jvm.chatter.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store backed by an AES-256-GCM key in the Android Keystore (never leaves the
 * hardware / TEE). Replaces androidx.security's EncryptedSharedPreferences, whose last release was an
 * alpha in 2023. Values are stored as base64(nonce ‖ ciphertext) with the key name as associated data.
 * Only a handful of values live here (device token, e2e private keys), so simplicity beats performance.
 */
class SecurePrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val key: SecretKey? = runCatching { loadOrCreateKey() }.onFailure { Log.w(TAG, "keystore unavailable: ${it.message}") }.getOrNull()

    /** True when the Keystore key could be created; otherwise callers should fall back to plain prefs. */
    val available: Boolean get() = key != null

    fun getString(name: String, default: String? = null): String? {
        val k = key ?: return default
        val raw = sp.getString(name, null) ?: return default
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, bytes, 0, NONCE))
            c.updateAAD(name.toByteArray())
            String(c.doFinal(bytes, NONCE, bytes.size - NONCE))
        }.onFailure { Log.w(TAG, "cannot decrypt '$name': ${it.message}") }.getOrNull() ?: default
    }

    fun putString(name: String, value: String?) {
        val k = key ?: return
        if (value == null) { sp.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        c.updateAAD(name.toByteArray())
        val iv = c.iv
        val ct = c.doFinal(value.toByteArray())
        sp.edit().putString(name, Base64.encodeToString(iv + ct, Base64.NO_WRAP)).apply()
    }

    fun contains(name: String): Boolean = sp.contains(name)

    fun clear() = sp.edit().clear().apply()

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "SecurePrefs"
        const val FILE = "chatter.secure2"
        const val ALIAS = "lochatter-prefs"
        const val NONCE = 12
    }
}
