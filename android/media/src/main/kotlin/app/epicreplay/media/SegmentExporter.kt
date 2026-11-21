package app.epicreplay.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The failure of an export, with the reason Media3 gives. */
class SegmentExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * S9 on the phone (plan 4.10, FR-028): one video file for each segment of the visuals plan (`VisualsPlan`), made with Media3. Video only, 30 frames a
 * second, at the storyboard's size, as the PC's FFmpeg makes them:
 * - a segment with a source is cut from the clip by frame numbers (`first_frame` to `end_frame`, read as time at 30 a second), played at its speed (the
 *   picture of a slowed one is mixed up to 30 a second by [FrameBlendEffect], the PC's `framerate`), and with a zoom cropped toward the focus box;
 * - a segment with no source holds one frame of the clip ([ClipFrames]) as a still;
 * - a replay that ends before its length (`hold_s`, the clip ended too soon after the climax) holds its last frame for the rest.
 * Each file has exactly the `frames` of its plan entry.
 */
@UnstableApi
class SegmentExporter(private val context: Context, private val clip: File) {
    private val main = Handler(Looper.getMainLooper())

    /** Makes the file for one plan entry; [width] and [height] are the storyboard's. Returns when the file is written. */
    suspend fun export(entry: JsonObject, width: Int, height: Int, out: File) {
        val frames = entry.getValue("frames").jsonPrimitive.int
        val items = ArrayList<EditedMediaItem>()
        val still = ArrayList<File>()
        try {
            if (entry["freeze_frame"] != null) {
                items += stillItem(entry.getValue("freeze_frame").jsonPrimitive.int, frames, width, height, still)
            } else {
                val first = entry.getValue("first_frame").jsonPrimitive.int
                val end = entry.getValue("end_frame").jsonPrimitive.int
                val speed = entry.getValue("speed").jsonPrimitive.double
                items += cutItem(first, end, speed, entry["crop"], width, height)
                val played = Math.round((end - first) / speed).toInt() // the frames the cut makes at 30 a second
                if (frames > played) items += stillItem(end - 1, frames - played, width, height, still) // the last frame held for the rest
            }
            val sequence = EditedMediaItemSequence.Builder(items).build()
            run(Composition.Builder(sequence).build(), out)
        } finally { still.forEach { it.delete() } }
    }

    private fun cutItem(first: Int, end: Int, speed: Double, crop: JsonElement?, width: Int, height: Int): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(Math.round(first * 1e6 / 30.0)).setEndPositionUs(Math.round(end * 1e6 / 30.0)).build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(clip)).setClippingConfiguration(clipping).build()
        val effects = ArrayList<Effect>()
        (crop as? JsonObject)?.let { c ->
            // the crop is given in pixels of the storyboard's picture; Media3's Crop takes -1 to 1 from the left and bottom edges
            val cw = c.getValue("w").jsonPrimitive.int; val ch = c.getValue("h").jsonPrimitive.int; val cx = c.getValue("x").jsonPrimitive.int; val cy = c.getValue("y").jsonPrimitive.int
            effects += Crop(2f * cx / width - 1f, 2f * (cx + cw) / width - 1f, 1f - 2f * (cy + ch) / height, 1f - 2f * cy / height)
        }
        effects += Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        if (speed < 1.0) effects += FrameBlendEffect(30.0)
        val b = EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), effects))
        if (speed != 1.0) b.setSpeed(constantSpeed(speed.toFloat()))
        return b.build()
    }

    private fun constantSpeed(speed: Float) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
    }

    /** A still of frame [n] of the clip for [frames] frames at 30 a second. */
    private fun stillItem(n: Int, frames: Int, width: Int, height: Int, made: MutableList<File>): EditedMediaItem {
        val bmp: Bitmap = ClipFrames(clip.path).use { it.frameAt(n) } ?: throw SegmentExportException("frame $n of the clip could not be read")
        val png = File.createTempFile("still", ".png", context.cacheDir).also { made += it }
        FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val item = MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(Math.round(frames * 1000.0 / 30.0)).build()
        return EditedMediaItem.Builder(item).setFrameRate(30).setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)))).build()
    }

    /** Runs the export on the main thread (Media3 wants a looper) and waits; the phone's encoder sometimes fails once (spike S2), so it is tried twice. */
    private suspend fun run(composition: Composition, out: File) {
        var last: Throwable? = null
        repeat(2) { attempt ->
            out.delete()
            val done = CompletableDeferred<ExportResult>()
            main.post {
                val t = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(exportResult) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                }).build()
                try { t.start(composition, out.path) } catch (e: Exception) { done.completeExceptionally(e) }
            }
            try { done.await(); return } catch (e: Exception) { last = e }
        }
        throw SegmentExportException("the segment could not be exported: ${last?.message}", last)
    }
}
