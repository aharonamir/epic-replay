package app.epicreplay.core.visuals

/** One picture of the output: when it is shown, how much of the later source frame it holds (0 the earlier, 1 the later), and which slot of the 30 a second clock it is. */
class ClockOut(val slot: Int, val timeUs: Long, val weight: Double)

/**
 * The steady clock of a segment's picture (spec 004 T421): one output picture every 1/[fps] s from source frames at any times, as the PC makes it: the
 * `fps=30` filter for a cut (each slot shows the nearer source frame) and `framerate` for a slowed one, whose output frames are the two nearest source
 * frames mixed. The grid is one for the whole stream (slot k is at k/fps after the first frame), so no remainder is lost between frames, and a source
 * with more frames a second loses some while one with fewer repeats some. With a [limit] the stream has exactly that many pictures, the last one held
 * if the source runs out (the PC's `fit`).
 */
class FrameClock(private val fps: Double = 30.0, private val blend: Boolean, private val limit: Int? = null) {
    private val step = 1_000_000.0 / fps
    private val slack = step * 0.005 // a source time is a whole number of microseconds: 133,332 for 133,333.3
    private var first = 0L
    private var previous = 0L
    private var count = 0 // frames seen
    private var next = 0 // the next slot to make
    private var lastGap = 0L

    /** The number of pictures made so far. */
    val made: Int get() = next

    private fun slotUs(k: Int) = first + Math.round(k * step)

    private fun weightFor(slotTime: Long, gap: Long): Double {
        val w = ((slotTime - previous) / gap.toDouble()).coerceIn(0.0, 1.0)
        // FFmpeg's `framerate` does not mix when one frame is nearly all of it (its interpolation limits 15 to 240 of 256), and `fps` takes the nearer frame
        return if (!blend) (if (w < 0.5) 0.0 else 1.0) else if (w < 15 / 256.0) 0.0 else if (w > 240 / 256.0) 1.0 else w
    }

    private fun room() = limit?.let { it - next } ?: Int.MAX_VALUE

    /** The pictures that the source frame at [timeUs] completes: the slots after the previous frame up to and including its own time. The first frame is slot 0, itself. */
    fun onFrame(timeUs: Long): List<ClockOut> {
        val out = ArrayList<ClockOut>()
        if (count == 0) {
            first = timeUs; previous = timeUs; count = 1
            if (room() > 0) { out += ClockOut(0, timeUs, 1.0); next = 1 }
            return out
        }
        val gap = timeUs - previous
        if (gap > 0) {
            while (room() > 0 && slotUs(next) <= timeUs + slack) {
                val t = slotUs(next)
                out += ClockOut(next, minOf(t, maxOf(timeUs, t)), weightFor(t, gap))
                next++
            }
            lastGap = gap
        }
        previous = timeUs; count++
        return out
    }

    /** The pictures after the last source frame: with a limit, the last frame held until the stream has them all; without, held for one more gap of the source. */
    fun finish(): List<ClockOut> {
        val out = ArrayList<ClockOut>()
        if (count == 0) return out
        while (room() > 0 && (limit != null || slotUs(next) < previous + lastGap - slack)) {
            out += ClockOut(next, slotUs(next), 0.0)
            next++
        }
        return out
    }
}
