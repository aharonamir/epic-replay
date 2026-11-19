package app.epicreplay.core.style

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

class NoRoom(message: String) : Exception(message)

/** `pc/src/epic_replay/styles/slots.py`: script slots from the storyboard and the pack (plan 4.7), the same arithmetic in the same order. */
object Slots {
    private const val GAP_S = 0.1
    private const val MIN_SLOT_S = 1.2
    private const val MIN_KEY_SLOT_S = 0.8
    private const val EPS = 1e-6
    private val KEY_ROLES = setOf("climax", "replay_climax")

    private class Spec(val role: String, val segment: String, val kind: String, val offsetS: Double, val relativeTo: String, val longestS: Double, val endsWithSegment: Boolean = false)

    private val SPECS = listOf(
        Spec("opening", "live", "start_at", 0.3, "segment", 4.0),
        Spec("build_up", "live", "start_at", -2.0, "climax_live_t", 1.6),
        Spec("climax", "live", "hit_at", 0.0, "climax_live_t", 2.0),
        Spec("reaction", "live", "start_at", 2.25, "climax_live_t", 1.6, endsWithSegment = true),
        Spec("replay_watch", "replay", "start_at", 0.5, "segment", 3.5),
        Spec("replay_climax", "replay", "hit_at", 0.0, "climax_replay_t", 3.5, endsWithSegment = true),
        Spec("sign_off", "endcard", "start_at", 0.3, "segment", 1.6),
    )

    fun build(storyboard: JsonObject, pack: JsonObject, climaxEventId: String?): JsonArray {
        val voice = pack.getValue("voice").jsonObject
        val wps = voice.getValue("words_per_second").jsonPrimitive.double
        val startup = voice.getValue("startup_s").jsonPrimitive.double
        val delay = voice.getValue("reaction_delay_s").jsonPrimitive.double
        val segs = LinkedHashMap<String, JsonObject>()
        for (s in storyboard.getValue("segments").jsonArray) segs.getOrPut(s.jsonObject.getValue("kind").jsonPrimitive.content) { s.jsonObject }
        val markers = storyboard.getValue("markers").jsonObject.mapValues { PyMath.round(it.value.jsonPrimitive.double, 3) }
        val videoEnd = storyboard.getValue("duration_s").jsonPrimitive.double

        fun startOf(sp: Spec): Double {
            if (sp.kind == "hit_at") return markers.getValue(sp.relativeTo) + delay
            val base = if (sp.relativeTo == "segment") segs.getValue(sp.segment).getValue("timeline_start").jsonPrimitive.double else markers.getValue(sp.relativeTo)
            return base + sp.offsetS
        }

        val specs = SPECS.filter { it.segment in segs }
        val slots = ArrayList<JsonObject>()
        var prevEnd = Double.NEGATIVE_INFINITY
        for ((i, sp) in specs.withIndex()) {
            val seg = segs.getValue(sp.segment)
            var start = startOf(sp)
            if (sp.kind == "start_at") start = maxOf(start, seg.getValue("timeline_start").jsonPrimitive.double, prevEnd + GAP_S)
            start = PyMath.round(start, 3)
            var limit = minOf(start + sp.longestS, videoEnd)
            val nxt = specs.drop(i + 1).firstOrNull { it.kind == "hit_at" }
            if (nxt != null) limit = minOf(limit, startOf(nxt) - GAP_S)
            if (sp.endsWithSegment) limit = minOf(limit, seg.getValue("timeline_end").jsonPrimitive.double)
            val dur = Math.floor((limit - start) * 1000 + EPS) / 1000 // never rounded up past a limit
            if (sp.role in KEY_ROLES && dur < MIN_KEY_SLOT_S - EPS) throw NoRoom("the ${sp.role} slot would last ${"%.3f".format(dur)} s, under $MIN_KEY_SLOT_S s")
            val words = Math.floor((dur - startup) * wps + EPS).toInt()
            if (sp.role !in KEY_ROLES && (dur < MIN_SLOT_S - EPS || words < 1)) continue
            prevEnd = PyMath.round(start + dur, 3)
            val hit = sp.kind == "hit_at"
            slots += buildJsonObject {
                put("segment_id", seg.getValue("id"))
                put("role", sp.role)
                put("anchor", buildJsonObject {
                    put("type", sp.kind)
                    put("timeline_t", if (hit) markers.getValue(sp.relativeTo) else start)
                    put("event_id", if (hit && climaxEventId != null) JsonPrimitive(climaxEventId) else JsonNull)
                })
                put("max_duration_s", dur)
                put("max_words", maxOf(1, words))
            }
        }
        return JsonArray(slots.mapIndexed { k, s -> JsonObject(mapOf("id" to JsonPrimitive("l${k + 1}")) + s) })
    }
}
