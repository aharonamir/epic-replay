package app.epicreplay.core.storyboard

import app.epicreplay.core.PyMath
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

/** `pc/src/epic_replay/stages/s4_storyboard.py` (plan 4.6): the output timeline from the play sheet and the clip, by code. The rules are written in that file's header; the arithmetic here is the same, in the same order. */
object Storyboard {
    private const val TITLE_S = 1.0
    private const val TRANSITION_S = 0.4
    private const val ENDCARD_S = 2.5
    private const val REPLAY_SPEED = 0.25
    private const val BEFORE_S = 1.5
    private const val AFTER_S = 1.0
    private const val WIDEST_S = 3.5
    private const val TRIM_OVER_S = 20.0
    private const val TRIM_BEFORE_S = 10.0
    private const val TRIM_AFTER_S = 3.0
    private const val ZOOM_SCALE = 1.25
    private const val ZOOM_EASE_S = 1.0
    private const val LOWER_THIRD_AT_S = 1.0
    private const val LOWER_THIRD_S = 4.0
    private const val KEY_ROOM_S = 1.5 + 1.2

    private fun r(t: Double): Double = PyMath.round(t + 0.0, 3)
    private fun JsonElement.d(): Double = jsonPrimitive.double

    /** Python truthiness of a JSON value for `if ps.get("focus_box")`. */
    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toDouble() != 0.0
    }

    private class Part(val kind: String, val length: Double, val src: Pair<Double, Double>?, val speed: Double?, val freeze: Double?, val zoom: Boolean)

    fun build(ps: JsonObject, meta: JsonObject, minOutputS: Double): JsonObject {
        val climax = ps.getValue("climax").jsonObject
        val t = climax.getValue("t").d()
        val dur = meta.getValue("duration_s").d()
        val fpsElement = meta.getValue("fps")
        val frame = 1.0 / fpsElement.d()
        var liveIn = 0.0
        var liveOut = dur
        if (dur > TRIM_OVER_S) { liveIn = maxOf(0.0, t - TRIM_BEFORE_S); liveOut = minOf(dur, t + TRIM_AFTER_S) }
        liveIn = r(liveIn); liveOut = r(liveOut)

        fun window(extra: Double): Pair<Double, Double> {
            var lo = t - BEFORE_S - extra / 2
            var hi = t + AFTER_S + extra / 2
            if (lo < liveIn) { hi += liveIn - lo; lo = liveIn }
            if (hi > liveOut) { lo = maxOf(liveIn, lo - (hi - liveOut)); hi = liveOut }
            return r(lo) to r(hi)
        }
        fun hold(wOut: Double) = r(maxOf(0.0, KEY_ROOM_S - (wOut - t) / REPLAY_SPEED))
        fun total(wIn: Double, wOut: Double): Double {
            val fixed = TITLE_S + TRANSITION_S + ENDCARD_S
            return fixed + (liveOut - liveIn) + (wOut - wIn) / REPLAY_SPEED + hold(wOut)
        }

        var (wIn, wOut) = window(0.0)
        val short = minOutputS - total(wIn, wOut)
        if (short > 0) { val w = window(minOf(WIDEST_S - BEFORE_S - AFTER_S, short * REPLAY_SPEED)); wIn = w.first; wOut = w.second }
        val holdS = hold(wOut)
        val endcardS = r(ENDCARD_S + maxOf(0.0, minOutputS - total(wIn, wOut)))

        val events = (ps["events"] as? JsonArray).orEmpty().associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        val eventId = (climax["event_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val outcomeT = eventId?.let { events[it] }?.jsonObject?.get("t_end")?.d() ?: t
        val lastFrame = r(liveOut - frame)
        val zoom = truthy(ps["focus_box"])

        val parts = listOf(
            Part("title", TITLE_S, null, null, liveIn, false),
            Part("live", liveOut - liveIn, liveIn to liveOut, 1.0, null, false),
            Part("transition", TRANSITION_S, null, null, lastFrame, false),
            Part("replay", (wOut - wIn) / REPLAY_SPEED + holdS, wIn to wOut, REPLAY_SPEED, null, zoom),
            Part("endcard", endcardS, null, null, r(minOf(maxOf(outcomeT, liveIn), lastFrame)), false),
        )
        val segments = ArrayList<JsonObject>()
        var at = 0.0
        for ((k, p) in parts.withIndex()) {
            val end = r(at + p.length)
            val startAt = at
            segments += buildJsonObject {
                put("id", "s${k + 1}")
                put("kind", p.kind)
                put("timeline_start", startAt)
                put("timeline_end", end)
                put("source", p.src?.let { buildJsonObject { put("in_s", it.first); put("out_s", it.second) } } ?: JsonNull)
                put("speed", p.speed?.let(::JsonPrimitive) ?: JsonNull)
                put("freeze_at_s", p.freeze?.let(::JsonPrimitive) ?: JsonNull)
                put("zoom", if (p.zoom) buildJsonObject { put("scale", ZOOM_SCALE); put("ease_in_s", ZOOM_EASE_S) } else JsonNull)
                if (p.kind == "replay" && holdS != 0.0) put("hold_s", holdS)
            }
            at = end
        }
        val seg = segments.associateBy { it.getValue("kind").jsonPrimitive.content }
        val live = seg.getValue("live"); val replay = seg.getValue("replay")
        val ltStart = r(live.getValue("timeline_start").d() + LOWER_THIRD_AT_S)
        val overlays = listOf(
            buildJsonObject { put("type", "ai_label"); put("start", 0.0); put("end", at) },
            buildJsonObject { put("type", "title"); put("start", 0.0); put("end", seg.getValue("title").getValue("timeline_end").d()) },
            buildJsonObject { put("type", "lower_third"); put("start", ltStart); put("end", r(minOf(ltStart + LOWER_THIRD_S, live.getValue("timeline_end").d()))) },
            buildJsonObject { put("type", "replay_bug"); put("start", replay.getValue("timeline_start").d()); put("end", replay.getValue("timeline_end").d()) },
            buildJsonObject { put("type", "end_card"); put("start", seg.getValue("endcard").getValue("timeline_start").d()); put("end", at) },
        )
        return buildJsonObject {
            put("schema_version", "1.0")
            put("run_id", ps.getValue("run_id"))
            put("template", "classic")
            put("fps", fpsElement)
            put("width", meta.getValue("width"))
            put("height", meta.getValue("height"))
            put("duration_s", at)
            put("segments", JsonArray(segments))
            put("overlays", JsonArray(overlays))
            put("markers", buildJsonObject {
                put("climax_live_t", r(live.getValue("timeline_start").d() + (t - liveIn)))
                put("climax_replay_t", r(replay.getValue("timeline_start").d() + (t - wIn) / REPLAY_SPEED))
            })
        }
    }
}
