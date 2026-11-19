package app.epicreplay.core.setup

import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Which model each role ran on, for a run's records (spec 004 FR-017): the same shape as the PC's `models_used`. */
class ModelsUsed(val vision: Profile, val text: Profile) {
    fun toJson(): JsonObject = buildJsonObject {
        put("vision", entry(vision))
        put("text", entry(text))
    }

    private fun entry(p: Profile): JsonObject = buildJsonObject {
        put("profile", JsonPrimitive(p.id))
        put("label", JsonPrimitive(p.label))
        put("location", JsonPrimitive(p.location))
        // the model name sent for a server; the weights file for a model on the phone
        put("model", JsonPrimitive(when (p) { is ServerProfile -> p.model; is OnDeviceProfile -> p.files.first { it.role == "weights" }.name }))
    }

    fun of(role: Role): Profile = if (role == Role.VISION) vision else text
}
