package app.epicreplay.core.visuals

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

/**
 * What S9 does with each storyboard segment, in frames (`s9_visuals.plan` and `media/ffmpeg.py`): the source range, the speed,
 * the zoom crop, or the frame to hold, and how many frames the segment has. Python rounds half to even and so does `rint`.
 */
object VisualsPlan {
    /** The frame index at source or timeline time [t]. */
    fun frames(t: Double, fps: Double): Int = Math.rint(t * fps + 1e-9).toInt()

    private fun even(v: Double): Int = maxOf(2, v.toInt() / 2 * 2)

    /** (width, height, x, y) of a crop of 1/[scale] of the picture, centred on the box (fractions of the frame) as far as the edges allow. */
    fun zoomCrop(scale: Double, box: JsonObject, width: Int, height: Int): IntArray {
        val cw = even(width / scale); val ch = even(height / scale)
        val cx = (box.getValue("x").jsonPrimitive.double + box.getValue("w").jsonPrimitive.double / 2) * width
        val cy = (box.getValue("y").jsonPrimitive.double + box.getValue("h").jsonPrimitive.double / 2) * height
        val x = minOf(maxOf(0, Math.rint(cx - cw / 2.0).toInt()), width - cw)
        val y = minOf(maxOf(0, Math.rint(cy - ch / 2.0).toInt()), height - ch)
        return intArrayOf(cw, ch, x, y)
    }

    private fun hasBox(e: JsonElement?) = e is JsonObject && e.isNotEmpty()

    fun plan(seg: JsonObject, sb: JsonObject, focusBox: JsonElement?, srcFrames: Int): JsonObject {
        val fps = sb.getValue("fps").jsonPrimitive.double
        val n = frames(seg.getValue("timeline_end").jsonPrimitive.double, fps) - frames(seg.getValue("timeline_start").jsonPrimitive.double, fps)
        val source = seg["source"] as? JsonObject
        val speed = seg["speed"]
        val method: JsonElement = if (source == null) JsonNull else JsonPrimitive(if (speed!!.jsonPrimitive.double < 1.0) "blend" else "native")
        val hold = seg["hold_s"]?.takeIf { it !is JsonNull } ?: JsonPrimitive(0.0)
        return buildJsonObject {
            put("id", seg.getValue("id")); put("method", method); put("frames", n); put("hold_s", hold)
            if (source == null) {
                put("freeze_frame", minOf(frames(seg.getValue("freeze_at_s").jsonPrimitive.double, fps), srcFrames - 1))
                return@buildJsonObject
            }
            val first = minOf(frames(source.getValue("in_s").jsonPrimitive.double, fps), srcFrames - 1)
            val end = minOf(maxOf(first + 1, frames(source.getValue("out_s").jsonPrimitive.double, fps)), srcFrames)
            val z = seg["zoom"] as? JsonObject
            put("first_frame", first); put("end_frame", end); put("speed", speed!!)
            if (z != null && hasBox(focusBox)) {
                val scale = z.getValue("scale")
                val c = zoomCrop(scale.jsonPrimitive.double, focusBox as JsonObject, sb.getValue("width").jsonPrimitive.int, sb.getValue("height").jsonPrimitive.int)
                put("crop", buildJsonObject { put("scale", scale); put("w", c[0]); put("h", c[1]); put("x", c[2]); put("y", c[3]) })
            } else put("crop", JsonNull)
        }
    }

    fun plan(sb: JsonObject, focusBox: JsonElement?, srcFrames: Int): JsonArray = JsonArray(sb.getValue("segments").jsonArray.map { plan(it.jsonObject, sb, focusBox, srcFrames) })
}
