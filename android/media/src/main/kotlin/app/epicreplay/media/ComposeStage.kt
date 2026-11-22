package app.epicreplay.media

import android.content.res.AssetManager
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import app.epicreplay.core.PyMath
import app.epicreplay.core.audio.Loudness
import app.epicreplay.core.audio.Wav
import app.epicreplay.core.compose.ComposeMeasure
import app.epicreplay.core.compose.EncoderChoice
import app.epicreplay.core.compose.OverlayPlan
import app.epicreplay.core.mix.MixAudio
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The sound of the layers of the mix on the phone: `run/...` from the run's folder (the clip's own sound decoded from `work.mp4`), `assets/...` from the shared sounds packed into the app. */
class AndroidMixAudio(private val assets: AssetManager, private val runDir: File, private val rate: Int) : MixAudio {
    /** A WAV layer must be at the mix's rate (the PC resamples; every shipped sound and take is at 48 kHz, so a different one is refused rather than played at the wrong speed). */
    private fun wav(bytes: ByteArray, name: String): List<FloatArray> {
        val (channels, r) = Wav.readChannels(bytes)
        require(r == rate) { "$name is at $r Hz, the mix is at $rate Hz" }
        require(channels.size <= 2) { "$name has ${channels.size} channels; a sound of the pack is mono or stereo" }
        return channels
    }

    override fun load(file: String): List<FloatArray> = when {
        file == "run/work.mp4" -> SignalDecoder.decodeChannels(File(runDir, "work.mp4").path, rate) ?: emptyList()
        file.startsWith("run/") -> wav(File(runDir, file.removePrefix("run/")).readBytes(), file)
        file.startsWith("assets/") -> wav(assets.open(file).use { it.readBytes() }, file)
        else -> throw IllegalArgumentException("unknown layer file $file")
    }
}

/** What S10 gave: the status as the PC names it, a note, and `render.json`. */
class ComposeOutcome(val status: String, val note: String, val doc: JsonObject?)

/**
 * S10 on the phone (`stages/s10_compose.py run`): the overlays drawn by [OverlayArt], the segments, the overlays and the mix into `final.mp4` by [Composer], and
 * `render.json` with what is measured on the file: the picture's frames (as many as the storyboard's length at its rate), the sound's loudness and peak, and where
 * each key line landed against its target. A video whose frames or sound are not the storyboard's length, or whose key line is more than 150 ms off, is "degraded"
 * and says so in the note; the "AI COMMENTARY" label is an overlay of every export (the storyboard always has one).
 */
@UnstableApi
class ComposeStage(private val composer: Composer, private val fonts: OverlayFonts) {
    suspend fun run(runDir: File, sb: JsonObject, script: JsonObject, voice: JsonObject, pack: JsonObject, log: (String) -> Unit = {}): ComposeOutcome {
        val width = sb.getValue("width").jsonPrimitive.int; val height = sb.getValue("height").jsonPrimitive.int
        val zh = (pack["language"] as? JsonPrimitive)?.content == "zh"
        val graphics = pack.getValue("graphics").jsonObject
        val specs = OverlayPlan.specs(sb, script, pack)
        val overlays = specs.map { it to OverlayArt.render(it, graphics, width, height, zh, fonts) }
        val segments = sb.getValue("segments").jsonArray.map { File(runDir, "segments/" + it.jsonObject.getValue("id").jsonPrimitive.content + ".mp4") }
        val final = File(runDir, "final.mp4")
        val events = ArrayList<String>()
        val result = try {
            composer.compose(segments, File(runDir, "mix.wav"), overlays, final, portrait = height > width) { events += it; log(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return ComposeOutcome("failed", ("the video could not be made: " + (e.message ?: e.javaClass.simpleName)).take(500), null)
        } finally { overlays.forEach { it.second.recycle() } }

        val frames = ClipProbe.readable(final.path)?.videoSamplesUs?.size ?: 0
        val info = probe(final)
        val stereo = SignalDecoder.decodeChannels(final.path, 48000) ?: emptyList()
        val lufs = if (stereo.isEmpty()) Double.NEGATIVE_INFINITY else Loudness.integrated(stereo, 48000)
        val peak = if (stereo.isEmpty()) Double.NEGATIVE_INFINITY else Loudness.truePeakDb(stereo)
        val mono = if (stereo.isEmpty()) FloatArray(0) else FloatArray(stereo[0].size) { i -> stereo.sumOf { it[i].toDouble() }.toFloat() / stereo.size }
        val takes = voice.getValue("lines").jsonArray.map { it.jsonObject }.filter { !app.epicreplay.core.mix.Mix.truthy(it["dropped"]) }
            .associate { it.getValue("id").jsonPrimitive.content to Wav.read(File(runDir, it.getValue("file").jsonPrimitive.content).readBytes()).samples }
        val hits = ComposeMeasure.onsets(voice, takes, mono, 48000)
        val fps = sb.getValue("fps").jsonPrimitive.double
        val problems = ComposeMeasure.problems(sb, frames, mono.size / 48000.0, hits, info.width, info.height)
        val labels = pack.getValue("script").jsonObject.getValue("labels").jsonObject
        val doc = buildJsonObject {
            put("duration_s", PyMath.round(frames / fps, 3)); put("container_duration_s", PyMath.round(info.durationS, 3))
            put("width", info.width); put("height", info.height); put("video_codec", info.video); put("audio_codec", info.audio)
            put("loudness_lufs", PyMath.round(lufs, 1)); put("true_peak_dbtp", PyMath.round(peak, 1))
            put("climax_onsets", JsonArray(hits))
            put("ai_label", (labels["ai_label"] as? JsonPrimitive)?.content?.ifEmpty { null } ?: OverlayPlan.AI_LABEL)
            put("graphics", "canvas")
            put("encoder", if (result.encoder == EncoderChoice.SOFTWARE) "software" else "hardware")
            put("export_events", JsonArray(events.map { JsonPrimitive(it) }))
        }
        return ComposeOutcome(if (problems.isEmpty()) "ok" else "degraded", problems.joinToString("; "), doc)
    }

    private class Info(val durationS: Double, val width: Int, val height: Int, val video: String, val audio: String)

    private fun probe(f: File): Info {
        val ex = MediaExtractor().apply { setDataSource(f.path) }
        try {
            var duration = 0.0; var w = 0; var h = 0; var v = ""; var a = ""
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i); val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, fmt.getLong(MediaFormat.KEY_DURATION) / 1e6)
                if (mime.startsWith("video/")) { v = if (mime == "video/avc") "h264" else mime.removePrefix("video/"); w = fmt.getInteger(MediaFormat.KEY_WIDTH); h = fmt.getInteger(MediaFormat.KEY_HEIGHT) }
                if (mime.startsWith("audio/")) a = if (mime == "audio/mp4a-latm") "aac" else mime.removePrefix("audio/")
            }
            return Info(duration, w, h, v, a)
        } finally { ex.release() }
    }
}
