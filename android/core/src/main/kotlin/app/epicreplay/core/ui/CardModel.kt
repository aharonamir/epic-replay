package app.epicreplay.core.ui

import app.epicreplay.core.card.PlayCard
import app.epicreplay.core.mix.Mix
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An event as the card lists it: its sentence ("action · outcome"), whether it is marked "Check this", and what the user said. */
class CardEvent(val id: String, val text: String, val toCheck: Boolean, val decision: Boolean?)

/** Where the card is: the card itself, or the "Not at all" view. */
enum class CardView { CARD, REJECT }

/** The play card's state (ui.md section 3): the frame the key moment is on, what the user has answered, and the edits typed in "Fix something". */
data class CardState(
    val frame: Int, val lastFrame: Int, val fps: Double, val keyMomentAnswered: Boolean, val decisions: Map<String, Boolean>, val fixing: Boolean, val activity: String, val outcome: String,
    val originalActivity: String, val originalOutcome: String, val view: CardView, val needsFineCheck: Boolean, val fineAnswered: Boolean,
) {
    val seconds: Double get() = Math.round(frame / fps * 100) / 100.0
}

/** What the user's answer to the card sends: the key moment, the edits and the context (`PlayCard.answer`'s inputs), and whether the narration is generic. */
class CardAnswerInput(val frame: Int, val edits: JsonObject, val generic: Boolean)

/** The card's behaviour as functions of its state (`web/static/page.js`, `web/templates/_card.html`), so a screen only draws it. */
object CardModel {
    private fun str(e: JsonElement?) = (e as? JsonObject)?.get("value")?.jsonPrimitive?.content ?: ""

    fun start(ps: JsonObject, meta: JsonObject): CardState {
        val c = ps.getValue("climax").jsonObject
        val sens = ps["sensitive"] as? JsonObject
        val unchecked = sens?.get("checked")?.let { it is JsonPrimitive && !it.isString && it.content == "false" } == true
        return CardState(
            c.getValue("frame").jsonPrimitive.int, PlayCard.lastFrame(meta), meta.getValue("fps").jsonPrimitive.double, false, emptyMap(), false, str(ps["activity"]), str(ps["outcome_summary"]),
            str(ps["activity"]), str(ps["outcome_summary"]), CardView.CARD, unchecked, false,
        )
    }

    fun events(ps: JsonObject, s: CardState): List<CardEvent> {
        val check = PlayCard.toCheck(ps).toSet()
        return ps.getValue("events").jsonArray.map { it.jsonObject }.map { e ->
            val outcome = (e["outcome"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val id = e.getValue("id").jsonPrimitive.content
            CardEvent(id, e.getValue("action").jsonPrimitive.content + (if (!outcome.isNullOrEmpty()) " · $outcome" else ""), id in check, s.decisions[id])
        }
    }

    /** The other candidate moments of the climax (not the one the card opened on), as frame and reason. */
    fun otherMoments(ps: JsonObject): List<Pair<Int, String>> {
        val c = ps.getValue("climax").jsonObject
        val frame = c.getValue("frame").jsonPrimitive.int
        return c["candidates"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.getValue("frame").jsonPrimitive.int != frame }.map { it.getValue("frame").jsonPrimitive.int to ((it["reason"] as? JsonPrimitive)?.content ?: "") }
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

    fun nudge(s: CardState, by: Int): CardState = s.copy(frame = (s.frame + by).coerceIn(0, s.lastFrame), keyMomentAnswered = true)
    fun pick(s: CardState, frame: Int): CardState = s.copy(frame = frame.coerceIn(0, s.lastFrame), keyMomentAnswered = true)
    fun thisOne(s: CardState): CardState = s.copy(keyMomentAnswered = true)
    fun decide(s: CardState, id: String, keep: Boolean): CardState = s.copy(decisions = s.decisions + (id to keep))
    fun fine(s: CardState): CardState = s.copy(fineAnswered = true)
    fun toggleFixing(s: CardState): CardState = s.copy(fixing = !s.fixing)
    fun editActivity(s: CardState, text: String) = s.copy(activity = text)
    fun editOutcome(s: CardState, text: String) = s.copy(outcome = text)
    fun reject(s: CardState): CardState = s.copy(view = CardView.REJECT)
    fun back(s: CardState): CardState = s.copy(view = CardView.CARD)

    /** Why "Yes, that's it" is not yet possible, in the words of the page, or null when it is. */
    fun whyNot(ps: JsonObject, s: CardState, words: PageWords): String? = when {
        s.needsFineCheck && !s.fineAnswered -> words.card("why_check")
        !s.keyMomentAnswered -> words.card("why")
        PlayCard.toCheck(ps).any { it !in s.decisions } -> words.card("why_items")
        else -> null
    }

    /** What an answer of "Yes, that's it" (or "Narrate without saying what happened", [generic]) sends: the decisions made and the sentences changed. */
    fun answerInput(s: CardState, generic: Boolean = false): CardAnswerInput {
        val edits = buildJsonObject {
            if (s.decisions.isNotEmpty()) put("events", JsonArray(s.decisions.map { (id, keep) -> buildJsonObject { put("id", id); put("keep", keep) } }))
            if (s.activity.trim() != s.originalActivity) put("activity", s.activity)
            if (s.outcome.trim() != s.originalOutcome) put("outcome_summary", s.outcome)
        }
        return CardAnswerInput(s.frame, edits, generic)
    }
}
