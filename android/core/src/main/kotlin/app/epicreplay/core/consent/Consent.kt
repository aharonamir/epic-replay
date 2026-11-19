package app.epicreplay.core.consent

import app.epicreplay.core.io.Durable
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** How far the frames or the text go under each place: a consent covers a run that goes no further (spec 003 FR-015, spec 004 T404b). */
object Places {
    val REACH = mapOf("device" to 0, "local" to 0, "network" to 1, "cloud" to 2)
}

/**
 * The consent words, read from `shared/copy/consent.json`, the same file the PC app reads (spec 004 FR-015).
 * [blocks] and [covers] are ports of `guards/consent.py`; the conformance area `consent` runs both on the same cases.
 */
class ConsentCopy(json: String) {
    private val words = Json.parseToJsonElement(json).jsonObject
    val textVersion: String = words.str("text_version")
    val consentText: String = words.str("consent")
    private fun group(name: String) = words.getValue(name).jsonObject

    /**
     * What the consent screen shows for the two roles: one sentence naming both when the frames and the text go to the
     * same place, else one block for the frames and one for the text. The frames' place is never shown without the text's.
     */
    fun blocks(visionLocation: String, visionLabel: String, textLocation: String, textLabel: String): List<String> =
        if (visionLocation == textLocation) {
            listOf(group("both_lines").str(visionLocation).replace("{vision}", visionLabel).replace("{text}", textLabel))
        } else {
            listOf(
                group("lines").str(visionLocation).replace("{label}", visionLabel),
                group("text_lines").str(textLocation).replace("{label}", textLabel),
            )
        }

    /** Whether [record] covers a run whose frames go to [visionLocation] and whose text goes to [textLocation]. */
    fun covers(record: JsonObject, visionLocation: String, textLocation: String): Boolean {
        if ((record["confirmed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true) return false
        if ((record["text_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content != textVersion) return false
        for ((role, now) in listOf("vision" to visionLocation, "text" to textLocation)) {
            val named = record["${role}_location"]
            // a place not named counts as the cloud; one that is not one of the four covers none
            val agreed = if (named == null) Places.REACH.getValue("cloud")
            else (named as? JsonPrimitive)?.takeIf { it.isString }?.let { Places.REACH[it.content] } ?: -1
            if (Places.REACH.getValue(now) > agreed) return false
        }
        return true
    }

    private fun JsonObject.str(key: String): String = (getValue(key) as JsonPrimitive).content
}

/** The consent the user gave, as the PC writes it into a run's `user_context.json`. */
data class ConsentRecord(val at: String, val textVersion: String, val visionLocation: String, val textLocation: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("confirmed", JsonPrimitive(true))
        put("at", JsonPrimitive(at))
        put("text_version", JsonPrimitive(textVersion))
        put("vision_location", JsonPrimitive(visionLocation))
        put("text_location", JsonPrimitive(textLocation))
    }
}

/** The stored consent (one small JSON file in private storage, not backed up). A damaged file is no consent. */
class ConsentStore(private val file: File) {
    fun load(): JsonObject? = try {
        if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else null
    } catch (e: Exception) {
        null
    }

    fun save(record: ConsentRecord) {
        try {
            Durable.writeAtomic(file, record.toJson().toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw IllegalStateException("the consent could not be saved")
        }
    }

    fun clear() { file.delete() }
}
