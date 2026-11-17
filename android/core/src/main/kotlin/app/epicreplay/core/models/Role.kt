package app.epicreplay.core.models

/** The two roles a model profile serves (`shared/contracts/model_profile.schema.json`). */
enum class Role(val id: String) {
    VISION("vision"),
    TEXT("text"),
    ;

    companion object {
        fun of(id: String): Role? = entries.firstOrNull { it.id == id }
    }
}
