package app.epicreplay.core.record

import app.epicreplay.core.json.PythonJson
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** `live`: always call. `record`: use a recording when there is one, else call and store the answer. `replay`: never call. */
enum class ProviderMode { LIVE, RECORD, REPLAY }

class ReplayMiss(message: String) : Exception(message)

/**
 * The key a recording is stored under (`shared/contracts/model-backend.md`, "Recording key"): the SHA-256 of the
 * canonical JSON of the adapter, the model, the request and the hashes of the files sent. Written on either platform,
 * a recording is read by the other.
 */
fun cacheKey(adapter: String, model: String, request: JsonElement, fileHashes: List<String> = emptyList()): String =
    PythonJson.sha256Hex(
        PythonJson.canonical(
            buildJsonObject {
                put("adapter", JsonPrimitive(adapter))
                put("model", JsonPrimitive(model))
                put("request", request)
                put("files", JsonArray(fileHashes.map { JsonPrimitive(it) }))
            },
        ),
    )

/** One recording per request, as `<dir>/<adapter>/<key>.json`; every reply is stored, including a misfit, so a replay repeats what happened. */
class Recorder(val mode: ProviderMode, private val dir: File) {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun path(adapter: String, key: String): File = File(File(dir, adapter), "$key.json")

    /** Whether [fetch] would answer from a recording, at no cost. */
    fun has(adapter: String, key: String): Boolean = mode != ProviderMode.LIVE && path(adapter, key).isFile

    /** The payload and whether it came from a recording. [live] makes the real call and returns a JSON object. */
    suspend fun fetch(adapter: String, key: String, live: suspend () -> JsonObject): Pair<JsonObject, Boolean> {
        val p = path(adapter, key)
        if (has(adapter, key)) return Json.parseToJsonElement(p.readText()).jsonObject to true
        if (mode == ProviderMode.REPLAY) {
            throw ReplayMiss("no recording for $adapter ${key.take(12)}; re-run once in record mode")
        }
        val payload = live()
        if (mode == ProviderMode.RECORD) {
            try {
                p.parentFile?.mkdirs()
                val tmp = File(p.path + ".tmp")
                tmp.writeText(pretty.encodeToString(JsonObject.serializer(), payload))
                if (!tmp.renameTo(p)) { tmp.delete(); throw IOException("rename failed") }
            } catch (e: IOException) {
                throw IllegalStateException("a recording could not be written")
            }
        }
        return payload to false
    }
}
