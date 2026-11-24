package app.epicreplay.core.models

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A cloud server of the user's own (spec 004 FR-CUSTOM-1): an OpenAI-compatible chat server over HTTPS. */
data class CustomServer(val label: String, val apiBase: String, val model: String, val priceInPerM: Double, val priceOutPerM: Double, val supportsTools: Boolean)

/**
 * The profile of [CustomServer]: checked as typed, and written as a profile file of `model_profile.schema.json`, so it is loaded, validated and offered like the shared ones.
 * The call ceiling is worked out from the prices as `deepseek-flash`'s is (about 20,000 tokens in and 1,500 out for a look); a server whose prices are unknown has a ceiling of 0, which the caps cannot count.
 */
object CustomServers {
    const val ID = "custom-cloud"
    const val FILE = "$ID.json"
    private val MODEL = Regex("""[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,99}""")

    /** Why the typed values cannot be a server, or null. Prices are text: empty is 0. */
    fun problem(label: String, apiBase: String, model: String, priceIn: String, priceOut: String): String? {
        val base = apiBase.trim()
        if (!base.startsWith("https://")) return "The address must start with https:// (a plain http address is not used)."
        val host = base.removePrefix("https://").substringBefore('/')
        if (host.isEmpty() || base.any { it.isWhitespace() || it.isISOControl() || it.code in 0x1C..0x1F || it.code == 0x85 || it.code == 0xA0 } || '?' in base || '#' in base) return "That is not a server address (no spaces, query or #; for example https://api.example.com/v1)."
        if (!MODEL.matches(model.trim())) return "The model name is one word, as the server names it (letters, digits and . _ : / @ + -)."
        if (label.trim().length > 40 || label.any { it.isISOControl() }) return "The name is at most 40 characters, on one line."
        for (p in listOf(priceIn, priceOut)) if (price(p) == null) return "A price is a number of dollars per million tokens (0 to 10,000), or empty if unknown."
        return null
    }

    /** Dollars per million tokens: a number from 0 to 10,000 (more is not a price, and would overflow the ceiling worked out from it). */
    private fun price(text: String): Double? = text.trim().ifEmpty { "0" }.toDoubleOrNull()?.takeIf { it in 0.0..10_000.0 }

    /** The server for typed values, or null when [problem] says they are wrong. */
    fun of(label: String, apiBase: String, model: String, priceIn: String, priceOut: String, supportsTools: Boolean): CustomServer? =
        if (problem(label, apiBase, model, priceIn, priceOut) != null) null
        else CustomServer(label.trim().ifEmpty { "My server" }, apiBase.trim().trimEnd('/'), model.trim(), price(priceIn)!!, price(priceOut)!!, supportsTools)

    private fun round6(x: Double) = BigDecimal(x).setScale(6, RoundingMode.HALF_UP).toDouble()

    fun profileJson(c: CustomServer): JsonObject {
        val ceiling = round6((20_000 * c.priceInPerM + 1_500 * c.priceOutPerM) / 1e6)
        return buildJsonObject {
            put("id", ID); put("label", c.label); put("kind", "openai_compatible"); put("location", "cloud")
            put("roles", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("vision"), JsonPrimitive("text"))))
            put("api_base", c.apiBase); put("model", c.model); put("api_key_env", "CUSTOM_API_KEY")
            put("price_in_per_m", c.priceInPerM); put("price_out_per_m", c.priceOutPerM)
            put("call_ceiling_usd", ceiling); put("call_mean_usd", round6(ceiling / 2))
            put("timeout_s", buildJsonObject { put("vision", 90); put("text", 90) }); put("max_parallel", 4); put("json_mode", "json_object")
            put("image", buildJsonObject { put("max_side_px", JsonNull); put("detail", JsonNull) })
            put("supports_tools", c.supportsTools); put("extra_body", JsonObject(emptyMap())); put("status", "experimental")
            put("serve", "A server entered by the user on this phone."); put("notes", "Entered by the user; its prices are theirs.")
        }
    }

    fun fromProfileJson(o: JsonObject) = CustomServer(
        o.getValue("label").jsonPrimitive.content, o.getValue("api_base").jsonPrimitive.content, o.getValue("model").jsonPrimitive.content,
        o.getValue("price_in_per_m").jsonPrimitive.double, o.getValue("price_out_per_m").jsonPrimitive.double, o.getValue("supports_tools").jsonPrimitive.content == "true",
    )
}

/** The user's server kept as a profile file in the app's private settings. */
class CustomServerStore(private val file: File) {
    fun load(): CustomServer? = try { CustomServers.fromProfileJson(Json.parseToJsonElement(file.readText()).jsonObject) } catch (e: Exception) { null }

    /** The profile file as the profile loader reads it, or null when there is none. */
    fun profileFile(): ProfileFile? = file.takeIf { it.isFile }?.let { ProfileFile(CustomServers.FILE, it.readText()) }

    fun save(c: CustomServer) { file.parentFile?.mkdirs(); file.writeText(CustomServers.profileJson(c).toString()) }
    fun delete() { file.delete() }
}
