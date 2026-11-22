package app.epicreplay.core.conformance

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The synthetic signal of the audio cases (`conformance_speech.signal`): noise bursts on silence, drawn with integers only, so every platform has the same samples. */
object DrawnSignal {
    fun draw(r: JsonObject): Pair<FloatArray, Int> {
        val sr = r.getValue("rate").jsonPrimitive.int
        val n = Math.round(r.getValue("seconds").jsonPrimitive.double * sr).toInt()
        val ints = LongArray(n)
        var state = 987654321L
        for (b in r.getValue("bursts").jsonArray) {
            val o = b.jsonObject
            val s0 = o.getValue("start_s").jsonPrimitive.double
            val start = Math.round(s0 * sr).toInt()
            val end = minOf(n, Math.round((s0 + o.getValue("dur_s").jsonPrimitive.double) * sr).toInt())
            val amp = o.getValue("amp").jsonPrimitive.int
            for (i in start until end) {
                state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
                ints[i] = Math.floorDiv((((state shr 16) and 0xFFFF) - 32768) * amp, 32768L)
            }
        }
        return FloatArray(n) { (ints[it].coerceIn(-32768, 32767) / 32768.0).toFloat() } to sr
    }
}
