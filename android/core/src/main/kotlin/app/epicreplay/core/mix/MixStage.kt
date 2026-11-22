package app.epicreplay.core.mix

import app.epicreplay.core.PyMath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where the sound of a layer comes from: [load] gives its channels at 48 kHz for a file named as [Mix.layers] names it (`run/...`, `assets/...`; for the clip's own sound the whole of `run/work.mp4`). */
fun interface MixAudio {
    fun load(file: String): List<FloatArray>
}

/** What S8 gave: the status as the PC names it, a note, `mix.json`, and the mix itself (two channels at 48 kHz) when it was made. */
class MixOutcome(val status: String, val note: String, val doc: JsonObject?, val left: FloatArray?, val right: FloatArray?)

/** S8 on the phone (`stages/s8_mix.py run`): the layers of [Mix.layers] mixed by [MixRender], judged against the loudness target and the ceiling, and `mix.json`. */
object MixStage {
    fun run(sb: JsonObject, voice: JsonObject, pack: JsonObject, uc: JsonObject, meta: JsonObject, rules: MixRules, audio: MixAudio): MixOutcome {
        val layers = Mix.layers(sb, voice, pack, uc, meta).map { it.jsonObject }
        val duration = sb.getValue("duration_s").jsonPrimitive.double
        val rendered = try {
            MixRender.render(layers.map { l ->
                MixLayer(
                    l.getValue("kind").jsonPrimitive.content, audio.load(l.getValue("file").jsonPrimitive.content), l.getValue("start").jsonPrimitive.double,
                    l.getValue("gain_db").jsonPrimitive.double, l["loop"]?.jsonPrimitive?.content == "true", l["in_s"]?.jsonPrimitive?.double, l["out_s"]?.jsonPrimitive?.double,
                )
            }, duration, rules)
        } catch (e: Exception) {
            return MixOutcome("failed", ("the mix could not be made: " + (e.message ?: e.javaClass.simpleName)).take(500), null, null, null)
        }
        val lufs = PyMath.round(rendered.lufs, 1); val peak = PyMath.round(rendered.peakDbfs, 1)
        val doc = buildJsonObject {
            put("layers", JsonArray(layers.map { l -> buildJsonObject { for ((k, v) in l) if (k != "file") put(k, v); put("file", l.getValue("file").jsonPrimitive.content.substringAfterLast('/')) } }))
            put("loudness_lufs", lufs); put("true_peak_dbtp", peak)
            put("duration_s", PyMath.round(rendered.left.size.toDouble() / rules.sampleRate, 3))
            put("target_lufs", rules.targetLufs); put("ceiling_dbtp", rules.ceilingDbtp)
        }
        val ok = Math.abs(lufs - rules.targetLufs) <= 1.0 && peak <= rules.ceilingDbtp
        return MixOutcome(if (ok) "ok" else "degraded", if (ok) "" else "measured $lufs LUFS, $peak dBTP", doc, rendered.left, rendered.right)
    }
}
