package app.epicreplay.core.spotter

import app.epicreplay.core.PyMath
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.sheet.ContactSheet
import app.epicreplay.core.text.EnglishLexicon
import app.epicreplay.core.text.PyText
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Spotter's plain code (`pc/src/epic_replay/agents/spotter.py` and `stages/s3_key_moment.py`, plan 4.3 and 4.4): which sheets to
 * draw, what the model's answers become (events with times, the sensitivity flag, the proposed moment and the play sheet), and the
 * rules the play sheet must pass. The model calls themselves are [SpotterRun]'s.
 */
class Spotter(private val english: EnglishLexicon, playSheetSchema: String) {
    private val schema = JsonSchema.fromDefinition(playSheetSchema)

    // --- the overview plan

    fun planOverview(info: JsonObject): JsonArray {
        val fps = info.getValue("fps").jsonPrimitive.double
        val n = info.getValue("frames").jsonPrimitive.int
        val dur = n / fps
        val first = ContactSheet.sheetFrames(0.3, maxOf(0.3, dur - 0.3), fps, n)
        // numpy.linspace(first, last, 18): first + i * step, the last value exactly `last`
        val a = first.first(); val b = first.last()
        val step = (b - a).toDouble() / (UNIFORM_FRAMES - 1)
        val even = List(UNIFORM_FRAMES) { i -> if (i == UNIFORM_FRAMES - 1) b else Math.rint(i * step + a).toInt() }.toSortedSet().toList()
        val halves = listOf(even.take(even.size / 2), even.drop(even.size / 2))
        val plan = ArrayList<Triple<String, List<Int>, String>>()
        for ((k, f) in halves.withIndex()) plan += Triple("uniform", f, "These frames are spread evenly over part ${k + 1} of 2 of the video.")
        for (c in (info.getValue("candidates") as JsonArray).take(SHEET_CANDIDATES)) {
            val t = c.jsonObject.getValue("t").jsonPrimitive.double
            plan += Triple("candidate", ContactSheet.sheetFrames(t - CANDIDATE_HALF_S, t + CANDIDATE_HALF_S, fps, n), WHERE_CANDIDATE)
        }
        return JsonArray(plan.mapIndexed { k, (kind, frames, where) ->
            buildJsonObject {
                put("kind", kind); put("frames", JsonArray(frames.map(::JsonPrimitive))); put("where", where); put("name", "o${k}_$kind.jpg")
            }
        })
    }

    // --- the events

    private fun strip(s: String) = s.trim { PyText.isSpace(it) }

    /** The model's events with each grid and cell replaced by its time, in time order. */
    fun mapEvents(data: JsonObject, grids: List<List<JsonObject>>): List<JsonObject> {
        fun t(grid: Int, cell: Int): Double {
            val cells = grids[minOf(maxOf(grid, 1), grids.size) - 1]
            return cells[minOf(maxOf(cell, 1), cells.size) - 1].getValue("t").jsonPrimitive.double
        }
        val out = data.getValue("events").jsonArray.map { e ->
            val o = e.jsonObject
            val start = t(o.getValue("first_grid").jsonPrimitive.int, o.getValue("first_cell").jsonPrimitive.int)
            val end = maxOf(start, t(o.getValue("last_grid").jsonPrimitive.int, o.getValue("last_cell").jsonPrimitive.int))
            val outcome = strip(o.getValue("outcome").jsonPrimitive.content)
            buildJsonObject {
                put("t_start", start); put("t_end", end)
                put("actor", strip(o.getValue("actor").jsonPrimitive.content)); put("action", strip(o.getValue("action").jsonPrimitive.content))
                put("outcome", if (outcome.isEmpty()) JsonNull else JsonPrimitive(outcome))
            }
        }
        return out.sortedBy { it.getValue("t_start").jsonPrimitive.double } // stable, as Python's sorted
    }

    /** The play sheet's sensitivity flag from the events call (FR-009); a reason that breaks the descriptor rules is replaced by a plain one. */
    fun sensitivity(eventsLook: JsonObject?): JsonObject {
        val a = eventsLook?.get("answer") as? JsonObject
        if (a == null || a.isEmpty()) return sens(false, null, false)
        if ((a["sensitive"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } != true) return sens(false, null, true)
        val raw = (a["sensitive_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        val reason = strip(raw).trimEnd('.')
        val use = if (reason.isEmpty() || english.findDescriptorBanned(reason).isNotEmpty() || PyText.length(reason) > 120) SENSITIVE_DEFAULT else reason
        return sens(true, use, true)
    }

    private fun sens(flag: Boolean, reason: String?, checked: Boolean) = buildJsonObject {
        put("flag", flag); put("reason", reason?.let(::JsonPrimitive) ?: JsonNull); put("checked", checked)
    }

    // --- the play sheet

    private fun JsonObject.num(k: String): Double = getValue(k).jsonPrimitive.double
    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
    private fun lower(s: String) = s.lowercase(Locale.ROOT)
    private fun onsetFrame(c: JsonObject): Int = (c["sound_onset_frame"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int ?: c.getValue("frame").jsonPrimitive.int

    private class Surest(val look: JsonObject, val cell: Int)

    /** The surest sheet that shows the high point, a candidate sheet winning a tie, the earlier sheet a full tie. */
    private fun surest(looks: List<JsonObject>): Surest? {
        var best: Surest? = null
        var key = Pair(-1, false)
        for (look in looks) {
            val a = look["answer"] as? JsonObject ?: continue
            if (a.isEmpty()) continue
            val cell = (a["high_point_cell"] as? JsonPrimitive)?.int ?: 0
            if (cell !in 1..look.getValue("cells").jsonArray.size) continue
            val k = Pair((a["sure"] as? JsonPrimitive)?.int ?: 0, look.str("kind") == "candidate")
            if (best == null || compareValues(k.first, key.first).let { if (it != 0) it > 0 else k.second && !key.second }) { best = Surest(look, cell); key = k }
        }
        return best
    }

    private class Found(val actors: List<JsonObject>, val events: List<JsonObject>, val climax: String)

    private fun events(found: List<JsonObject>, conf: Double, followedText: String, userName: JsonElement?, t: Double, outcome: String, activity: String, dur: Double, descConf: Double, climaxConf: Double): Found {
        if (found.isEmpty()) {
            val actors = ArrayList<JsonObject>()
            if (followedText.isNotEmpty()) actors += buildJsonObject { put("id", "a1"); put("descriptor", followedText); put("role", ""); put("user_name", userName ?: JsonNull); put("followed", true) }
            val a: JsonElement = if (actors.isNotEmpty()) JsonPrimitive("a1") else JsonNull
            val events = listOf(
                buildJsonObject { put("id", "e1"); put("t_start", 0.0); put("t_end", dur); put("actor", a); put("action", activity); put("object", JsonNull); put("outcome", JsonNull); put("confidence", descConf); put("source", "model") },
                buildJsonObject { put("id", "e2"); put("t_start", t); put("t_end", t); put("actor", a); put("action", outcome); put("object", JsonNull); put("outcome", if (outcome.isNotEmpty()) JsonPrimitive(outcome) else JsonNull); put("confidence", climaxConf); put("source", "model") },
            )
            return Found(actors, events, "e2")
        }
        val ids = LinkedHashMap<String, String>()
        for (e in found) { val a = e.str("actor"); if (a.isNotEmpty() && lower(a) !in ids) ids[lower(a)] = "a${ids.size + 1}" }
        val followed = ids[lower(followedText)] ?: ids.values.firstOrNull()
        val actors = ids.map { (k, i) ->
            buildJsonObject {
                put("id", i); put("descriptor", found.first { lower(it.str("actor")) == k }.str("actor")); put("role", "")
                put("user_name", if (i == followed) (userName ?: JsonNull) else JsonNull); put("followed", i == followed)
            }
        }
        val evs = found.mapIndexed { k, e ->
            buildJsonObject {
                put("id", "e${k + 1}"); put("t_start", e.getValue("t_start")); put("t_end", e.getValue("t_end"))
                put("actor", ids[lower(e.str("actor"))]?.let(::JsonPrimitive) ?: JsonNull); put("action", e.getValue("action")); put("object", JsonNull)
                put("outcome", e.getValue("outcome")); put("confidence", conf); put("source", "model")
            }
        }
        fun gap(e: JsonObject) = maxOf(e.num("t_start") - t, t - e.num("t_end"), 0.0)
        // min by (gap, -t_start): the first of equals
        val climax = evs.minWithOrNull(compareBy<JsonObject>({ gap(it) }, { -it.num("t_start") }))!!
        return Found(actors, evs, climax.str("id"))
    }

    /** The play sheet of the fixed sequence (plan 4.4): the description from the surest sheet, a proposed frame snapped to a sound onset, always low confidence; the events from the events call; the S1 candidates and any other high point as other moments. */
    fun buildPlaySheet(runId: String, info: JsonObject, looks: List<JsonObject>, signals: JsonObject, snapS: Double, model: String, eventsLook: JsonObject?): JsonObject {
        val fps = info.num("fps"); val n = info.getValue("frames").jsonPrimitive.int
        val cands = info.getValue("candidates").jsonArray.map { it.jsonObject }
        val answered = looks.filter { (it["answer"] as? JsonObject)?.isNotEmpty() == true }
        val sure = surest(looks)
        val desc: JsonObject?
        var frame: Int
        var method: String
        if (sure != null) {
            desc = sure.look.getValue("answer").jsonObject
            frame = sure.look.getValue("cells").jsonArray[sure.cell - 1].jsonObject.getValue("frame").jsonPrimitive.int; method = "sheets"
        } else {
            desc = answered.maxByOrNull { (it.getValue("answer").jsonObject["sure"] as? JsonPrimitive)?.int ?: 0 }?.getValue("answer")?.jsonObject
            frame = if (cands.isNotEmpty()) onsetFrame(cands[0]) else n / 2
            method = "signal_peak"
        }
        if (method == "sheets") {
            val t0 = frame / fps
            val near = (signals["onsets_t"] as? JsonArray).orEmpty().map { it.jsonPrimitive.double }.filter { it >= t0 - snapS && it <= t0 + 0.1 }
            if (near.isNotEmpty()) { frame = Math.rint(near.min() * fps).toInt(); method = "sheets+sound" }
        }
        frame = minOf(maxOf(frame, 0), n - 1)
        val t = PyMath.round(frame / fps, 3)

        val sureV = ((desc?.get("sure") as? JsonPrimitive)?.int ?: 0) / 10.0
        val descConf = if (desc != null) PyMath.round(sureV, 2) else 0.0
        val climaxConf = if (sure != null) PyMath.round(LOW * sureV, 2) else 0.0
        val user = info.getValue("user").jsonObject
        val userActivity = (user["activity"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotEmpty() }?.content
        val activity = buildJsonObject {
            if (userActivity != null) { put("value", userActivity); put("confidence", 1.0); put("source", "user") }
            else { put("value", desc?.str("activity") ?: ""); put("confidence", descConf); put("source", "model") }
        }
        val outcome = desc?.str("outcome") ?: ""
        val foundEvents = ((eventsLook?.get("events") as? JsonArray).orEmpty()).map { it.jsonObject }
        val evAnswer = eventsLook?.get("answer") as? JsonObject
        val eventsConf = if (foundEvents.isNotEmpty()) PyMath.round(((evAnswer?.get("sure") as? JsonPrimitive)?.int ?: 0) / 10.0, 2) else 0.0
        val f = events(foundEvents, eventsConf, strip(desc?.str("actor") ?: ""), user["player_name"], t, outcome, activity.str("value"), PyMath.round((n - 1) / fps, 3), descConf, climaxConf)

        val others = ArrayList<JsonObject>()
        for (c in cands) {
            val fr = onsetFrame(c)
            val why = if ((c["sound_z"] as? JsonPrimitive)?.double?.let { it > 0 } == true) "motion and sound" else "motion"
            others += buildJsonObject { put("t", PyMath.round(fr / fps, 3)); put("frame", fr); put("reason", "S1 candidate: sudden $why (score ${Checks.pyNumber(c.getValue("score").jsonPrimitive.content)})") }
        }
        for (look in answered) {
            val cells = look.getValue("cells").jsonArray
            val cell = (look.getValue("answer").jsonObject["high_point_cell"] as? JsonPrimitive)?.int ?: 0
            if (cell !in 1..cells.size) continue
            val fr = cells[cell - 1].jsonObject.getValue("frame").jsonPrimitive.int
            val listed = listOf(frame) + others.map { it.getValue("frame").jsonPrimitive.int }
            if (listed.all { Math.abs(fr - it) / fps >= OTHER_MOMENT_GAP_S }) {
                others += buildJsonObject { put("t", PyMath.round(fr / fps, 3)); put("frame", fr); put("reason", "the overview also saw a high point (${look.str("name")})") }
            }
        }
        return buildJsonObject {
            put("schema_version", "1.0"); put("run_id", runId); put("mode", "specific"); put("activity", activity); put("setting", "")
            put("actors", JsonArray(f.actors))
            put("events", JsonArray(f.events))
            put("climax", buildJsonObject {
                put("event_id", f.climax); put("t", t); put("frame", frame); put("method", method); put("confidence", climaxConf)
                put("candidates", JsonArray(others.take(MAX_OTHER_MOMENTS)))
            })
            put("outcome_summary", buildJsonObject { put("value", outcome); put("confidence", descConf); put("source", "model") })
            put("focus_box", JsonNull)
            put("sensitive", sensitivity(eventsLook))
            put("agreement", buildJsonObject { put("passes", 1); put("disagreed_fields", JsonArray(emptyList())); put("escalated", false) })
            put("overall_confidence", minOf(descConf, climaxConf))
            put("confirmed_by_user", false)
            put("models", buildJsonObject { put("analysis", model); put("refine", "none") })
        }
    }

    // --- the rules a play sheet must pass

    /** (field path, banned words) for every model-written text that breaks the descriptor rules (FR-008). */
    fun descriptorBreaches(ps: JsonObject): List<Pair<String, List<String>>> {
        val found = ArrayList<Pair<String, List<String>>>()
        fun look(path: String, text: JsonElement?) {
            val s = (text as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            val words = english.findDescriptorBanned(s)
            if (words.isNotEmpty()) found += path to words
        }
        for (key in listOf("activity", "outcome_summary")) {
            val o = ps[key] as? JsonObject
            if (o?.get("source")?.jsonPrimitive?.content == "model") look("$key/value", o["value"])
        }
        look("setting", ps["setting"])
        for ((i, a) in (ps["actors"] as? JsonArray).orEmpty().withIndex()) { look("actors/$i/descriptor", a.jsonObject["descriptor"]); look("actors/$i/role", a.jsonObject["role"]) }
        for ((i, e) in (ps["events"] as? JsonArray).orEmpty().withIndex()) {
            val o = e.jsonObject
            if (o["source"]?.jsonPrimitive?.content == "model") for (k in listOf("action", "object", "outcome")) look("events/$i/$k", o[k])
        }
        return found
    }

    private fun crossFile(ps: JsonObject, meta: JsonObject): List<String> {
        val errs = ArrayList<String>()
        val dur = meta.num("duration_s")
        val events = ps.getValue("events").jsonArray.map { it.jsonObject }
        val ids = events.map { it.str("id") }.toSet()
        val actors = ps.getValue("actors").jsonArray.map { it.jsonObject }
        val actorIds = actors.map { it.str("id") }.toSet()
        for (e in events) {
            val s = e.num("t_start"); val en = e.num("t_end")
            if (!(0 <= s && s <= en && en <= dur + EPS)) errs += "event ${e.str("id")} lies outside the clip"
            val a = (e["actor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (a != null && a !in actorIds) errs += "event ${e.str("id")} names unknown actor $a"
        }
        val climax = ps.getValue("climax").jsonObject
        if (climax.str("event_id") !in ids) errs += "climax event does not exist"
        if (actors.count { it["followed"]?.jsonPrimitive?.booleanOrNull == true } > 1) errs += "more than one followed actor"
        if (!(0 <= climax.num("t") && climax.num("t") <= dur + EPS)) errs += "climax time outside the clip"
        val fps = meta.num("fps")
        if (Math.abs(climax.num("frame") / fps - climax.num("t")) > 1 / fps) errs += "climax frame and time disagree"
        return errs
    }

    /** The Spotter's last tool: the schema, the cross-file rules and the descriptor rules. Returns the errors; empty means accepted. */
    fun submit(ps: JsonObject, meta: JsonObject): List<String> {
        val schemaErrors = mutableListOf<ValidationError>()
        schema.validate(ps, schemaErrors::add)
        var errors = schemaErrors.map { "${it.objectPath}: ${it.message.take(160)}" }.sorted()
        if (errors.isEmpty()) errors = crossFile(ps, meta)
        return errors + descriptorBreaches(ps).map { (path, w) -> "$path: may not say ${w.toSortedSet().joinToString(", ")} (FR-008)" }
    }

    /** Empties each field that breaks a descriptor rule (there is no agent to rewrite it), flags it for the user and lowers the confidence; returns the paths cleared. */
    fun clearBreaches(ps: JsonObject): Pair<JsonObject, List<String>> {
        val breaches = descriptorBreaches(ps).map { it.first }
        if (breaches.isEmpty()) return ps to emptyList()
        fun set(node: JsonElement, parts: List<String>): JsonElement {
            val key = parts.first()
            if (node is JsonArray) {
                val i = key.toInt()
                return JsonArray(node.mapIndexed { j, c -> if (j == i) set(c, parts.drop(1)) else c })
            }
            val o = node.jsonObject
            if (parts.size == 1) {
                val cleared: JsonElement = if (key in listOf("object", "outcome")) JsonNull else JsonPrimitive("")
                val withConfidence = if ("confidence" in o) o + ("confidence" to JsonPrimitive(0.0)) else o
                return JsonObject(withConfidence + (key to cleared))
            }
            return JsonObject(o + (key to set(o.getValue(key), parts.drop(1))))
        }
        var out: JsonElement = ps
        // the confidence reset belongs to the object holding the field: set() above handles it at the last step
        for (p in breaches) out = set(out, p.split("/"))
        var o = out.jsonObject
        val ag = o.getValue("agreement").jsonObject
        val fields = ag.getValue("disagreed_fields").jsonArray.map { it.jsonPrimitive.content }.toMutableList()
        for (p in breaches) if (p !in fields) fields += p
        o = JsonObject(o + mapOf(
            "agreement" to JsonObject(ag + ("disagreed_fields" to JsonArray(fields.map(::JsonPrimitive)))),
            "overall_confidence" to JsonPrimitive(minOf(o.num("overall_confidence"), LOW)),
        ))
        return o to breaches
    }

    /** S3's flow on a trace: build, submit, empty a descriptor breach and submit again. */
    class Made(val playSheet: JsonObject, val errors: List<String>, val cleared: List<String>) { val accepted get() = errors.isEmpty() }

    fun make(runId: String, info: JsonObject, looks: List<JsonObject>, eventsLook: JsonObject?, signals: JsonObject, snapS: Double, model: String, meta: JsonObject): Made {
        var sheet = buildPlaySheet(runId, info, looks, signals, snapS, model, eventsLook)
        var errors = submit(sheet, meta)
        var cleared = emptyList<String>()
        if (errors.isNotEmpty()) {
            val (s2, c) = clearBreaches(sheet)
            sheet = s2; cleared = c
            if (cleared.isNotEmpty()) errors = submit(sheet, meta)
        }
        return Made(sheet, errors, cleared)
    }

    companion object {
        const val UNIFORM_FRAMES = 18
        const val CANDIDATE_HALF_S = 1.5
        const val SHEET_CANDIDATES = 3
        const val LOW = 0.6
        const val OTHER_MOMENT_GAP_S = 0.5
        const val MAX_OTHER_MOMENTS = 6
        const val EPS = 0.002
        const val WHERE_CANDIDATE = "These frames cover about three seconds around a moment of sudden motion or sound."
        const val SENSITIVE_DEFAULT = "the clip may show someone hurt or upset"
    }
}
