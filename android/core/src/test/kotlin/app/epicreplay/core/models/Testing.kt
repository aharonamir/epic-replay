package app.epicreplay.core.models

import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpResponse
import app.epicreplay.core.net.HttpTransport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Shared helpers of the T408 tests. */
object Testing {
    val shared = File(System.getProperty("shared.dir"))

    fun profile(id: String = "deepseek-flash"): ServerProfile =
        ProfileLoader.loadDir(File(shared, "models"), File(shared, "contracts/model_profile.schema.json"), Platform.PC).profiles.getValue(id) as ServerProfile

    val REPLY: String = """{"id":"c","object":"chat.completion","created":1,"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    fun reply(content: String, prompt: Int = 1000, completion: Int = 500, toolCalls: String? = null): String {
        val msg = buildJsonObject {
            put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(content))
            if (toolCalls != null) put("tool_calls", Json.parseToJsonElement(toolCalls))
        }
        return buildJsonObject {
            put("choices", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("index", JsonPrimitive(0)); put("message", msg) })))
            put("usage", buildJsonObject { put("prompt_tokens", JsonPrimitive(prompt)); put("completion_tokens", JsonPrimitive(completion)) })
        }.toString()
    }

    fun parse(s: String): JsonElement = Json.parseToJsonElement(s)

    /** The request as the Python tests record it (the shape of `requests` in `backend.requests`). */
    fun record(r: HttpRequest): JsonObject = buildJsonObject {
        put("method", JsonPrimitive(r.method)); put("url", JsonPrimitive(r.url))
        put("authorization", r.headers["Authorization"]?.let { JsonPrimitive(it) } ?: JsonNull)
        put("content_type", r.headers["Content-Type"]?.let { JsonPrimitive(it) } ?: JsonNull)
        put("timeout", buildJsonObject { for (k in listOf("connect", "read", "write", "pool")) put(k, r.timeoutS) })
        put("body", Json.parseToJsonElement(String(r.body!!, Charsets.UTF_8)))
    }
}

/** Answers every request with [body] and keeps what it was asked. Nothing leaves the process. */
class CapturingTransport(private val body: () -> String = { Testing.REPLY }, private val status: Int = 200) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest, profileId: String): HttpResponse {
        requests += request
        return HttpResponse(status, body().toByteArray())
    }
}
