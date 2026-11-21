package app.epicreplay.core.commentator

import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.budget.PaidResult
import app.epicreplay.core.budget.paidCall
import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.ChatRole
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelError
import app.epicreplay.core.models.ModelResult
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.models.ToolCall
import app.epicreplay.core.models.ToolDef
import app.epicreplay.core.record.Recorder
import app.epicreplay.core.record.cacheKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** How the script is written (spec 004 FR-021, D7): the PC's agent loop over tools; a JSON draft whose failures are given back; or one structured draft, mended by code. */
enum class CommentatorMode { TOOLS, FEEDBACK, STRUCTURED }

class CommentatorResult(val outcome: ScriptOutcome, val mode: CommentatorMode, val calls: List<JsonObject>, val turns: Int, val usd: Double, val replayed: Boolean = false)

/**
 * The Commentator's loop over `chat()` (spec 004 FR-021): in [CommentatorMode.TOOLS] the PC's agent (two tools, `check_lines` and `submit_script`,
 * at most `max_iterations` turns, the first accepted submit is the result) and, when it submits nothing that passes, one structured draft; in
 * [CommentatorMode.FEEDBACK] (a profile that takes no tool calls) a JSON draft whose failures are given back for [maxFeedbackTurns]; in
 * [CommentatorMode.STRUCTURED] the one draft. What no draft fixes is replaced by generic lines (`Commentator.conclude`). Every limit lives in
 * the tools, not in the prompt (rule 6). With a [ledger] each paid part reserves its estimate first; with a [recorder] the agent's run and the
 * structured draft are recorded and replayed under the PC's keys (`commentator`, `commentator_draft`), so a recording made on either platform is read by the other.
 */
class CommentatorRun(
    private val commentator: Commentator,
    private val backend: ModelBackend,
    private val words: CommentatorWords,
    private val model: String,
    private val ledger: Ledger? = null,
    private val recorder: Recorder? = null,
    private val runId: String = "run",
    val mode: CommentatorMode = if (backend.profile.supportsTools) CommentatorMode.TOOLS else CommentatorMode.FEEDBACK,
    private val maxFeedbackTurns: Int = 3,
) {
    private val max get() = words.maxIterations
    private fun prices(): Pair<Double, Double> = (backend.profile as? ServerProfile)?.let { it.priceInPerM to it.priceOutPerM } ?: (0.0 to 0.0)
    private fun round6(x: Double) = Math.round(x * 1e6) / 1e6
    private fun agentEstimate() = prices().let { round6(max * (5000 * it.first + 1500 * it.second) / 1e6) }
    private fun draftEstimate() = prices().let { round6((5000 * it.first + 3000 * it.second) / 1e6) }

    private fun parse(text: String): JsonElement? = try { Json.parseToJsonElement(text) } catch (e: Exception) { null }

    /** The tools' view of a draft: what `check_lines` and `submit_script` answer. */
    private class Session(val c: Commentator, val slots: List<JsonObject>, val ps: JsonObject, val uc: JsonObject, val sb: JsonObject, val pack: JsonObject) {
        val calls = ArrayList<JsonObject>()
        var accepted: JsonObject? = null
        private fun draftOf(args: JsonElement?): JsonObject {
            val o = (args as? JsonObject) ?: ((args as? JsonPrimitive)?.takeIf { it.isString }?.let { runCatching { Json.parseToJsonElement(it.content) }.getOrNull() } as? JsonObject) ?: JsonObject(emptyMap())
            return buildJsonObject {
                put("lines", o["lines"] ?: JsonArray(emptyList())); put("headline", o["headline"] ?: JsonPrimitive("")); put("lower_third", o["lower_third"] ?: JsonPrimitive(""))
            }
        }
        fun call(name: String, args: JsonElement?): String {
            val draft = draftOf(args)
            val fails = c.failures(draft, slots, ps, uc, sb, pack)
            val n = (draft["lines"] as? JsonArray)?.size ?: 0
            if (name == "check_lines" || name == "submit_script") calls += buildJsonObject { put("tool", name); put("lines", n); put("failures", fails.size) }
            if (name == "check_lines") {
                return if (fails.isEmpty()) "All lines pass. Call submit_script with this draft." else buildJsonObject { put("failures", JsonArray(fails)) }.toString()
            }
            if (name == "submit_script") {
                if (fails.isNotEmpty()) return buildJsonObject { put("rejected", true); put("failures", JsonArray(fails)) }.toString()
                if (accepted == null) accepted = draft
                return "Accepted. You are done."
            }
            return "unknown tool $name" // nothing is accepted by a tool this loop does not have
        }
    }

    private fun tools(): List<ToolDef> {
        val params = buildJsonObject {
            put("type", "object"); put("properties", words.draftSchema.getValue("properties")); put("required", JsonArray(listOf(JsonPrimitive("lines"))))
        }
        return listOf("check_lines", "submit_script").map { ToolDef(it, words.toolDescription(it), params) }
    }

    private class Agent(val draft: JsonObject?, val calls: List<JsonObject>, val usd: Double, val turns: Int, val tokensIn: Long, val tokensOut: Long) {
        /** As the PC records an agent run (`draft`, `calls`, `usage`), and what this platform also keeps: the cost and the turns. */
        fun payload(): JsonObject = buildJsonObject {
            put("draft", draft ?: JsonNull); put("calls", JsonArray(calls))
            put("usage", buildJsonObject { put("input_tokens", tokensIn); put("output_tokens", tokensOut) })
            put("usage_usd", usd); put("turns", turns)
        }
    }

    /** The agent's turns: ask, run the tools it calls, give the answers back; ends at the first accepted submit, a turn with no tool call, or the limit. */
    private suspend fun agentLoop(s: Session, system: String, query: String): Agent {
        val messages = ArrayList<ChatMessage>(listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, query)))
        var usd = 0.0; var turns = 0; var tin = 0L; var tout = 0L
        while (turns < max && s.accepted == null) {
            val r: ModelResult = backend.chat(messages, null, tools())
            usd += r.usd; turns++; tin += r.usage.inputTokens; tout += r.usage.outputTokens
            if (r.toolCalls.isEmpty()) break // an answer in plain text ends it, as in the ReAct loop
            messages += ChatMessage(ChatRole.ASSISTANT, r.text, r.toolCalls)
            for (c: ToolCall in r.toolCalls) messages += ChatMessage(ChatRole.TOOL, s.call(c.name, c.arguments), toolCallId = c.id)
        }
        return Agent(s.accepted, s.calls, usd, turns, tin, tout)
    }

    private fun structuredSystem(system: String) = system.split("Work:")[0] + words.structuredDraftIntro + PythonJson.dumps(words.draftSchema)

    private suspend fun structuredCall(system: String, query: String): Pair<JsonObject, Double> {
        val r = backend.chat(listOf(ChatMessage(ChatRole.SYSTEM, structuredSystem(system)), ChatMessage(ChatRole.USER, query)), words.draftSchema, null)
        val draft = r.data ?: (parse(r.text) as? JsonObject) ?: JsonObject(emptyMap())
        return draft to r.usd
    }

    suspend fun write(slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): CommentatorResult {
        val system = commentator.systemPrompt(pack)
        val query = "Brief (data):\n" + PythonJson.dumpsIndentedAscii(commentator.brief(ps, uc, sb, slots), 1)
        val session = Session(commentator, slots, ps, uc, sb, pack)
        var note = "the agent submitted no script that passes"
        val calls = ArrayList<JsonObject>()
        var usd = 0.0; var turns = 0; var replayed = false; var failure: String? = null

        if (mode == CommentatorMode.TOOLS) {
            val key = cacheKey("commentator", model, buildJsonObject { put("system", system); put("query", query) })
            val known = recorder?.has("commentator", key) == true
            val run = suspend {
                try {
                    val (payload, hit) = recorder?.fetch("commentator", key) { agentLoop(session, system, query).payload() } ?: (agentLoop(session, system, query).payload() to false)
                    PaidResult(payload to hit, if (hit) 0.0 else (payload["usage_usd"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0), hit)
                } catch (e: ModelError) {
                    // the agent stopped, perhaps after paid turns: nothing is recorded, the fallback goes on, and the run counts at its estimate (the PC's AgentFailed)
                    failure = "the agent failed (${e.javaClass.simpleName})"
                    PaidResult(buildJsonObject { put("draft", JsonNull); put("calls", JsonArray(session.calls)); put("usage_usd", agentEstimate()); put("turns", 0) } to false, agentEstimate(), false)
                }
            }
            val (payload, hit) = if (ledger == null) run().value else ledger.paidCall(runId, "S5", "tool-loop", model, if (known) 0.0 else agentEstimate(), false) { run() }
            replayed = hit
            if (failure != null) note = failure!!
            usd += if (hit) 0.0 else payload["usage_usd"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            turns = payload["turns"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            payload["calls"]?.jsonArray?.forEach { calls += it.jsonObject }
            val draft = payload["draft"] as? JsonObject
            if (draft != null && commentator.failures(draft, slots, ps, uc, sb, pack).isEmpty()) {
                return CommentatorResult(commentator.accepted(draft, slots, ps, uc, pack), mode, calls, turns, usd, replayed)
            }
        } else if (mode == CommentatorMode.FEEDBACK) {
            return feedback(system, query, slots, ps, uc, sb, pack)
        } else {
            note = "${backend.profile.label} was asked for a structured draft only"
        }

        // the structured draft, then the generic lines
        val dkey = cacheKey("commentator_draft", model, buildJsonObject { put("system", system); put("query", query) })
        var structured: JsonObject? = null
        try {
            val run = suspend {
                val (payload, hit) = recorder?.fetch("commentator_draft", dkey) {
                    val (d, u) = structuredCall(system, query); buildJsonObject { put("draft", d); put("usage_usd", u) }
                } ?: structuredCall(system, query).let { (d, u) -> buildJsonObject { put("draft", d); put("usage_usd", u) } to false }
                PaidResult(payload to hit, if (hit) 0.0 else payload["usage_usd"]!!.jsonPrimitive.content.toDouble(), hit)
            }
            val known = recorder?.has("commentator_draft", dkey) == true
            val (payload, hit) = if (ledger == null) run().value else ledger.paidCall(runId, "S5", "chat", model, if (known) 0.0 else draftEstimate(), false) { run() }
            structured = payload["draft"] as? JsonObject ?: JsonObject(emptyMap())
            if (!hit) usd += payload["usage_usd"]!!.jsonPrimitive.content.toDouble()
            calls += buildJsonObject { put("tool", "structured_draft"); put("failures", commentator.failures(structured, slots, ps, uc, sb, pack).size) }
        } catch (e: ModelError) {
            note = "$note; the structured draft failed (${e.javaClass.simpleName})"
        }
        return CommentatorResult(commentator.conclude(structured, note, slots, ps, uc, sb, pack), mode, calls, turns, usd, replayed)
    }

    /** A JSON draft; its failures are given back and it is asked again, up to [maxFeedbackTurns]; the best draft is mended by code. */
    private suspend fun feedback(system: String, query: String, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): CommentatorResult {
        val messages = ArrayList<ChatMessage>(listOf(ChatMessage(ChatRole.SYSTEM, structuredSystem(system)), ChatMessage(ChatRole.USER, query)))
        val calls = ArrayList<JsonObject>()
        var best: JsonObject? = null; var bestFails = Int.MAX_VALUE; var usd = 0.0; var turns = 0
        val run = suspend {
            var accepted: JsonObject? = null
            while (turns < maxFeedbackTurns && accepted == null) {
                val r = backend.chat(messages, words.draftSchema, null) // a failure leaves the ledger to settle it (a timeout at its estimate), then the best draft so far is mended
                turns++; usd += r.usd
                val draft = r.data ?: (parse(r.text) as? JsonObject) ?: JsonObject(emptyMap())
                val fails = commentator.failures(draft, slots, ps, uc, sb, pack)
                calls += buildJsonObject { put("tool", "draft"); put("lines", (draft["lines"] as? JsonArray)?.size ?: 0); put("failures", fails.size) }
                if (fails.size < bestFails) { best = draft; bestFails = fails.size }
                if (fails.isEmpty()) accepted = draft
                else {
                    messages += ChatMessage(ChatRole.ASSISTANT, draft.toString())
                    messages += ChatMessage(ChatRole.USER, "These lines failed. Fix them and answer with the whole JSON draft again, nothing else:\n" + buildJsonObject { put("failures", JsonArray(fails)) })
                }
            }
            PaidResult(accepted, usd, false)
        }
        val accepted = try {
            if (ledger == null) run().value else ledger.paidCall(runId, "S5", "chat", model, round6(maxFeedbackTurns * draftEstimate()), false) { run() }
        } catch (e: ModelError) {
            val note = "the draft call failed (${e.javaClass.simpleName}) after $turns turn(s)"
            return CommentatorResult(commentator.conclude(best, note, slots, ps, uc, sb, pack), CommentatorMode.FEEDBACK, calls, turns, usd)
        }
        if (accepted != null) return CommentatorResult(commentator.accepted(accepted, slots, ps, uc, pack), CommentatorMode.FEEDBACK, calls, turns, usd)
        return CommentatorResult(commentator.conclude(best, "no draft passed in $turns turn(s)", slots, ps, uc, sb, pack), CommentatorMode.FEEDBACK, calls, turns, usd)
    }
}
