package app.epicreplay.core.ui

import app.epicreplay.core.PyMath
import app.epicreplay.core.setup.ModelsUsed
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One line of the script panel. [generic] (not in the PC's panel) says the coded fallback wrote it (spec 004 FR-018). */
class ScriptLine(val time: String, val role: String, val text: String, val gloss: String?, val key: Boolean, val climax: Boolean, val dropped: Boolean, val generic: Boolean)

class ScriptPanel(val lines: List<ScriptLine>, val lang: String?, val passed: Boolean, val failures: List<String>) {
    /** The PC's panel as JSON (`web.views.script_panel_of`), for the conformance cases. */
    fun toJson(): JsonObject = buildJsonObject {
        put("lines", JsonArray(lines.map { l -> buildJsonObject {
            put("time", l.time); put("role", l.role); put("text", l.text); put("gloss", l.gloss?.let { JsonPrimitive(it) } ?: JsonNull)
            put("key", l.key); put("climax", l.climax); put("dropped", l.dropped)
        } }))
        put("lang", lang?.let { JsonPrimitive(it) } ?: JsonNull); put("passed", passed); put("failures", JsonArray(failures.map { JsonPrimitive(it) }))
    }
}

class CostRow(val name: String, val duration: String, val cost: String)
class TimeAndCost(val rows: List<CostRow>, val duration: String, val cost: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rows", JsonArray(rows.map { r -> buildJsonObject { put("name", r.name); put("duration", r.duration); put("cost", r.cost) } }))
        put("duration", duration); put("cost", cost)
    }
}

/** A model a run used, for the result's list: what it did, which, and where it ran. */
class ModelRow(val name: String, val value: String)

/** What the result page says about the part the coded fallback played (FR-018): empty when the model wrote everything. */
class FallbackNote(val lines: List<String>)

/**
 * What the result screen shows of a finished run, built by code from the run's files (spec 004 FR-017, FR-018; `web/views.py` of the PC for the panels both apps have).
 * Nothing here changes a stage; a file that is missing or cannot be read gives no row.
 */
object ResultModel {
    private val KEY_ROLES = setOf("climax", "replay_climax")

    /** Python's truth of a JSON value: null, false, 0 and the empty string are false. */
    private fun truthy(e: JsonElement?): Boolean = when {
        e == null || e is JsonNull -> false
        e is JsonPrimitive && e.isString -> e.content.isNotEmpty()
        e is JsonPrimitive -> e.booleanOrNull ?: (e.content.toDoubleOrNull()?.let { it != 0.0 } ?: true)
        e is JsonArray -> e.isNotEmpty()
        else -> (e as JsonObject).isNotEmpty()
    }

    private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** `views.script_panel_of`: each line's time and role, the key lines marked, a dropped line marked, and what the checks said. */
    fun scriptPanel(script: JsonObject, voice: JsonObject?, checks: JsonObject?, gloss: JsonObject?, words: PageWords): ScriptPanel {
        val byId = (voice?.get("lines") as? JsonArray).orEmpty().map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        val lines = (script["lines"] as? JsonArray).orEmpty().map { it.jsonObject }.map { ln ->
            val v = byId[ln.getValue("id").jsonPrimitive.content]
            val start = (v?.get("timeline_start") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()
            val dropped = truthy(v?.get("dropped"))
            val role = ln.getValue("role").jsonPrimitive.content
            ScriptLine(
                time = if (start != null && !dropped) PyMath.fixed1(start) + " s · " else "",
                role = words.role(role), text = ln.getValue("text").jsonPrimitive.content, gloss = str(gloss?.get(ln.getValue("id").jsonPrimitive.content)),
                key = role in KEY_ROLES, climax = role == "climax", dropped = dropped, generic = (ln["generic"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val failed = (checks?.get("failures") as? JsonArray).orEmpty().map { it.jsonObject }.map { (str(it["where"]) ?: "") + ": " + (str(it["detail"]) ?: "") }
        val passed = ((checks?.get("passed") as? JsonPrimitive)?.booleanOrNull ?: true) && failed.isEmpty()
        return ScriptPanel(lines, if (str(script["language"]) == "zh") "zh-Hans" else null, passed, failed)
    }

    /** `views.time_and_cost` for a progress state's steps: each step's seconds and cost, and the totals. */
    fun timeAndCost(steps: List<StepView>, costUsd: Double): TimeAndCost = TimeAndCost(
        steps.map { CostRow(it.name, it.durationS?.let { d -> "$d s" } ?: "", PyMath.fixed(it.costUsd, 4)) },
        "${steps.sumOf { it.durationS ?: 0 }} s", PyMath.fixed(costUsd, 4),
    )

    fun timeAndCostOf(state: JsonObject): TimeAndCost = timeAndCost(
        state.getValue("steps").jsonArray.mapIndexed { i, e ->
            val o = e.jsonObject
            StepView(i + 1, o.getValue("name").jsonPrimitive.content, "done", (o["duration_s"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toInt(), o.getValue("cost_usd").jsonPrimitive.content.toDouble())
        },
        state.getValue("cost_usd").jsonPrimitive.content.toDouble(),
    )

    /** The models a run used, from its manifest's `stack` (FR-017); none for a run from before they were recorded. */
    fun modelRows(manifest: JsonObject): List<ModelRow> {
        val used = (manifest["stack"] as? JsonObject)?.get("models") as? JsonObject ?: return emptyList()
        return listOf("vision" to "Looking at the clip", "text" to "Writing the script").mapNotNull { (role, name) ->
            val m = used[role] as? JsonObject ?: return@mapNotNull null
            val where = if (str(m["location"]) == "local") "on this phone" else "at a cloud provider"
            ModelRow(name, "${str(m["label"]) ?: str(m["profile"]) ?: "?"}, ${str(m["model"]) ?: "?"}, $where")
        }
    }

    /**
     * FR-018: when the coded fallback wrote lines, or a stage ran with calls left out, say which, so a generic video is not mistaken for a failed one.
     * Empty when the model wrote every line and no stage was degraded.
     */
    fun fallbackNote(manifest: JsonObject, panel: ScriptPanel?, words: PageWords): FallbackNote {
        val out = ArrayList<String>()
        val generic = panel?.lines?.filter { it.generic }.orEmpty()
        if (generic.isNotEmpty()) out += "${generic.size} of ${panel!!.lines.size} lines were written by code, not by the model: ${generic.joinToString(", ") { it.role }}."
        val degraded = (manifest["stages"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { str(it["status"]) == "degraded" }.mapNotNull { st -> words.steps.firstOrNull { str(st["stage"]) in it.stages }?.name }.distinct()
        for (step in degraded) out += "\"$step\" ran with some calls left out; code filled in."
        return FallbackNote(out)
    }
}
