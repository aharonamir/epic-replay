package app.epicreplay.core.secrets

import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts a small blob. The Android implementation uses a key that never leaves the Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM through the Java cryptography API, with the key from [keyProvider]. On the phone the
 * provider returns a Keystore key (its material cannot be read, only used); in the JVM tests it
 * returns an in-memory key, so the same code is tested. The blob is `iv (12 bytes) + ciphertext + tag`,
 * with a fresh random IV every time (the cipher makes it).
 */
class AesGcmCipher(private val keyProvider: () -> Key) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = c.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES + TAG_BYTES) { "blob too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, blob, 0, IV_BYTES))
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
