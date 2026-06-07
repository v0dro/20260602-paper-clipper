package com.example.paperclipper.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the passphrase that encrypts the SQLCipher database.
 *
 * The passphrase is 32 random bytes generated once and persisted, but never in the clear: it is
 * wrapped with an AES-256-GCM key that lives in the Android Keystore (hardware-backed where
 * available, non-exportable), and only the IV + ciphertext are stored in a private SharedPreferences
 * file. The raw passphrase therefore never touches disk and is never logged.
 *
 * Because the wrapping key is device-bound, losing it (Keystore reset / OEM invalidation) makes the
 * database unrecoverable — that is the accepted trade-off for encryption at rest. [reset] lets the
 * caller recover by wiping the key so a fresh database can be created.
 */
internal object DbKeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "paperclipper_db_key"
    private const val PREFS = "paperclipper_secure"
    private const val PREF_IV = "db_pass_iv"
    private const val PREF_CT = "db_pass_ct"
    private const val PASSPHRASE_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Returns the database passphrase, generating and persisting it on first use. Throws if the
     * Keystore key exists but cannot decrypt the stored blob (e.g. it was invalidated) — the caller
     * should treat that as "DB unrecoverable", [reset], and retry.
     */
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivB64 = prefs.getString(PREF_IV, null)
        val ctB64 = prefs.getString(PREF_CT, null)
        if (ivB64 != null && ctB64 != null) {
            val key = secretKey() ?: error("DB key missing from Keystore but wrapped passphrase present")
            return decrypt(key, Base64.decode(ivB64, Base64.NO_WRAP), Base64.decode(ctB64, Base64.NO_WRAP))
        }

        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val (iv, ct) = encrypt(getOrCreateKey(), passphrase)
        prefs.edit()
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREF_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    /** Drops the Keystore key and the wrapped passphrase so a fresh database/key pair can be created. */
    fun reset(context: Context) {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PREF_IV).remove(PREF_CT).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        secretKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun secretKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun encrypt(key: SecretKey, data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(data)
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
