package app.epicreplay.core.compose

import app.epicreplay.core.PyMath
import app.epicreplay.core.audio.Landed
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What S10 measures on the finished file (`stages/s10_compose.py onsets`): for each line with a target, where its take landed in the video's audio (found by
 * correlation near its placed start) and, with the take's measured hit offset, where its key word falls against the target.
 */
object ComposeMeasure {
    /** [out] is the video's audio as mono; [takes] the fitted take of each line (mono, by id) at the same rate. */
    fun onsets(voice: JsonObject, takes: Map<String, FloatArray>, out: FloatArray, sampleRate: Int): List<JsonObject> {
        val found = ArrayList<JsonObject>()
        for (l in voice.getValue("lines").jsonArray.map { it.jsonObject }) {
            if ("target_s" !in l) continue
            if (app.epicreplay.core.mix.Mix.truthy(l["dropped"])) continue
            val take = takes[l.getValue("id").jsonPrimitive.content] ?: continue
            val start = Landed.landed(take, out, l.getValue("timeline_start").jsonPrimitive.double, sampleRate)
            val target = l.getValue("target_s").jsonPrimitive.double
            val measured = PyMath.round(start + l.getValue("hit_offset_s").jsonPrimitive.double, 3)
            found += buildJsonObject {
                put("line_id", l.getValue("id")); put("role", l.getValue("role")); put("target_s", target); put("measured_s", measured)
                put("error_ms", Math.round((measured - target) * 1000).toInt())
            }
        }
        return found
    }

    /** The timing checks of a finished export: the picture is the storyboard's length to the frame, the sound as long, every key line within [toleranceMs] of its target. Empty when all hold. */
    fun problems(sb: JsonObject, frames: Int, audioS: Double, hits: List<JsonObject>, width: Int? = null, height: Int? = null, toleranceMs: Int = 150): List<String> {
        val fps = sb.getValue("fps").jsonPrimitive.double
        val duration = sb.getValue("duration_s").jsonPrimitive.double
        val out = ArrayList<String>()
        val expected = Math.round(duration * fps).toInt()
        if (frames != expected) out += "the video has $frames frames, the storyboard $expected"
        if (Math.abs(audioS - duration) > 0.1) out += "the sound is ${PyMath.round(audioS, 3)} s, the storyboard ${duration} s"
        val w = sb.getValue("width").jsonPrimitive.content.toInt(); val h = sb.getValue("height").jsonPrimitive.content.toInt()
        if (width != null && height != null && (width != w || height != h)) out += "the video is ${width}x$height, the storyboard ${w}x$h"
        for (hit in hits) if (Math.abs(hit.getValue("error_ms").jsonPrimitive.content.toInt()) > toleranceMs) out += "line ${hit.getValue("line_id").jsonPrimitive.content} lands ${hit.getValue("error_ms").jsonPrimitive.content} ms from its target"
        return out
    }
}
