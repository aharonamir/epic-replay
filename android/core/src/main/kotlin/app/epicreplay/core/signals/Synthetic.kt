package app.epicreplay.core.signals

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The `signals.scan` conformance function: draws the recipe's clip with integers only, as `pc/src/epic_replay/conformance_signals.py` does, and scans it. */
object Synthetic {
    private fun texture(x: Int, y: Int, kind: Int): Int {
        val (a, b, c) = if (kind == 0) Triple(37, 91, 5) else Triple(53, 29, 7)
        return (x * a + y * b + ((x * y) % 23) * c) % 101 + 60
    }

    fun frames(r: JsonObject): Sequence<Gray> {
        val w = r.getValue("width").jsonPrimitive.int; val h = r.getValue("height").jsonPrimitive.int
        val count = r.getValue("frames").jsonPrimitive.int
        val cut = (r["cut_frame"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int
        val pan = r["pan"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(0, 0)
        val shake = r["shake"]?.jsonObject
        val objects = r["objects"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        return (0 until count).asSequence().map { f ->
            val kind = if (cut != null && f >= cut) 1 else 0
            val sh = shake?.get(f.toString())?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(0, 0)
            val px = pan[0] * f + sh[0]; val py = pan[1] * f + sh[1]
            val data = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) data[y * w + x] = texture(x + px + 1000, y + py + 1000, kind).toByte()
            for (o in objects) {
                val from = o.getValue("from").jsonPrimitive.int; val to = o.getValue("to").jsonPrimitive.int
                if (f < from || f >= to) continue
                val size = o.getValue("size").jsonPrimitive.int
                val x = o.getValue("x").jsonPrimitive.int + o.getValue("vx").jsonPrimitive.int * (f - from)
                val y = o.getValue("y").jsonPrimitive.int + o.getValue("vy").jsonPrimitive.int * (f - from)
                for (yy in Math.max(0, y) until Math.min(h, Math.max(0, y + size))) for (xx in Math.max(0, x) until Math.min(w, Math.max(0, x + size)))
                    data[yy * w + xx] = 250.toByte()
            }
            Gray(w, h, data)
        }
    }

    fun audio(a: JsonObject): FloatArray {
        val sr = Audio.SR
        val n = a.getValue("seconds").jsonPrimitive.int * sr
        val floorAmp = a["floor"]?.jsonPrimitive?.int ?: 0
        val ints = LongArray(n)
        var state = 12345L
        fun next(): Long { state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL; return ((state shr 16) and 0xFFFF) - 32768 }
        for (i in 0 until n) ints[i] = Math.floorDiv(next() * floorAmp, 32768L)
        for (e in a.getValue("bursts").jsonArray) {
            val b = e.jsonObject
            val start = b.getValue("at_sample").jsonPrimitive.int; val length = b.getValue("length").jsonPrimitive.int; val amp = b.getValue("amp").jsonPrimitive.int
            for (i in 0 until length) {
                val r = next()
                ints[start + i] += Math.floorDiv(r * Math.floorDiv(amp.toLong() * (length - i), length.toLong()), 32768L)
            }
        }
        return FloatArray(n) { (ints[it].coerceIn(-32768, 32767) / 32768.0).toFloat() }
    }

    fun scan(args: Map<String, JsonElement>): JsonElement {
        val r = args.getValue("recipe").jsonObject
        val fps = r.getValue("fps").jsonPrimitive.int
        val y = r["audio"]?.takeIf { it !is JsonNull }?.let { audio(it.jsonObject) } ?: FloatArray(0)
        val s = SignalScan.scan(frames(r), fps.toDouble(), y, 0.0, args.getValue("top").jsonPrimitive.int)
        val minScore = args.getValue("min_score").jsonPrimitive.content.toDouble()
        fun r3(x: Double) = JsonPrimitive(SignalScan.rint(x, 3))
        return buildJsonObject {
            put("has_sound", s.hasSound)
            put("frames", s.frames)
            put("cuts_t", JsonArray(s.cuts.map { r3(it.toDouble() / fps) }))
            put("onsets_t", JsonArray(s.onsetsT.map(::r3)))
            put("loud_peaks_t", JsonArray(s.loudPeaksT.map(::r3)))
            put("candidates", JsonArray(s.candidates.filter { it.score >= minScore }.sortedBy { it.t }.map { c ->
                buildJsonObject { put("t", r3(c.t)); put("sound_onset_t", c.soundOnsetT?.let(::r3) ?: JsonNull) }
            }))
        }
    }
}
