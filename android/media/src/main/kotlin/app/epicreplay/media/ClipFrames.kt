package app.epicreplay.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

/**
 * The clip as 30 frames a second, read by time (the PC converts to a constant 30 fps copy and counts frames in that): frame `n` is the picture at
 * `n / 30` s. The frame count is the readable length times 30, to the nearest. A frame the decoder cannot give is null.
 */
class ClipFrames(private val path: String) : AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(path) }

    /** How many frames the clip has at 30 a second. */
    val count: Int = ClipProbe.readable(path)?.info?.let { info ->
        (info.getValue("format") as kotlinx.serialization.json.JsonObject).getValue("duration").let { (it as kotlinx.serialization.json.JsonPrimitive).content.toDouble() }
    }?.let { Math.round(it * FPS).toInt() } ?: 0

    fun frameAt(n: Int): Bitmap? {
        if (n < 0 || n >= count) return null
        return try { retriever.getFrameAtTime(Math.round(n * 1e6 / FPS), MediaMetadataRetriever.OPTION_CLOSEST) } catch (e: RuntimeException) { null }
    }

    override fun close() = retriever.release()

    companion object { const val FPS = 30.0 }
}
