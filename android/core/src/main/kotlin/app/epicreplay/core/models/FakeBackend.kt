package app.epicreplay.core.models

import kotlinx.serialization.json.JsonObject

/**
 * A backend for tests: it answers with scripted results or errors, in order, and records every call.
 * Nothing leaves the process. Use [reply] and [fail] to queue answers; a call with none queued fails the test.
 */
class FakeBackend(override val profile: Profile) : ModelBackend {
    sealed interface Call {
        class Look(val images: List<LabeledImage>, val question: String, val schema: JsonObject?) : Call
        class Chat(val messages: List<ChatMessage>, val schema: JsonObject?, val tools: List<ToolDef>?) : Call
    }

    val calls = mutableListOf<Call>()
    private val script = ArrayDeque<() -> ModelResult>()

    fun reply(text: String, data: JsonObject? = null, toolCalls: List<ToolCall> = emptyList(), usage: Usage = Usage(0, 0)): FakeBackend {
        script += { ModelResult(text, data, toolCalls, usage, profile.usd(usage.inputTokens, usage.outputTokens), 0.0) }
        return this
    }

    fun fail(error: ModelError): FakeBackend {
        script += { throw error }
        return this
    }

    private fun next(): ModelResult =
        (script.removeFirstOrNull() ?: error("FakeBackend: no reply queued for call ${calls.size}"))()

    override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?): ModelResult {
        calls += Call.Look(images, question, schema)
        return next()
    }

    override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult {
        require(tools == null || profile.supportsTools) { "profile ${profile.id} does not support tools" }
        calls += Call.Chat(messages, schema, tools)
        return next()
    }
}
