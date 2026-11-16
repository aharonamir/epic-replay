package app.epicreplay.core.models

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a platform can run: a valid profile of another kind or location is left out, not refused (spec 004 FR-002). */
data class Platform(val name: String, val kinds: Set<String>, val locations: Set<String>) {
    companion object {
        /** The PC app: servers in the cloud, on this computer or on the owner's network. */
        val PC = Platform("pc", setOf(ServerProfile.KIND), setOf("cloud", "local", "network"))

        /** The phone in v1: cloud servers and models on the device; no home server (D1). */
        val ANDROID = Platform("android", setOf(ServerProfile.KIND, OnDeviceProfile.KIND), setOf("cloud", "device"))
    }
}

/** A profile file that did not make the list, and why. [leftOut] is true for a valid profile this platform cannot run. */
data class ProfileProblem(val file: String, val leftOut: Boolean, val reason: String)

class LoadedProfiles(val profiles: Map<String, Profile>, val problems: List<ProfileProblem>) {
    /** The profiles that list [role], in file-name order: what the user may choose from. */
    fun offeredFor(role: Role): List<Profile> = profiles.values.filter { role in it.roles }
    fun refused(): List<ProfileProblem> = problems.filter { !it.leftOut }
    fun leftOut(): List<ProfileProblem> = problems.filter { it.leftOut }
}

/** The text of one profile file and its name (`<id>.json`). */
class ProfileFile(val name: String, val text: String)

/**
 * Loads `shared/models/` the way `pc/src/epic_replay/providers/models.py` does: the schema
 * (`shared/contracts/model_profile.schema.json`), then the loader's own rules, then the platform
 * filter, then one model name per profile. A refused or left-out file never stops the others.
 */
class ProfileLoader(schemaText: String, private val platform: Platform) {
    private val schema = JsonSchema.fromDefinition(schemaText)

    fun load(files: List<ProfileFile>): LoadedProfiles {
        val out = linkedMapOf<String, Profile>()
        val owner = HashMap<String, String>()
        val problems = mutableListOf<ProfileProblem>()
        for (file in files.sortedBy { it.name }) {
            val data = try {
                Json.parseToJsonElement(file.text)
            } catch (e: Exception) {
                problems += ProfileProblem(file.name, false, "not JSON (${(e.message ?: e.javaClass.simpleName).take(120)})")
                continue
            }
            var errs = problems(data, file.name.removeSuffix(".json"))
            if (errs.isEmpty()) {
                val obj = data.jsonObject
                val kind = obj.str("kind")
                val location = obj.str("location")
                if (kind !in platform.kinds || location !in platform.locations) {
                    problems += ProfileProblem(file.name, true, "kind $kind at location $location cannot run on ${platform.name}")
                    continue
                }
                val model = obj["model"]?.jsonPrimitive?.content
                if (model != null && model in owner) {
                    errs = listOf("model $model is also the model of profile ${owner[model]}")
                }
            }
            if (errs.isNotEmpty()) {
                problems += ProfileProblem(file.name, false, errs.joinToString("; "))
                continue
            }
            val profile = try {
                build(data.jsonObject)
            } catch (e: Exception) {
                // a value the schema allows but this platform cannot hold (a number beyond Int, say): refused, never a crash
                problems += ProfileProblem(file.name, false, "a field is out of range (${e.javaClass.simpleName})")
                continue
            }
            if (profile is ServerProfile) owner[profile.model] = profile.id
            out[profile.id] = profile
        }
        return LoadedProfiles(out, problems)
    }

    /** Why a parsed profile file is not usable, or an empty list. [stem] is its file name without `.json`. */
    fun problems(data: JsonElement, stem: String): List<String> {
        val errors = mutableListOf<ValidationError>()
        schema.validate(data, errors::add)
        if (errors.isNotEmpty() || data !is JsonObject) {
            return errors.map { describe(it) }.sorted().ifEmpty { listOf("not an object") }
        }
        val out = mutableListOf<String>()
        // The schema's `[^\s]` is ASCII-only in Kotlin's regex and Unicode in Python's: check the URL fields the same way as Python.
        listOfNotNull(data["api_base"], *((data["files"] as? JsonArray)?.map { (it as? JsonObject)?.get("url") }?.toTypedArray() ?: emptyArray()))
            .filterIsInstance<JsonPrimitive>()
            .filter { it.isString && it.content.any { c -> c.isWhitespace() || Character.isSpaceChar(c) } }
            .forEach { out += "a URL contains whitespace" }
        if (data.str("id") != stem) out += "id ${data.str("id")} is not the file name $stem"
        if (data.str("kind") != ServerProfile.KIND) return out
        if (data.getValue("call_ceiling_usd").jsonPrimitive.double < data.getValue("call_mean_usd").jsonPrimitive.double) {
            out += "call_ceiling_usd is below call_mean_usd"
        }
        val timeouts = data.getValue("timeout_s").jsonObject.keys
        val roles = data.getValue("roles").jsonArray.map { it.jsonPrimitive.content }.toSet()
        if (timeouts != roles) out += "timeout_s must have exactly the roles ${roles.sorted()}"
        return out
    }

    private fun describe(e: ValidationError): String =
        "${e.objectPath.toString().ifEmpty { "profile" }}: ${e.message.take(160)}"

    private fun build(o: JsonObject): Profile {
        val roles = o.getValue("roles").jsonArray.map { Role.of(it.jsonPrimitive.content)!! }
        return if (o.str("kind") == OnDeviceProfile.KIND) {
            OnDeviceProfile(
                id = o.str("id"), label = o.str("label"), roles = roles,
                supportsTools = o.getValue("supports_tools").jsonPrimitive.boolean,
                status = o.str("status"), notes = o.str("notes"),
                engine = o.str("engine"),
                files = o.getValue("files").jsonArray.map {
                    val f = it.jsonObject
                    ModelFile(f.str("name"), f.str("url"), f.str("sha256"), f.getValue("bytes").toLongExact(), f.str("role"))
                },
                contextTokens = o.getValue("context_tokens").toIntExact(),
                minFreeRamMb = o.getValue("min_free_ram_mb").toIntExact(),
                minFreeStorageMb = o.getValue("min_free_storage_mb").toIntExact(),
                license = o.str("license"),
            )
        } else {
            val image = o.getValue("image").jsonObject
            ServerProfile(
                id = o.str("id"), label = o.str("label"), location = o.str("location"), roles = roles,
                supportsTools = o.getValue("supports_tools").jsonPrimitive.boolean,
                status = o.str("status"), notes = o.str("notes"),
                priceInPerM = o.getValue("price_in_per_m").jsonPrimitive.double,
                priceOutPerM = o.getValue("price_out_per_m").jsonPrimitive.double,
                apiBase = o.str("api_base"), model = o.str("model"),
                apiKeyEnv = (o.getValue("api_key_env") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                callCeilingUsd = o.getValue("call_ceiling_usd").jsonPrimitive.double,
                callMeanUsd = o.getValue("call_mean_usd").jsonPrimitive.double,
                timeoutS = o.getValue("timeout_s").jsonObject.entries.associate { (k, v) -> Role.of(k)!! to v.jsonPrimitive.double },
                maxParallel = o.getValue("max_parallel").toIntExact(),
                jsonMode = o.str("json_mode"),
                image = ImageSpec(
                    maxSidePx = (image.getValue("max_side_px") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.toIntExact(),
                    detail = (image.getValue("detail") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                ),
                extraBody = o.getValue("extra_body").jsonObject,
                serve = o.str("serve"),
            )
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    /** JSON Schema counts 4096.0 as an integer, as Python does. Read from the number's own text, so a large exact integer is never rounded through a Double. */
    private fun JsonElement.toIntExact(): Int = wholeNumber().intValueExact()

    private fun JsonElement.toLongExact(): Long = wholeNumber().longValueExact()

    private fun JsonElement.wholeNumber(): BigDecimal {
        val d = BigDecimal(jsonPrimitive.content)
        check(d.stripTrailingZeros().scale() <= 0) { "not a whole number" }
        return d
    }

    companion object {
        /** Loads every `*.json` in [dir] with the schema file [schema]. A missing folder or schema is named, not skipped. */
        fun loadDir(dir: File, schema: File, platform: Platform): LoadedProfiles {
            require(dir.isDirectory) { "model profiles folder not found: $dir" }
            require(schema.isFile) { "model profile schema not found: $schema" }
            val files = dir.listFiles { f -> f.isFile && f.extension == "json" }!!.map { ProfileFile(it.name, it.readText()) }
            return ProfileLoader(schema.readText(), platform).load(files)
        }
    }
}
