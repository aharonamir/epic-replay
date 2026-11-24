package app.epicreplay.core.secrets

/** One row of the keys screen. [masked] is what may be shown; the key itself never is. */
data class KeyRow(val kind: KeyKind, val status: KeyStatus, val masked: String?)

/**
 * The logic of the keys screen, without any UI: show each key masked, replace one, delete one.
 * Text typed by the user is trimmed and checked before it reaches the store; a rejected key is
 * reported by reason, never by echoing it.
 */
class KeysController(private val store: SecretStore) {
    fun rows(): List<KeyRow> = KeyKind.entries.map { kind ->
        when (store.status(kind)) {
            KeyStatus.ABSENT -> KeyRow(kind, KeyStatus.ABSENT, null)
            KeyStatus.UNREADABLE -> KeyRow(kind, KeyStatus.UNREADABLE, null)
            KeyStatus.PRESENT -> KeyRow(kind, KeyStatus.PRESENT, store.get(kind)?.masked())
        }
    }

    /** Returns null when the key was stored, else the reason it was refused. */
    fun save(kind: KeyKind, typed: String): String? {
        val key = typed.trim()
        return problem(typed) ?: try {
            store.put(kind, Secret(key))
            null
        } catch (e: SecretStoreException) {
            e.message
        }
    }

    /** Why [typed] cannot be a key, or null; nothing is stored. */
    fun problem(typed: String): String? {
        val key = typed.trim()
        return when {
            key.isEmpty() -> "Type the key first."
            key.length < MIN_LENGTH -> "That is too short to be a key."
            key.length > MAX_LENGTH -> "That is too long to be a key."
            key.any { it.isWhitespace() || it.isISOControl() } -> "A key has no spaces or line breaks."
            else -> null
        }
    }

    fun delete(kind: KeyKind): String? = try {
        store.delete(kind)
        null
    } catch (e: SecretStoreException) {
        e.message
    }

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 512
    }
}
