package app.epicreplay.core.secrets

import java.io.File
import java.io.IOException

/**
 * The user's API keys on disk (spec 004 FR-008): one file per key in [dir], holding a format byte
 * and the cipher's blob, nothing else. The key is never written in the clear, never in a message of
 * an exception thrown here, and the file is excluded from backups by the app's rules. A key that can
 * no longer be decrypted (a restored device, a deleted Keystore key) reads as [KeyStatus.UNREADABLE]
 * and is entered again, not guessed at.
 */
class SecretStore(private val dir: File, private val cipher: SecretCipher) {
    private fun file(kind: KeyKind) = File(dir, kind.id + ".bin")

    /** Stores [key] for [kind], replacing any earlier one. The write is atomic: a crash leaves the old key or the new. */
    fun put(kind: KeyKind, key: Secret) {
        val text = key.reveal()
        if (text.isBlank()) throw SecretStoreException("the key is empty")
        val blob = try {
            cipher.encrypt(text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SecretStoreException("the key could not be encrypted (${e.javaClass.simpleName})")
        }
        try {
            dir.mkdirs()
            val tmp = File(dir, kind.id + ".tmp")
            tmp.writeBytes(byteArrayOf(FORMAT) + blob)
            if (!tmp.renameTo(file(kind))) {
                tmp.delete()
                throw IOException("rename failed")
            }
        } catch (e: IOException) {
            throw SecretStoreException("the key could not be written")
        }
    }

    fun status(kind: KeyKind): KeyStatus = when {
        !file(kind).isFile -> KeyStatus.ABSENT
        read(kind) != null -> KeyStatus.PRESENT
        else -> KeyStatus.UNREADABLE
    }

    /** The key, or null when there is none or it cannot be decrypted (see [status]). */
    fun get(kind: KeyKind): Secret? = read(kind)

    fun delete(kind: KeyKind) {
        File(dir, kind.id + ".tmp").delete()
        val f = file(kind)
        if (f.exists() && !f.delete()) throw SecretStoreException("the key file could not be deleted")
    }

    private fun read(kind: KeyKind): Secret? {
        val f = file(kind)
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            if (bytes.isEmpty() || bytes[0] != FORMAT) return null
            Secret(String(cipher.decrypt(bytes.copyOfRange(1, bytes.size)), Charsets.UTF_8))
        } catch (e: Exception) {
            null // wrong key, damaged file, short file: unreadable, and nothing about it is reported
        }
    }

    companion object {
        const val FORMAT: Byte = 1
    }
}
