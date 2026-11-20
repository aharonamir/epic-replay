package app.epicreplay.core.models

import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.secrets.Secret
import java.util.Base64
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Scales a JPEG down so its longest side is at most [maxSidePx]; the same input always gives the same bytes. */
interface ImageResizer {
    fun resize(jpeg: ByteArray, maxSidePx: Int): ByteArray
}

/** At most `max_parallel` requests of a profile at once; the rest wait their turn, before the request starts. */
object Gates {
    private val gates = HashMap<Pair<String, Int>, Semaphore>()

    @Synchronized
    fun forProfile(p: ServerProfile): Semaphore = gates.getOrPut(p.id to p.maxParallel) { Semaphore(p.maxParallel) }
}

/**
 * The model client for a cloud profile (spec 004 FR-007, `shared/contracts/model-backend.md`). Everything
 * about a call comes from the [profile]: the URL, the timeout for [role], the JSON mode, the image handling,
 * the extra body, whether a key is sent. It builds the request exactly as `pc/.../providers/client.py` does
 * (the conformance cases `backend/requests_*` pin the bodies). It does not retry, record or count the cost:
 * those belong to the caller (the budget ledger and the recorder).
 */
class CloudBackend(
    override val profile: ServerProfile,
    private val role: Role,
    private val transport: HttpTransport,
    private val key: () -> Secret?,
    private val resizer: ImageResizer? = null,
    private val gate: Semaphore = Gates.forProfile(profile),
    private val nanoTime: () -> Long = System::nanoTime,
) : ModelBackend {
    init {
        require(role in profile.roles) { "model profile ${profile.id} is not for the ${role.id} role" }
    }

    override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult {
        val content = buildJsonArray {
            for (img in images) {
                if (!img.label.isNullOrEmpty()) add(textPart(img.label))
                val jpeg = profile.image.maxSidePx?.let {
                    checkNotNull(resizer) { "profile ${profile.id} resizes images and no resizer was given" }.resize(img.jpeg, it)
                } ?: img.jpeg
                val sent = if (profile.image.detail == "request") detail else profile.image.detail
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
                        if (sent != null) put("detail", sent)
                    })
                })
            }
            add(textPart(if (schema == null) question else question + SCHEMA_LINE + PythonJson.dumps(schema)))
        }
        val messages = buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", content) }) }
        // `look` records the timeout as a float, as httpx was given it
        return call(messages, schema, null, JsonPrimitive(profile.timeout(role)))
    }

    override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult {
        require(tools.isNullOrEmpty() || profile.supportsTools) { "model profile ${profile.id} does not support tool calls" }
        val json = buildJsonArray {
            for (m in messages) add(buildJsonObject {
                put("role", m.role.name.lowercase()); put("content", m.content)
                if (m.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                    m.toolCalls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id); put("type", "function")
                            // the arguments travel as the JSON text the model wrote, as the API has them
                            put("function", buildJsonObject { put("name", c.name); put("arguments", (c.arguments as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (c.arguments?.toString() ?: "{}")) })
                        })
                    }
                })
                if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
            })
        }
        val t = profile.timeout(role)
        // `chat` goes through the openai package, which was given a whole number of seconds as an int
        val timeout = if (t == Math.rint(t)) JsonPrimitive(t.toLong()) else JsonPrimitive(t)
        return call(json, schema, tools, timeout)
    }

    private suspend fun call(messages: JsonArray, schema: JsonObject?, tools: List<ToolDef>?, timeout: JsonPrimitive): ModelResult {
        val secret = if (profile.apiKeyEnv != null) key() ?: throw ModelError.KeyMissing(profile.id) else null
        val body = buildJsonObject {
            put("model", profile.model)
            put("messages", messages)
            responseFormat(schema)?.let { put("response_format", it) }
            if (!tools.isNullOrEmpty()) put("tools", buildJsonArray { tools.forEach { add(toolJson(it)) } })
            profile.extraBody.forEach { (k, v) -> put(k, v) } // the profile's own fields win, as body.update does
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (secret != null) put("Authorization", "Bearer ${secret.reveal()}")
        }
        val request = HttpRequest("POST", profile.apiBase.trimEnd('/') + "/chat/completions", headers, body.toString().toByteArray(Charsets.UTF_8), timeout)
        return gate.withPermit {
            val t0 = nanoTime()
            val response = transport.send(request, profile.id)
            val latency = (nanoTime() - t0) / 1e9
            if (response.status !in 200..299) throw ModelError.Server(profile.id, response.status, "the model server for profile ${profile.id} answered HTTP ${response.status}")
            parse(response.body, schema, latency)
        }
    }

    private fun responseFormat(schema: JsonObject?): JsonObject? = when {
        schema == null || profile.jsonMode == "prompt" -> null
        profile.jsonMode == "json_schema" -> buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject { put("name", "answer"); put("schema", schema) })
        }
        else -> buildJsonObject { put("type", "json_object") }
    }

    private fun toolJson(t: ToolDef): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) })
    }

    private fun textPart(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

    private fun parse(bytes: ByteArray, schema: JsonObject?, latencyS: Double): ModelResult {
        val reply = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw ModelError.Server(profile.id, 200, "the model server for profile ${profile.id} did not answer with JSON")
        }
        val message = try {
            reply.getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject
        } catch (e: Exception) {
            throw ModelError.Server(profile.id, 200, "the model server for profile ${profile.id} sent a reply with no message")
        }
        val text = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val usage = reply["usage"] as? JsonObject
        val input = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val toolCalls = (message["tool_calls"] as? JsonArray)?.map {
            val c = it.jsonObject
            val f = c.getValue("function").jsonObject
            ToolCall(c.getValue("id").jsonPrimitive.content, f.getValue("name").jsonPrimitive.content, f["arguments"])
        } ?: emptyList()
        val data = if (schema == null) null else FirstJson.of(text) as? JsonObject
        return ModelResult(text, data, toolCalls, Usage(input, output), profile.usd(input, output), Math.round(latencyS * 1000) / 1000.0)
    }

    companion object {
        const val SCHEMA_LINE = "\nAnswer with JSON only, matching this schema:\n"
    }
}

/** The first JSON value of a reply: a code fence is stripped, and a second object after the first is ignored (`first_json`). */
object FirstJson {
    fun of(text: String): JsonElement? {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = if ('\n' in t) t.substringAfter('\n') else t
            t = t.substringBeforeLast("```", t)
        }
        t = t.trim()
        val end = endOfValue(t) ?: return null
        return try {
            Json.parseToJsonElement(t.substring(0, end))
        } catch (e: Exception) {
            null
        }
    }

    /** The index after the JSON value that starts at 0, or null when the text does not start with one. */
    private fun endOfValue(t: String): Int? {
        if (t.isEmpty()) return null
        if (t[0] == '{' || t[0] == '[') {
            var depth = 0
            var inString = false
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) return i + 1 }
                }
                i++
            }
            return null
        }
        val m = Regex("""^("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?|true|false|null)""").find(t) ?: return null
        return m.value.length
    }
}
