package app.epicreplay.core.card

import app.epicreplay.core.PyMath
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.text.PyText
import app.epicreplay.core.voice.ElevenLabs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The user's answers at the play card applied to the play sheet and the user context. */
class CardAnswer(val playSheet: JsonObject, val userContext: JsonObject)

/**
 * The play card's rules (`flow/runner.py`: `to_check`, `nearest_event`, `picked`, `answer`, `rest_cost_usd`; spec 001 ui.md section 3, spec 004 T425): which events the card
 * asks about, what the key moment and the corrections do to the play sheet and the context, and what the rest of the run costs. A refused answer throws
 * [IllegalArgumentException] with the PC's message and nothing is changed.
 */
object PlayCard {
    const val LOW = 0.7 // below this an event is an item to check
    private val CONTEXT = listOf("player_name", "team_home", "team_away", "score")
    private val TEXT_MAX = listOf("activity" to 80, "outcome_summary" to 160)
    private const val CHARS_PER_WORD = 6.5

    private fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(LinkedHashMap(this).also { m -> for ((k, v) in pairs) m[k] = v })
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()
    private fun pyStrip(s: String): String { var a = 0; var b = s.length; while (a < b && PyText.isSpace(s[a])) a++; while (b > a && PyText.isSpace(s[b - 1])) b--; return s.substring(a, b) }

    fun lastFrame(meta: JsonObject): Int = Math.rint(meta.getValue("duration_s").jsonPrimitive.double * meta.getValue("fps").jsonPrimitive.double).toInt() - 1

    /** The events the card asks about: written by the model and below the highlight line. Each must be kept or removed before the card is confirmed (FR-010). */
    fun toCheck(ps: JsonObject): List<String> = ps.getValue("events").jsonArray.map { it.jsonObject }.filter { str(it["source"]) != "user" && it.getValue("confidence").jsonPrimitive.double < LOW }.map { it.getValue("id").jsonPrimitive.content }

    /** The event that holds the key moment, or the nearest one (plan.md 4.4); on a tie the later start. */
    fun nearestEvent(ps: JsonObject): String {
        val t = ps.getValue("climax").jsonObject.getValue("t").jsonPrimitive.double
        fun d(e: JsonObject) = maxOf(e.getValue("t_start").jsonPrimitive.double - t, t - e.getValue("t_end").jsonPrimitive.double, 0.0)
        val best = ps.getValue("events").jsonArray.map { it.jsonObject }.minWithOrNull(compareBy<JsonObject> { d(it) }.thenBy { -it.getValue("t_start").jsonPrimitive.double })!!
        return best.getValue("id").jsonPrimitive.content
    }

    /** The user's key moment written into the play sheet: the climax with method `user`, and the sheet confirmed. */
    fun picked(ps: JsonObject, meta: JsonObject, frame: Int): JsonObject {
        require(frame in 0..lastFrame(meta)) { "the clip has frames 0 to ${lastFrame(meta)}" }
        val fps = meta.getValue("fps").jsonPrimitive.double
        val c = ps.getValue("climax").jsonObject.with("frame" to JsonPrimitive(frame), "t" to JsonPrimitive(PyMath.round(frame / fps, 3)), "method" to JsonPrimitive("user"), "confidence" to JsonPrimitive(1.0))
        return ps.with("climax" to c, "confirmed_by_user" to JsonPrimitive(true))
    }

    /** `answer`: the key moment and the corrections, each edited field with `source: user`. In generic mode (FR-012) the sheet keeps what the model saw, marked `mode: generic`. */
    fun answer(playSheet: JsonObject, userContext: JsonObject, meta: JsonObject, frame: Int, edits: JsonObject, context: JsonObject, generic: Boolean = false): CardAnswer {
        var ps = picked(playSheet, meta, frame)
        if (generic) ps = ps.with("mode" to JsonPrimitive("generic"))
        var uc = userContext
        val known = ps.getValue("events").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        val keep = HashMap<String, Boolean>()
        for (x in (edits["events"] as? JsonArray) ?: JsonArray(emptyList())) {
            val o = x as? JsonObject
            val id = str(o?.get("id")); val k = (o?.get("keep") as? JsonPrimitive)?.takeIf { !it.isString && (it.content == "true" || it.content == "false") }?.content?.toBoolean()
            require(o != null && id != null && id in known && k != null) { "each event edit is {id, keep} with an event of this play sheet" }
            keep[id] = k
        }
        var events = ps.getValue("events").jsonArray.map { it.jsonObject }.filter { keep[it.getValue("id").jsonPrimitive.content] ?: true }
        require(events.isNotEmpty()) { "keep at least one event" }
        events = events.map { if (keep[it.getValue("id").jsonPrimitive.content] == true) it.with("source" to JsonPrimitive("user")) else it }
        ps = ps.with("events" to JsonArray(events))
        if (ps.getValue("climax").jsonObject.getValue("event_id").jsonPrimitive.content !in events.map { it.getValue("id").jsonPrimitive.content }) {
            ps = ps.with("climax" to ps.getValue("climax").jsonObject.with("event_id" to JsonPrimitive(nearestEvent(ps))))
        }
        for ((k, most) in TEXT_MAX) {
            if (k !in edits) continue
            val v = str(edits[k])?.let(::pyStrip)
            require(v != null && v.isNotEmpty() && PyText.length(v) <= most) { "$k is a sentence of 1 to $most characters" }
            ps = ps.with(k to JsonObject(linkedMapOf("value" to JsonPrimitive(v), "confidence" to JsonPrimitive(1.0), "source" to JsonPrimitive("user"))))
            if (k == "outcome_summary") {
                val id = ps.getValue("climax").jsonObject.getValue("event_id").jsonPrimitive.content
                ps = ps.with("events" to JsonArray(ps.getValue("events").jsonArray.map { e -> if (e.jsonObject.getValue("id").jsonPrimitive.content == id) e.jsonObject.with("outcome" to JsonPrimitive(v), "source" to JsonPrimitive("user")) else e }))
            }
        }
        if ("followed_actor" in edits) {
            val a = str(edits["followed_actor"])
            val actors = ps.getValue("actors").jsonArray.map { it.jsonObject }
            require(a != null && a in actors.map { it.getValue("id").jsonPrimitive.content }) { "followed_actor is an actor of this play sheet" }
            ps = ps.with("actors" to JsonArray(actors.map { it.with("followed" to JsonPrimitive(it.getValue("id").jsonPrimitive.content == a)) }))
        }
        for ((k, v) in context) {
            val ok = k in CONTEXT && (v is JsonNull || (v is JsonPrimitive && v.isString && PyText.length(v.content) <= 40))
            require(ok) { "context takes ${CONTEXT.joinToString(", ")}, each at most 40 characters" }
            uc = uc.with(k to ((str(v)?.let(::pyStrip)?.takeIf { it.isNotEmpty() })?.let { JsonPrimitive(it) } ?: JsonNull))
        }
        if ("player_name" in context) {
            ps = ps.with("actors" to JsonArray(ps.getValue("actors").jsonArray.map { x ->
                val o = x.jsonObject
                if ((o["followed"] as? JsonPrimitive)?.content == "true") o.with("user_name" to (uc["player_name"] ?: JsonNull)) else o
            }))
        }
        return CardAnswer(ps, uc)
    }

    /** What the run costs after the card (`rest_cost_usd`): the Commentator's ceiling plus the voice for as many words as the clip's length holds at the pack's rate. [text] is the text profile (null: it costs nothing). */
    fun restCostUsd(meta: JsonObject, pack: JsonObject, text: Profile?, maxIterations: Int = 6): Double {
        val words = meta.getValue("duration_s").jsonPrimitive.double * pack.getValue("voice").jsonObject.getValue("words_per_second").jsonPrimitive.double
        val perWord = if (str(pack["language"]) == "zh") 1.0 else CHARS_PER_WORD
        val (pin, pout) = (text as? ServerProfile)?.let { it.priceInPerM to it.priceOutPerM } ?: (0.0 to 0.0)
        val agent = PyMath.round(maxIterations * (5000 * pin + 1500 * pout) / 1e6, 6)
        return PyMath.round(agent + words * perWord * ElevenLabs.USD_PER_CHAR, 2)
    }
}
