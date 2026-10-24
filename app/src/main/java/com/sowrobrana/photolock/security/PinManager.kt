package com.sowrobrana.photolock.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Owns the vault PIN — as a salted PBKDF2 verifier only.
 *
 *  - The PIN is hashed with PBKDF2-HMAC-SHA256 (120k iterations, 256-bit
 *    output) against a random per-install 16-byte salt.
 *  - Salt + hash (Base64) are stored in EncryptedSharedPreferences, whose
 *    own master key lives in the Android Keystore.
 *  - The PIN itself is never stored, and it does NOT derive the media
 *    encryption key (see PLAN.md §8) — it is an app gate, so changing the
 *    PIN never re-encrypts the vault.
 *
 * All methods do disk + CPU work: call them from an IO/Default dispatcher,
 * never from the main thread.
 */
class PinManager(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    /** True once a PIN has been created (first-run flow checks this). */
    fun hasPin(): Boolean = prefs.contains(KEY_HASH)

    /** PIN length chosen at creation time (4 or 6). Defaults to 4. */
    fun pinLength(): Int = prefs.getInt(KEY_LENGTH, DEFAULT_PIN_LENGTH)

    /** Creates (or replaces) the PIN verifier for [pin]. */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_LENGTH, pin.length)
            .apply()
    }

    /** Constant-time verification of [pin] against the stored verifier. */
    fun verify(pin: String): Boolean {
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val actual = pbkdf2(pin, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    /**
     * Verifies [oldPin] first; only then replaces the PIN with [newPin].
     * Returns false (and changes nothing) when the old PIN is wrong.
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verify(oldPin)) return false
        setPin(newPin)
        return true
    }

    /** Removes the verifier entirely (vault reset flow; Phase 2/3). */
    fun clearPin() {
        prefs.edit().clear().apply()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        const val DEFAULT_PIN_LENGTH = 4
        const val ALT_PIN_LENGTH = 6

        private const val PREFS_FILE = "photolock_pin"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_LENGTH = "pin_length"

        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
    }
}
