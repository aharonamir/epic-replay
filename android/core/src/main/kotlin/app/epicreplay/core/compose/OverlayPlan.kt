package app.epicreplay.core.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One overlay: its kind, the words (by field), the window on the timeline and how long it takes to fade in and out. */
class OverlaySpec(val kind: String, val text: Map<String, String>, val start: Double, val end: Double, val fadeS: Double) {
    /** How much of it shows at [t]: a linear fade in over [fadeS] from the start and out over [fadeS] to the end (FFmpeg's `fade` on the alpha). */
    fun alpha(t: Double): Double {
        val up = if (fadeS <= 0) 1.0 else minOf(1.0, maxOf(0.0, (t - start) / fadeS))
        val down = if (fadeS <= 0) 1.0 else minOf(1.0, maxOf(0.0, (end - t) / fadeS))
        return minOf(up, down)
    }
}

/** What S10 draws over the video and when (`s10_compose.overlay_specs`): in drawing order the title, the replay bug, the end card and the AI label. */
object OverlayPlan {
    const val FADE_S = 0.2
    const val AI_LABEL = "AI COMMENTARY"
    private val ORDER = listOf("title", "replay_bug", "end_card", "ai_label")

    private fun str(e: JsonElement?, default: String): String = (e as? JsonPrimitive)?.takeIf { e !is JsonNull }?.content ?: default

    /** Dark or white text, whichever reads better on the [accent] colour (`graphics/render.py on_accent`, as contracts/design.md): the contrast with either, by WCAG relative luminance. */
    fun onAccent(accent: String): String {
        fun lin(i: Int): Double { val c = accent.trimStart('#').substring(i, i + 2).toInt(16) / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        val lum = 0.2126 * lin(0) + 0.7152 * lin(2) + 0.0722 * lin(4)
        return if ((lum + 0.05) / 0.05 > 1.05 / (lum + 0.05)) "#121416" else "#FFFFFF"
    }

    fun specs(sb: JsonObject, script: JsonObject, pack: JsonObject): List<OverlaySpec> {
        val labels = pack.getValue("script").jsonObject.getValue("labels").jsonObject
        val headline = str(script["headline"], "")
        val all = mapOf(
            "title" to mapOf("headline" to headline),
            "replay_bug" to mapOf("label" to str(labels["replay_bug"], "REPLAY")),
            "end_card" to mapOf("headline" to headline, "kicker" to str(labels["end_card_kicker"], "")),
            "ai_label" to mapOf("label" to str(labels["ai_label"], "").ifEmpty { AI_LABEL }),
        )
        val spans = sb.getValue("overlays").jsonArray.associateBy { it.jsonObject.getValue("type").jsonPrimitive.content }
        return ORDER.mapNotNull { kind ->
            val text = all.getValue(kind)
            val span = spans[kind]?.jsonObject
            if (span == null || text.values.none { it.isNotEmpty() }) return@mapNotNull null
            val s = span.getValue("start").jsonPrimitive.double; val e = span.getValue("end").jsonPrimitive.double
            OverlaySpec(kind, text, s, e, minOf(FADE_S, (e - s) / 3))
        }
    }
}
