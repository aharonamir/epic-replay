package app.epicreplay.core.style

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NoStyles(message: String) : Exception(message)

/** The text of one pack file and its name. */
class PackFile(val name: String, val text: String)

class StylePacks(val packs: Map<String, JsonObject>, val problems: List<String>) {
    fun default(): JsonObject = packs.values.first { (it["default"] as? JsonPrimitive)?.booleanOrNull == true }

    /** The pack as `language` sees it. English is the pack itself. */
    fun resolved(id: String, language: String?): JsonObject {
        val pack = packs[id] ?: throw NoStyles("style $id is not a usable pack")
        if (language == null || language == "en") return pack
        val block = (pack["languages"] as? JsonObject)?.get(language) as? JsonObject ?: throw NoStyles("style $id does not speak $language")
        return JsonObject(pack.filterKeys { it != "languages" } + mapOf("language" to JsonPrimitive(language), "script" to block.getValue("script"), "voice" to block.getValue("voice")))
    }
}

/**
 * Loads the style packs the way `pc/src/epic_replay/styles/loader.py` does (FR-040): each pack against
 * `style_pack.schema.json`, its voices against the registry (`voices.json`), its assets, and each language block alone;
 * a refused pack is left out with the reason, and exactly one pack must be the default. [assetExists] answers for a path
 * under `assets/`; [skinExists] for a graphics skin (null: not checked, the phone draws its own).
 */
class StyleLoader(schemaText: String, private val voices: JsonObject, private val assetExists: (String) -> Boolean, private val skinExists: ((String) -> Boolean)? = null) {
    private val schema = JsonSchema.fromDefinition(schemaText)

    private fun validate(data: JsonElement): List<String> {
        val errs = mutableListOf<ValidationError>()
        schema.validate(data, errs::add)
        return errs.map { "${it.objectPath.toString().ifEmpty { "pack" }}: ${it.message.take(160)}" }.sorted()
    }

    private fun voiceProblems(voice: JsonObject): List<String> = listOf("primary", "fallback").mapNotNull { which ->
        val ref = voice.getValue(which).jsonObject
        val provider = ref.getValue("provider").jsonPrimitive.content
        val id = ref.getValue("voice_id").jsonPrimitive.content
        val known = if (provider.startsWith("_")) null else voices[provider] as? JsonObject
        if (known == null || id !in known) "voice/$which: $provider voice $id is not known" else null
    }

    private fun problems(pack: JsonObject): List<String> {
        var errs = validate(pack)
        if (errs.isNotEmpty()) return errs
        errs = voiceProblems(pack.getValue("voice").jsonObject).toMutableList()
        val skin = pack.getValue("graphics").jsonObject.getValue("skin").jsonPrimitive.content
        if (skinExists != null && !skinExists.invoke(skin)) errs = errs + "graphics/skin: no skin named $skin"
        val sound = pack.getValue("sound").jsonObject
        val named = listOf(pack.getValue("sample_audio").jsonPrimitive.content) +
            SOUNDS.mapNotNull { k -> (sound[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotEmpty() }?.content }
        for (rel in named) if (!assetExists(rel)) errs = errs + "asset $rel is missing under assets/"
        return errs
    }

    private fun usableLanguages(pack: JsonObject, blocks: JsonElement, name: String, log: MutableList<String>): JsonObject {
        val kept = linkedMapOf<String, JsonElement>()
        val entries = if (blocks is JsonObject) blocks.entries.map { it.key to it.value } else listOf("languages" to blocks)
        for ((lang, block) in entries) {
            var errs = validate(JsonObject(pack + mapOf("languages" to JsonObject(mapOf(lang to block)))))
            if (errs.isEmpty()) errs = voiceProblems(block.jsonObject.getValue("voice").jsonObject)
            if (errs.isNotEmpty()) { log += "language $lang of style pack $name refused: ${errs.joinToString("; ")}"; continue }
            kept[lang] = block
        }
        return JsonObject(kept)
    }

    fun load(files: List<PackFile>): StylePacks {
        val packs = linkedMapOf<String, JsonObject>()
        val log = mutableListOf<String>()
        for (file in files.sortedBy { it.name }) {
            val parsed = try { Json.parseToJsonElement(file.text) } catch (e: Exception) { log += "style pack ${file.name} refused: not JSON"; continue }
            val blocks = (parsed as? JsonObject)?.get("languages")
            val pack = if (parsed is JsonObject) JsonObject(parsed.filterKeys { it != "languages" }) else null
            var errs = if (pack == null) listOf("not an object") else problems(pack)
            if (errs.isEmpty() && pack!!.getValue("id").jsonPrimitive.content in packs) errs = listOf("id ${pack.getValue("id").jsonPrimitive.content} is used by another pack")
            if (errs.isNotEmpty()) { log += "style pack ${file.name} refused: ${errs.joinToString("; ")}"; continue }
            val kept = if (blocks != null) usableLanguages(pack!!, blocks, file.name, log) else JsonObject(emptyMap())
            packs[pack!!.getValue("id").jsonPrimitive.content] = if (kept.isNotEmpty()) JsonObject(pack + mapOf("languages" to kept)) else pack
        }
        if (packs.isEmpty()) throw NoStyles("no usable style pack")
        val defaults = packs.values.filter { (it["default"] as? JsonPrimitive)?.booleanOrNull == true }.map { it.getValue("id").jsonPrimitive.content }
        if (defaults.size != 1) throw NoStyles("exactly one pack must be the default; found ${defaults.ifEmpty { listOf("none") }}")
        val sorted = packs.entries.sortedWith(compareBy({ (it.value.getValue("order").jsonPrimitive.content.toDouble()) }, { it.key }))
        return StylePacks(sorted.associate { it.key to it.value }, log)
    }

    companion object {
        val SOUNDS = listOf("bed", "climax", "replay_climax", "transition", "sting_in", "sting_out")

        /** The packs in `shared/styles/` (every .json but `voices.json`), the schema in `shared/contracts/` and the assets in `shared/assets/`. */
        fun loadShared(shared: File, skinExists: ((String) -> Boolean)? = null): StylePacks {
            val styles = File(shared, "styles")
            require(styles.isDirectory) { "styles folder not found: $styles" }
            val voices = Json.parseToJsonElement(File(styles, "voices.json").readText()).jsonObject
            val loader = StyleLoader(File(shared, "contracts/style_pack.schema.json").readText(), voices, { File(shared, "assets/$it").isFile }, skinExists)
            val files = styles.listFiles { f -> f.isFile && f.extension == "json" && f.name != "voices.json" }!!.map { PackFile(it.name, it.readText()) }
            return loader.load(files)
        }
    }
}
