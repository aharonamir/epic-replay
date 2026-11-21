package app.epicreplay.core.intake

import app.epicreplay.core.PyMath
import app.epicreplay.core.text.PyText
import java.util.Locale
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

/**
 * S0's rules (`pc/src/epic_replay/stages/s0_intake.py`, FR-001 and FR-002) on what a probe found: ffprobe's `format` duration and `streams`
 * (codec type, size, frame rates). The app builds the same shape from its own decoders ([app.epicreplay.media]), so the rules here are the PC's.
 */
object Intake {
    private val EXTS = setOf(".mp4", ".mov", ".webm")
    const val MAX_BYTES = 200L * 1024 * 1024
    const val MIN_S = 3.0
    const val MAX_S = 30.0
    const val SLACK_S = 0.05
    const val WORK_FPS = 30

    private val DECIMAL = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    /** `pathlib.Path(name).suffix.lower()`: from the last dot, unless the name starts with it or ends with it. */
    private fun suffix(name: String): String {
        val base = name.substringAfterLast('/')
        val i = base.lastIndexOf('.')
        return if (i > 0 && i < base.length - 1) base.substring(i).lowercase(Locale.ROOT) else ""
    }

    /** `float(x)` of a value ffprobe wrote: a number, or a string of one; anything else is 0. */
    private fun asFloat(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) return p.double
        val t = p.content.trim { PyText.isSpace(it) }
        return if (DECIMAL.matches(t)) t.toDouble() else null
    }

    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toDoubleOrNull() != 0.0
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
    }

    private fun streams(info: JsonObject): List<JsonObject> = (info["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun video(info: JsonObject): JsonObject? = streams(info).firstOrNull { (it["codec_type"] as? JsonPrimitive)?.content == "video" }

    /** The format duration in seconds; 0 when there is none or it is not a number. */
    fun duration(info: JsonObject): Double {
        val d = (info["format"] as? JsonObject)?.get("duration")
        return if (truthy(d)) asFloat(d) ?: 0.0 else 0.0
    }

    fun lengthOk(dur: Double) = MIN_S - SLACK_S <= dur && dur <= MAX_S + SLACK_S

    /** Why an upload is refused, in words for the page, or null: its name's extension, its size, its video stream and its length, in that order. */
    fun check(name: String, sizeBytes: Long, info: JsonObject): String? {
        if (suffix(name) !in EXTS) return "The clip must be an MP4, MOV or WebM file."
        if (sizeBytes > MAX_BYTES) return "The clip is larger than 200 MB."
        if (video(info) == null) return "The file could not be read as a video."
        val dur = duration(info)
        if (!lengthOk(dur)) return "The clip is ${PyMath.fixed1(dur)} s long; it must be 3 to 30 seconds."
        return null
    }

    /** Why the working copy is refused, or null: no video in what the conversion made, or a length outside 3 to 30 s on what could be read. */
    fun checkWork(info: JsonObject): String? {
        if (video(info) == null) return "The clip could not be converted; try another file."
        val dur = duration(info)
        if (!lengthOk(dur)) return "Only ${PyMath.fixed1(dur)} s of the clip could be read; it must be 3 to 30 seconds."
        return null
    }

    /** The upload's own frame rate: the average, else the container's, else [WORK_FPS]. */
    fun captureFps(stream: JsonObject?): Double {
        for (key in listOf("avg_frame_rate", "r_frame_rate")) {
            val v = (stream?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
            val num = v.substringBefore('/'); val den = if ('/' in v) v.substringAfter('/') else ""
            val n = num.takeIf { DECIMAL.matches(it.trim()) }?.trim()?.toDouble() ?: continue
            val d = if (den.isEmpty()) 1.0 else den.takeIf { DECIMAL.matches(it.trim()) }?.trim()?.toDouble() ?: continue
            if (d == 0.0) continue
            val rate = n / d
            if (rate > 0) return PyMath.round(rate, 3)
        }
        return WORK_FPS.toDouble()
    }

    /** `clip_meta.json` from what was found in the upload and in the working copy. */
    fun clipMeta(srcInfo: JsonObject, workInfo: JsonObject, sourceSha256: String): JsonObject {
        val v = video(workInfo)!!
        return buildJsonObject {
            put("duration_s", PyMath.round(duration(workInfo), 3)); put("width", v.getValue("width")); put("height", v.getValue("height"))
            put("rotation", 0); put("fps", WORK_FPS); put("capture_fps", captureFps(video(srcInfo)))
            put("has_audio", streams(srcInfo).any { (it["codec_type"] as? JsonPrimitive)?.content == "audio" }); put("source_sha256", sourceSha256)
        }
    }
}
