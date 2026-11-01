package com.jarvis.assistant.security.cipher

/**
 * Interface for encrypting/decrypting sensitive credentials and API keys at rest.
 */
interface KeyCipher {
    /** Encrypts [plaintext] returning base64 encoded ciphertext + IV. */
    fun encrypt(plaintext: String): String

    /** Decrypts a value produced by [encrypt]. */
    fun decrypt(ciphertext: String): String

    /** Removes the underlying key material. */
    fun delete()
}
