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
    private companion object { const val EDGE_US = 2_000L }
    private val main = Handler(Looper.getMainLooper())

    /** Makes the file for one plan entry; [width] and [height] are the storyboard's. Returns when the file is written, and checks that it has the plan's frames. */
    suspend fun export(entry: JsonObject, width: Int, height: Int, out: File) {
        val frames = entry.getValue("frames").jsonPrimitive.int
        val still = ArrayList<File>()
        try {
            val item = if (entry["freeze_frame"] != null) stillItem(entry.getValue("freeze_frame").jsonPrimitive.int, frames, width, height, still)
            else cutItem(entry.getValue("first_frame").jsonPrimitive.int, entry.getValue("end_frame").jsonPrimitive.int, entry.getValue("speed").jsonPrimitive.double, entry["crop"], width, height, frames)
            run(Composition.Builder(EditedMediaItemSequence.Builder(listOf(item)).build()).build(), out)
            val made = ClipProbe.readable(out.path)?.videoSamplesUs?.size ?: 0
            if (made != frames) { out.delete(); throw SegmentExportException("the segment ${entry.getValue("id").jsonPrimitive.content} has $made frames, the plan has $frames") }
        } finally { still.forEach { it.delete() } }
    }

    private fun cutItem(first: Int, end: Int, speed: Double, crop: JsonElement?, width: Int, height: Int, frames: Int): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(edgeUs(first)).setEndPositionUs(edgeUs(end)).build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(clip)).setClippingConfiguration(clipping).build()
        val effects = ArrayList<Effect>()
        (crop as? JsonObject)?.let { c ->
            // the crop is given in pixels of the storyboard's picture; Media3's Crop takes -1 to 1 from the left and bottom edges
            val cw = c.getValue("w").jsonPrimitive.int; val ch = c.getValue("h").jsonPrimitive.int; val cx = c.getValue("x").jsonPrimitive.int; val cy = c.getValue("y").jsonPrimitive.int
            effects += Crop(2f * cx / width - 1f, 2f * (cx + cw) / width - 1f, 1f - 2f * (cy + ch) / height, 1f - 2f * cy / height)
        }
        // a crop is stretched back to the storyboard's size, as the PC's `scale=W:H` does (its shape is the storyboard's up to the even rounding of the crop)
        effects += Presentation.createForWidthAndHeight(width, height, if (crop is JsonObject) Presentation.LAYOUT_STRETCH_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT)
        effects += FrameBlendEffect(30.0, blend = speed < 1.0, frames = frames) // 30 a second, the plan's number of them
        val b = EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), effects))
        if (speed != 1.0) b.setSpeed(constantSpeed(speed.toFloat()))
        return b.build()
    }

    /** Where a frame number starts, in microseconds: a container writes the time of frame 2 as 66,666 and Media3 drops frames before the start, so the edge is a little early (the frame is not lost) and the frame at the end is left out all the same. */
    private fun edgeUs(frame: Int): Long = maxOf(0L, Math.round(frame * 1e6 / 30.0) - EDGE_US)

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

    /** Runs the export on the main thread (Media3 wants a looper) and waits; the phone's encoder sometimes fails once (spike S2), so it is tried twice. A cancelled call stops the export and returns only when it has stopped. */
    private suspend fun run(composition: Composition, out: File) {
        var last: Throwable? = null
        repeat(2) {
            out.delete()
            val done = CompletableDeferred<ExportResult>()
            val stopped = CompletableDeferred<Unit>() // the export has been stopped, or never started
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val active = java.util.concurrent.atomic.AtomicReference<Transformer?>(null)
            main.post {
                try {
                    if (cancelled.get()) return@post
                    val t = Transformer.Builder(context).addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(exportResult) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                    active.set(t)
                    t.start(composition, out.path)
                } catch (e: Exception) { done.completeExceptionally(e) }
            }
            try { done.await(); return } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                main.post { try { active.get()?.cancel() } finally { stopped.complete(Unit) } } // after a start that was already queued, on the same looper
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stopped.await(); out.delete() }
                throw e // never started again after a cancel
            } catch (e: Exception) { last = e }
        }
        out.delete()
        throw SegmentExportException("the segment could not be exported: ${last?.message}", last)
    }
}
