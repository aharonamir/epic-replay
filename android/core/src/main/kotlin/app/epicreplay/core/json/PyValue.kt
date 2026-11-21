package app.epicreplay.core.json

import app.epicreplay.core.checks.Checks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How Python reads a JSON value where the stage code leans on it: `if x`, `x or y` and `str(x)`. */
object PyValue {
    fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toBigDecimalOrNull()?.signum() != 0
    }

    /** `str(x)` for a string, a number, a boolean or None (a list or object is not written the way Python does). */
    fun str(e: JsonElement?): String = when (e) {
        null, is JsonNull -> "None"
        is JsonPrimitive -> if (e.isString) e.content else if (e.content == "true") "True" else if (e.content == "false") "False" else Checks.pyNumber(e.content)
        else -> e.toString()
    }

    /** `str(x or "")`. */
    fun strOrEmpty(e: JsonElement?): String = if (truthy(e)) str(e) else ""
}
