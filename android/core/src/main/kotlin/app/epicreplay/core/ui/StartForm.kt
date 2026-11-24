package app.epicreplay.core.ui

import app.epicreplay.core.style.StylePacks
import app.epicreplay.core.text.PyText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A narrator as the start screen lists it: from the style pack (`shared/styles/`), never by its id. */
class NarratorCard(val id: String, val name: String, val description: String, val caption: String, val accent: String, val offersMandarin: Boolean)

/** What the user has set on the start screen. */
data class StartInput(val clipName: String?, val narrator: String, val language: String, val playerName: String, val removeOriginalSound: Boolean, val consent: Boolean)

/** The start screen's rules (ui.md section 1): the narrators in the packs' order, the default preselected, "Make my replay" enabled by a valid clip and the consent, and the user context a run starts with (`web/app.py`). */
object StartForm {
    const val MAX_NAME = 40

    fun narrators(styles: StylePacks): List<NarratorCard> = styles.packs.values.sortedBy { (it["order"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE }.map { p ->
        NarratorCard(
            p.getValue("id").jsonPrimitive.content, p.getValue("name").jsonPrimitive.content, p.getValue("description").jsonPrimitive.content,
            ((p["script"] as? JsonObject)?.get("example_lines") as? JsonObject)?.get("climax")?.jsonPrimitive?.content ?: "",
            (p["graphics"] as? JsonObject)?.get("accent")?.jsonPrimitive?.content ?: "#FFD23F", (p["languages"] as? JsonObject)?.containsKey("zh") == true,
        )
    }

    fun defaultNarrator(styles: StylePacks): String = styles.default().getValue("id").jsonPrimitive.content

    /** The language the run is made in: Mandarin only for a narrator that offers it, else English. */
    fun language(card: NarratorCard, asked: String): String = if (asked == "zh" && card.offersMandarin) "zh" else "en"

    /** A player's name as the PC takes it: white space collapsed, at most 40 characters, empty is none. */
    fun cleanName(typed: String): String? = PyText.take(PyText.splitWords(typed).joinToString(" "), MAX_NAME).ifEmpty { null }

    fun canStart(i: StartInput): Boolean = i.clipName != null && i.consent

    /** The run's `user_context.json` (the PC's fields): [consent] is the record the user's tick makes. */
    fun userContext(i: StartInput, card: NarratorCard, consent: JsonObject): JsonObject = buildJsonObject {
        put("consent", consent); put("keep_for_demo", false); put("style", card.id); put("language", language(card, i.language)); put("activity", JsonNull)
        put("player_name", cleanName(i.playerName)?.let { JsonPrimitive(it) } ?: JsonNull); put("team_home", JsonNull); put("team_away", JsonNull); put("score", JsonNull)
        put("keep_original_audio", !i.removeOriginalSound); put("express", true); put("description", JsonNull)
    }
}
