package app.epicreplay.core.ui

import app.epicreplay.core.PyMath
import app.epicreplay.core.run.FlowOrder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One of the eight steps as the progress screen shows it: `done`, `running`, `waiting`, `waiting_user` (the card), or `failed`; the seconds of a finished step. */
class StepView(val n: Int, val name: String, val status: String, val durationS: Int?, val costUsd: Double)

/** What the progress screen shows for a run: its status, the eight steps, the time left in words and, for a run that stopped, what to tell the user. */
class RunState(val status: String, val style: String?, val language: String, val costUsd: Double, val steps: List<StepView>, val stepN: Int, val timeLeft: String, val message: String?, val hasClip: Boolean, val hasVideo: Boolean) {
    fun toJson(): JsonObject = buildJsonObject {
        put("status", status); put("style", style?.let { JsonPrimitive(it) } ?: JsonNull); put("language", language); put("cost_usd", costUsd)
        put("steps", JsonArray(steps.map { s -> buildJsonObject { put("n", s.n); put("name", s.name); put("status", s.status); put("duration_s", s.durationS?.let { JsonPrimitive(it) } ?: JsonNull); put("cost_usd", s.costUsd) } }))
        put("step_n", stepN); put("time_left", timeLeft); put("message", message?.let { JsonPrimitive(it) } ?: JsonNull); put("has_clip", hasClip); put("has_video", hasVideo)
    }

    companion object {
        private const val REJECTED = "stopped at the play card"
        private const val DEFAULT_STAGE_S = 3.0

        private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        /** `web.app.state` for a manifest: [typical] is the median duration of each stage on this phone (a stage not in it counts as 3 s). */
        fun of(manifest: JsonObject, typical: Map<String, Double>, words: PageWords, hasClip: Boolean, hasVideo: Boolean): RunState {
            val status = manifest.getValue("status").jsonPrimitive.content
            val stages = manifest.getValue("stages").jsonArray.map { it.jsonObject }
            val last = LinkedHashMap<String, JsonObject>().also { m -> for (s in stages) m[s.getValue("stage").jsonPrimitive.content] = s }
            val done = last.filterValues { it.getValue("status").jsonPrimitive.content in FlowOrder.DONE }.keys
            val order = FlowOrder.ORDER.map { it.name }
            val current = order.firstOrNull { it !in done }
            val steps = ArrayList<StepView>()
            for ((i, def) in words.steps.withIndex()) {
                val n = i + 1
                val mine = def.stages.mapNotNull { last[it] }
                var st = when {
                    mine.any { it.getValue("status").jsonPrimitive.content in setOf("failed", "stopped") } -> "failed"
                    def.stages.all { it in done } -> "done"
                    current in def.stages || (mine.isNotEmpty() && status == "running") -> if (current == FlowOrder.CHECKPOINT_A) "waiting_user" else "running"
                    else -> "waiting"
                }
                if (status in setOf("stopped", "failed") && st == "running") st = "waiting"
                val name = if (i == 3 && st == "done") words.confirmed else def.name
                val secs = mine.filter { it.getValue("status").jsonPrimitive.content in FlowOrder.DONE && it.getValue("stage").jsonPrimitive.content != FlowOrder.CHECKPOINT_A }.sumOf { it.getValue("duration_s").jsonPrimitive.double }
                val cost = mine.sumOf { (it["cost_usd"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.double ?: 0.0 }
                steps += StepView(n, name, st, if (st == "done" && n != 4) Math.rint(secs).toInt() else null, PyMath.round(cost, 4))
            }
            val stepN = steps.firstOrNull { it.status != "done" }?.n ?: 8
            val left = if (current == null) 0.0 else order.drop(order.indexOf(current)).filter { it != FlowOrder.CHECKPOINT_A }.sumOf { typical[it] ?: DEFAULT_STAGE_S }
            val total = (manifest["total_usd"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double?.takeIf { it != 0.0 }
            val cost = PyMath.round(total ?: stages.sumOf { (it["cost_usd"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.double ?: 0.0 }, 4)
            val message = if (status in setOf("stopped", "failed")) stoppedMessage(stages, current, words) else null
            return RunState(status, str(manifest["style"]), str(manifest["language"]) ?: "en", cost, steps, stepN, words.timeLeft[if (left < 10) 0 else if (left < 45) 1 else 2], message, hasClip, hasVideo)
        }

        /** What the screen says when a run stopped, in ui.md's words: never the stage's own note, which can hold provider errors or cost figures, except S0's, written for the user. */
        fun stoppedMessage(stages: List<JsonObject>, current: String?, words: PageWords): String {
            val failed = stages.firstOrNull { it.getValue("status").jsonPrimitive.content in setOf("failed", "stopped") }
            val note = str(failed?.get("note")).orEmpty()
            if (note.startsWith("sensitive: ")) return words.stopped("sensitive").replace("{reason}", note.removePrefix("sensitive: ").trimEnd('.'))
            if ("would be exceeded" in note) return words.stopped("cap")
            if (note == REJECTED) return words.stopped("rejected")
            if (failed != null && failed.getValue("stage").jsonPrimitive.content == "S0" && note.isNotEmpty()) return note
            val where = failed?.getValue("stage")?.jsonPrimitive?.content ?: current
            val step = words.steps.firstOrNull { where in it.stages }?.name ?: "making your replay"
            return words.stopped("failed").replace("{step}", step.lowercase(java.util.Locale.ROOT))
        }
    }
}
