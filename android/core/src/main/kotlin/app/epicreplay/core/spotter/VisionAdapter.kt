package app.epicreplay.core.spotter

import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.budget.PaidResult
import app.epicreplay.core.budget.paidCall
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelError
import app.epicreplay.core.record.Recorder
import app.epicreplay.core.record.cacheKey
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What an adapter call gave: the JSON that fit the schema, the cost of the tries, whether every try came from a recording, and how many were made. */
class VisionAnswer(val data: JsonObject, val text: String, val usd: Double, val cacheHit: Boolean, val attempts: Int, val latencyS: Double)

/**
 * The vision adapter of the PC (`providers/vision.py` `_ask`) over a [ModelBackend]: with a schema one call and, when the reply does not
 * fit, one more with [retryLine] added to the question; each try is its own call, its own recording (the PC's keys, so a recording made on
 * either platform is read by the other) and, with a [ledger], its own reservation against the caps (a recording costs nothing and reserves
 * nothing). Raises [ModelError.SchemaMisfit] when the retry does not fit either.
 *
 * [model] is the model name the recording key uses (the profile's, or the setting that overrides it); [estimateUsd] is what one try may
 * cost for the cap check (the profile's `call_ceiling_usd`, 0 for a model on the phone).
 */
class VisionAdapter(
    private val backend: ModelBackend,
    private val model: String,
    private val retryLine: String,
    private val estimateUsd: Double = 0.0,
    private val ledger: Ledger? = null,
    private val recorder: Recorder? = null,
    private val runId: String = "run",
    private val stage: String = "S2",
    private val preCheckpoint: Boolean = true,
) {
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun misfit(data: JsonElement?, schema: JsonObject?): String {
        if (schema == null) return ""
        if (data == null || data is JsonNull) return "the reply was not JSON"
        val errors = mutableListOf<ValidationError>()
        JsonSchema.fromDefinition(schema.toString()).validate(data, errors::add)
        return errors.firstOrNull()?.message?.take(200) ?: ""
    }

    /** [request] is the cache-key request without its question: `{detail, schema, sheet_cells}` or `{detail, schema, grids}`. */
    suspend fun ask(request: JsonObject, images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): VisionAnswer {
        val tries = if (schema == null) listOf(question) else listOf(question, "$question\n$retryLine")
        val files = images.map { sha256(it.jpeg) }
        var cost = 0.0; val hits = ArrayList<Boolean>(); var last = ""; var latency = 0.0
        for (q in tries) {
            val key = cacheKey("vision", model, JsonObject(request + ("question" to JsonPrimitive(q))), files)
            suspend fun send(): JsonObject {
                val r = backend.look(images, q, schema, detail)
                return buildJsonObject {
                    put("text", r.text); put("data", r.data ?: JsonNull)
                    put("usage", buildJsonObject { put("prompt_tokens", r.usage.inputTokens); put("completion_tokens", r.usage.outputTokens) })
                    put("usage_usd", r.usd); put("latency_s", r.latencyS)
                }
            }
            suspend fun fetch(): PaidResult<Pair<JsonObject, Boolean>> {
                val (payload, hit) = recorder?.fetch("vision", key) { send() } ?: (send() to false)
                return PaidResult(payload to hit, if (hit) 0.0 else payload.getValue("usage_usd").jsonPrimitive.double, hit)
            }
            val (payload, hit) = if (ledger == null) fetch().value else {
                val est = if (recorder?.has("vision", key) == true) 0.0 else estimateUsd
                ledger.paidCall(runId, stage, "vision", model, est, preCheckpoint) { fetch() }
            }
            hits += hit
            if (!hit) cost += payload.getValue("usage_usd").jsonPrimitive.double
            latency += (payload["latency_s"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double ?: 0.0
            val data = payload["data"]
            last = misfit(data, schema)
            if (last.isEmpty()) return VisionAnswer(data as? JsonObject ?: JsonObject(emptyMap()), payload.getValue("text").jsonPrimitive.content, cost, hits.all { it }, hits.size, latency)
        }
        throw ModelError.SchemaMisfit(backend.profile.id, "${tries.size} replies did not fit the schema; last: $last")
    }
}
