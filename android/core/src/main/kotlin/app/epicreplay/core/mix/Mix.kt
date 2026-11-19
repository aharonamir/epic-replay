package app.epicreplay.core.mix

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

/** The numbers of the mix (`shared/mix/rules.json`): loudness target, ceiling, the bed's fades and the ducking. */
class MixRules(val targetLufs: Double, val ceilingDbtp: Double, val limitMarginDb: Double, val sampleRate: Int, val fadeInS: Double, val fadeOutS: Double, val duck: Duck) {
    class Duck(val threshold: Double, val ratio: Double, val attackMs: Double, val releaseMs: Double, val makeup: Double)

    companion object {
        fun parse(text: String): MixRules {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            val bed = o.getValue("bed").jsonObject; val d = o.getValue("duck").jsonObject
            fun n(x: JsonObject, k: String) = x.getValue(k).jsonPrimitive.double
            return MixRules(
                n(o, "target_lufs"), n(o, "ceiling_dbtp"), n(o, "limit_margin_db"), o.getValue("sample_rate").jsonPrimitive.content.toInt(),
                n(bed, "fade_in_s"), n(bed, "fade_out_s"), Duck(n(d, "threshold"), n(d, "ratio"), n(d, "attack_ms"), n(d, "release_ms"), n(d, "makeup")),
            )
        }
    }
}

/** `s8_mix.layers`: what goes into the mix, in order: the voice lines not dropped, the bed, the one-shots, the clip's own sound. Folders are written `run/` and `assets/`. */
object Mix {
    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toBigDecimalOrNull()?.signum() != 0
    }

    fun layers(sb: JsonObject, voice: JsonObject, pack: JsonObject, uc: JsonObject, meta: JsonObject): JsonArray {
        val seg = sb.getValue("segments").jsonArray.associateBy { it.jsonObject.getValue("kind").jsonPrimitive.content } // a repeated kind: the last
        val snd = pack.getValue("sound").jsonObject
        val out = ArrayList<JsonObject>()
        for (ln in voice.getValue("lines").jsonArray) {
            val l = ln.jsonObject
            if (!truthy(l["dropped"])) out += buildJsonObject {
                put("kind", "voice"); put("id", l.getValue("id")); put("file", "run/" + l.getValue("file").jsonPrimitive.content)
                put("start", l.getValue("timeline_start")); put("gain_db", 0.0)
            }
        }
        if (truthy(snd["bed"])) out += buildJsonObject {
            put("kind", "bed"); put("file", "assets/" + snd.getValue("bed").jsonPrimitive.content); put("start", 0.0)
            put("gain_db", snd.getValue("bed_gain_db")); put("loop", true)
        }
        val marks = sb.getValue("markers").jsonObject
        val shots = listOf(
            "climax" to marks.getValue("climax_live_t"),
            "replay_climax" to marks.getValue("climax_replay_t"),
            "transition" to seg["transition"]?.jsonObject?.getValue("timeline_start"),
            "sting_in" to JsonPrimitive(0.0),
            "sting_out" to seg["endcard"]?.jsonObject?.getValue("timeline_start"),
        )
        for ((kind, at) in shots) if (truthy(snd[kind]) && at != null) out += buildJsonObject {
            put("kind", kind); put("file", "assets/" + snd.getValue(kind).jsonPrimitive.content); put("start", at); put("gain_db", 0.0)
        }
        val live = seg["live"]?.jsonObject
        if (live != null && truthy(meta["has_audio"]) && (if ("keep_original_audio" in uc) truthy(uc["keep_original_audio"]) else true)) {
            val src = live.getValue("source").jsonObject
            out += buildJsonObject {
                put("kind", "original"); put("file", "run/work.mp4"); put("start", live.getValue("timeline_start"))
                put("gain_db", snd.getValue("original_audio_gain_db")); put("in_s", src.getValue("in_s")); put("out_s", src.getValue("out_s"))
            }
        }
        return JsonArray(out)
    }
}
