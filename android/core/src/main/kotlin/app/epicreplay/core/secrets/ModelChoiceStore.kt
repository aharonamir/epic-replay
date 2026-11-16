package app.epicreplay.core.secrets

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The two roles a model profile serves (`shared/contracts/model_profile.schema.json`). */
enum class Role(val id: String) { VISION("vision"), TEXT("text") }

/**
 * The profile chosen for each role (spec 004 FR-005): a small JSON file `{"vision": id|null, "text": id|null}`.
 * No choice is the default (D3): a role with no profile asks the user before a run. This file holds
 * profile ids only, never a key. The loader that decides which ids are offered is T407's.
 */
class ModelChoiceStore(private val file: File) {
    fun get(role: Role): String? {
        if (!file.isFile) return null
        return try {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            (obj[role.id] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ID.matches(it) }
        } catch (e: Exception) {
            null // a damaged file is no choice
        }
    }

    /** Sets the profile id for [role], or clears the choice with null. The write is atomic. */
    fun set(role: Role, profileId: String?) {
        require(profileId == null || ID.matches(profileId)) { "not a profile id" }
        val now = Role.entries.associate { it.id to (if (it == role) profileId else get(it)) }
        val json = JsonObject(now.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) })
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("rename failed")
            }
        } catch (e: IOException) {
            throw SecretStoreException("the choice could not be written")
        }
    }

    companion object {
        val ID = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}
