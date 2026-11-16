package app.epicreplay.core.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A picture for [ModelBackend.look]: JPEG bytes and an optional label sent before it as text. */
class LabeledImage(val label: String?, val jpeg: ByteArray)

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

class ChatMessage(val role: ChatRole, val content: String)

/** A tool the model may call; [parameters] is a JSON Schema object. Only for a profile with `supportsTools`. */
class ToolDef(val name: String, val description: String, val parameters: JsonObject)

class ToolCall(val id: String, val name: String, val arguments: JsonElement?)

class Usage(val inputTokens: Long, val outputTokens: Long)

/**
 * What a call returns (`shared/contracts/model-backend.md`). [data] is the first JSON object in the reply,
 * or null (also when no schema was asked for). [usd] is 0 for a local or on-device model. [latencyS] runs
 * from before the request to after the reply.
 */
class ModelResult(
    val text: String,
    val data: JsonObject?,
    val toolCalls: List<ToolCall>,
    val usage: Usage,
    val usd: Double,
    val latencyS: Double,
)

/** The errors of the contract. The message never holds a key. */
sealed class ModelError(message: String) : Exception(message) {
    /** A server profile's server did not answer before the request was made. */
    class Unreachable(val profileId: String, message: String) : ModelError(message)

    /** A call took longer than the profile's timeout; it may still have been billed. */
    class Timeout(val profileId: String, message: String) : ModelError(message)

    /** The reply and its one retry did not fit the schema. */
    class SchemaMisfit(val profileId: String, message: String) : ModelError(message)

    /** An `on_device` profile's files are missing or fail their checksum (spec 004 FR-004). */
    class NotInstalled(val profileId: String, val missing: List<String>) :
        ModelError("the model files of profile $profileId are not installed: ${missing.joinToString()}")

    /** Not enough free memory to load an on-device model; refused before loading, never a crash (FR-014). */
    class OutOfMemory(val profileId: String, val neededMb: Int, val freeMb: Int) :
        ModelError("profile $profileId needs about $neededMb MB free; $freeMb MB is free")
}

/**
 * One model behind one profile: the same two operations on every backend (spec 004 FR-004). Calls are
 * serialised by the implementation as the profile's `max_parallel` says, and can be cancelled.
 */
interface ModelBackend {
    val profile: Profile

    /** [images] are looked at with [question]; with a [schema] the reply is JSON matching it. */
    suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject? = null): ModelResult

    suspend fun chat(messages: List<ChatMessage>, schema: JsonObject? = null, tools: List<ToolDef>? = null): ModelResult
}
