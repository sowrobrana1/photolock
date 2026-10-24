package com.sowrobrana.photolock.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM file encryption backed by the Android Keystore.
 *
 * The key is generated inside the Keystore (hardware-backed where the device
 * supports it) and never leaves it — only encrypt/decrypt operations are
 * performed with it. If the user clears the app data or the key is deleted,
 * vault files become unrecoverable BY DESIGN; the app must warn about this
 * before the first import (see PLAN.md).
 *
 * Encrypted file format (per file):
 *   [1 byte: IV length][IV bytes][AES-GCM ciphertext + 128-bit tag]
 *
 * Streaming API so multi-hundred-MB videos never load fully into memory.
 *
 * Known caveat: CipherInputStream historically swallows some AEAD tag
 * failures as EOF on old Android versions. Phase 2 hardening item: move to a
 * chunked-AEAD format (e.g. 64 KiB segments, each with its own tag) so any
 * tampering is detected deterministically — tracked in PLAN.md.
 */
class CryptoManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
        load(null)
    }

    /** True once the vault key exists (i.e. the vault has been initialised). */
    fun keyExists(): Boolean = keyStore.containsAlias(keyAlias)

    /**
     * Encrypts everything read from [input] into [output], prepending the IV
     * header described above. Closes [output]'s cipher stream (which writes
     * the final GCM tag) but not [input].
     */
    fun encrypt(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        output.write(iv.size)
        output.write(iv)

        CipherOutputStream(output, cipher).use { cipherOutput ->
            input.copyTo(cipherOutput, BUFFER_SIZE)
        }
    }

    /**
     * Decrypts a stream produced by [encrypt]. Throws if the header is
     * malformed; a wrong/corrupted key or tampered ciphertext fails the GCM
     * tag check while streaming.
     */
    fun decrypt(input: InputStream, output: OutputStream) {
        val ivLength = input.read()
        require(ivLength > 0) { "Corrupt vault file: missing IV header" }

        val iv = ByteArray(ivLength)
        var readTotal = 0
        while (readTotal < ivLength) {
            val n = input.read(iv, readTotal, ivLength - readTotal)
            require(n != -1) { "Corrupt vault file: truncated IV" }
            readTotal += n
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )

        CipherInputStream(input, cipher).use { cipherInput ->
            cipherInput.copyTo(output, BUFFER_SIZE)
        }
    }

    /**
     * Destroys the vault key. After this call every vault file is permanently
     * unreadable — only call from an explicit "reset vault" flow after the
     * user has exported their media and confirmed twice.
     */
    fun deleteKey() {
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Phase 1 option: setUserAuthenticationRequired(true) would bind
            // every decrypt to a fresh biometric/PIN prompt at the OS level.
            // Deliberately left off for the MVP — the app-level PIN gate plus
            // an in-memory "unlocked" session is the planned model.
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "photolock_vault_key_v1"

        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val BUFFER_SIZE = 64 * 1024
    }
}
