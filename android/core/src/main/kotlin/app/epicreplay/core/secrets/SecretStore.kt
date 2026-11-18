package app.epicreplay.core.secrets

import app.epicreplay.core.io.Durable
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The user's API keys on disk (spec 004 FR-008): one file per key in [dir], holding a format byte
 * and the cipher's blob, nothing else. The key is never written in the clear, never in a message of
 * an exception thrown here, and the file is excluded from backups by the app's rules. A key that can
 * no longer be decrypted (a restored device, a deleted Keystore key) reads as [KeyStatus.UNREADABLE]
 * and is entered again, not guessed at. Each blob is bound to its key kind and the format byte as GCM
 * associated data, so swapping two key files makes both unreadable instead of exchanging the keys.
 * Operations are serialised by a lock, so a replace and a delete cannot trample each other's temporary file.
 */
class SecretStore(private val dir: File, private val cipher: SecretCipher) {
    private val lock = ReentrantLock()
    private fun file(kind: KeyKind) = File(dir, kind.id + ".bin")
    private fun aad(kind: KeyKind) = "epic-replay/secret/v$FORMAT/${kind.id}".toByteArray(Charsets.UTF_8)

    /** Stores [key] for [kind], replacing any earlier one. The write is atomic: a crash leaves the old key or the new. */
    fun put(kind: KeyKind, key: Secret) = lock.withLock {
        val text = key.reveal()
        if (text.isBlank()) throw SecretStoreException("the key is empty")
        val blob = try {
            cipher.encrypt(text.toByteArray(Charsets.UTF_8), aad(kind))
        } catch (e: Exception) {
            throw SecretStoreException("the key could not be encrypted (${e.javaClass.simpleName})")
        }
        try {
            Durable.writeAtomic(file(kind), byteArrayOf(FORMAT) + blob)
        } catch (e: IOException) {
            throw SecretStoreException("the key could not be written")
        }
    }

    fun status(kind: KeyKind): KeyStatus = lock.withLock { statusLocked(kind) }

    private fun statusLocked(kind: KeyKind): KeyStatus = when {
        !file(kind).isFile -> KeyStatus.ABSENT
        read(kind) != null -> KeyStatus.PRESENT
        else -> KeyStatus.UNREADABLE
    }

    /** The key, or null when there is none or it cannot be decrypted (see [status]). */
    fun get(kind: KeyKind): Secret? = lock.withLock { read(kind) }

    fun delete(kind: KeyKind) = lock.withLock {
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
            Secret(String(cipher.decrypt(bytes.copyOfRange(1, bytes.size), aad(kind)), Charsets.UTF_8))
        } catch (e: Exception) {
            null // wrong key, damaged file, short file: unreadable, and nothing about it is reported
        }
    }

    companion object {
        const val FORMAT: Byte = 1
    }
}
