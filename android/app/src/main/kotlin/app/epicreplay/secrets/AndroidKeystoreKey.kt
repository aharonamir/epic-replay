package app.epicreplay.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.Key
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * The AES-256 key that wraps the user's API keys (spec 004 FR-008), in the Android Keystore: the
 * key's material never leaves it, the app can only ask it to encrypt and decrypt. Made on first use.
 * No authentication is required to use it (the app is the user's own and has no lock screen of its own).
 */
object AndroidKeystoreKey {
    private const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "epic_replay_secrets_v1"

    @Synchronized
    fun get(): Key {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null))?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    /** Deletes the Keystore key: every stored key becomes unreadable (used by tests). */
    @Synchronized
    fun delete() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
    }
}
