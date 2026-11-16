package app.epicreplay.core.secrets

/**
 * An API key in memory. It prints as `Secret(****)` so it cannot reach a log line, an exception
 * message or a screen by accident (spec 004 FR-008); the text is available only through [reveal],
 * which the model clients call when they build a request.
 */
class Secret(private val value: String) {
    fun reveal(): String = value

    /** The key shown on a screen: dots and, for a long key, its last four characters; never more. */
    fun masked(): String =
        if (value.length >= MIN_LENGTH_FOR_TAIL) "••••••••" + value.takeLast(TAIL) else "••••••••"

    override fun toString(): String = "Secret(****)"
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val TAIL = 4
        const val MIN_LENGTH_FOR_TAIL = 16
    }
}

/** The keys the user types in settings (spec 004 FR-008). `id` is the file name in the store. */
enum class KeyKind(val id: String, val label: String) {
    MODEL_PROVIDER("model_provider", "Model provider key"),
    ELEVENLABS("elevenlabs", "ElevenLabs key"),
}

/** What the store knows about one key. */
enum class KeyStatus { ABSENT, PRESENT, UNREADABLE }

/** A failure of the store. The message never contains a key or the file's content. */
class SecretStoreException(message: String) : Exception(message)
