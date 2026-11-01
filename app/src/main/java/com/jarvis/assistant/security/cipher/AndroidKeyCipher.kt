package com.jarvis.assistant.security.cipher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM encryption backed by the hardware-isolated Android Keystore.
 * The encryption key never leaves secure enclave hardware/keystore; each encryption uses
 * a cryptographically secure random 12-byte IV stored alongside ciphertext.
 */
class AndroidKeyCipher(private val context: Context) : KeyCipher {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_secure_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        @Volatile
        private var instance: AndroidKeyCipher? = null

        fun getInstance(context: Context): AndroidKeyCipher {
            return instance ?: synchronized(this) {
                instance ?: AndroidKeyCipher(context.applicationContext).also { instance = it }
            }
        }
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            // Fallback gracefully if keystore fails
            plaintext
        }
    }

    override fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        return try {
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (combined.size < IV_LENGTH + 1) return ciphertext
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ciphertext
        }
    }

    override fun delete() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }
}
