package app.epicreplay.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the phone's decoders find in a clip, in the shape of ffprobe's output that the intake rules read (`core/intake`): `format.duration` and
 * `streams` with `codec_type`, `width`, `height`, `avg_frame_rate` and `r_frame_rate`. [probe] reads the header (what the container promises);
 * [readable] reads the samples (what can be read), so a file cut short shows its real length. An unreadable file gives `{}`.
 */
object ClipProbe {
    class Streams(val info: JsonObject, val videoSamplesUs: List<Long>, val rotation: Int, val codedWidth: Int, val codedHeight: Int)

    private fun seconds(us: Long) = "%.6f".format(java.util.Locale.ROOT, us / 1e6)

    /** A file the decoders cannot read gives null, never an exception: whatever the extractor throws while reading is an unreadable file. */
    private fun scan(path: String, samples: Boolean): Streams? = try { scanOrThrow(path, samples) } catch (e: RuntimeException) { null } catch (e: IOException) { null }

    private fun scanOrThrow(path: String, samples: Boolean): Streams? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
        } catch (e: IOException) { ex.release(); return null } catch (e: RuntimeException) { ex.release(); return null }
        try {
            var durationUs = 0L; var rotation = 0; var w = 0; var h = 0
            val tracks = ArrayList<JsonObject>()
            var videoIndex = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/")) {
                    if (videoIndex < 0) {
                        videoIndex = i
                        w = f.getInteger(MediaFormat.KEY_WIDTH); h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey(MediaFormat.KEY_ROTATION)) rotation = f.getInteger(MediaFormat.KEY_ROTATION)
                    }
                } else if (!mime.startsWith("audio/")) continue
            }
            // the samples of the first video track: their times give the real length and the real average rate
            val times = ArrayList<Long>()
            if (samples && videoIndex >= 0) {
                ex.selectTrack(videoIndex)
                while (true) { val t = ex.sampleTime; if (t < 0) break; times += t; if (!ex.advance()) break }
            }
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    val declared = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) "${f.getInteger(MediaFormat.KEY_FRAME_RATE)}/1" else "0/0"
                    val avg = if (i == videoIndex && times.size > 1) "%.6f/1".format(java.util.Locale.ROOT, (times.size - 1) * 1e6 / (times.last() - times.first())) else "0/0"
                    tracks += buildJsonObject {
                        put("codec_type", "video"); put("width", f.getInteger(MediaFormat.KEY_WIDTH)); put("height", f.getInteger(MediaFormat.KEY_HEIGHT))
                        put("avg_frame_rate", avg); put("r_frame_rate", declared)
                    }
                } else if (mime.startsWith("audio/")) tracks += buildJsonObject { put("codec_type", "audio") }
            }
            val lengthUs = if (samples && times.isNotEmpty()) {
                val period = if (times.size > 1) (times.last() - times.first()) / (times.size - 1) else 0L
                times.last() + period
            } else durationUs
            val info = buildJsonObject {
                put("format", buildJsonObject { put("duration", seconds(lengthUs)) })
                put("streams", JsonArray(tracks))
            }
            return Streams(info, times, rotation, w, h)
        } finally { ex.release() }
    }

    /** The header's view: what the container says it holds. */
    fun probe(path: String): Streams? = scan(path, samples = false)

    /** The samples' view: how long the video really is and its real average frame rate. */
    fun readable(path: String): Streams? = scan(path, samples = true)

    /**
     * The picture's rotation in degrees (0, 90, 180 or 270). The retriever's value is the authority, because it is what the frames the app reads
     * (`ClipFrames`) are turned by; the track's own `KEY_ROTATION` ([fallback]) is used only when the retriever says nothing.
     */
    fun rotation(path: String, fallback: Int = 0): Int {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: fallback } catch (e: RuntimeException) { fallback } finally { r.release() }
    }
}
