package app.epicreplay.core.visuals

/** One frame of the blended picture: when it is shown and how much of the later source frame it holds (0 is the earlier frame, 1 the later one). */
class BlendStep(val timeUs: Long, val weight: Double)

/**
 * The frames between two source frames of a slowed segment (spec 004 T421, as the PC's FFmpeg `framerate` makes them): Media3 stretches the times of a
 * 0.25 segment so a source frame comes every 133 ms and a picture every 33 ms is wanted, so each gap is filled with frames that are the two source
 * frames mixed, the later one more and more. The last step of a gap is the later source frame itself, at its own time.
 */
object BlendSchedule {
    /** The steps from just after [previousUs] up to [currentUs], one every 1/[fps] s; none if the frames are less than a step apart. */
    fun steps(previousUs: Long, currentUs: Long, fps: Double = 30.0): List<BlendStep> {
        val gap = currentUs - previousUs
        if (gap <= 0) return emptyList()
        val delta = 1_000_000.0 / fps
        val exact = gap / delta
        // 133,333 us is four steps of 33,333.3 us, however the microseconds were rounded
        val n = if (Math.abs(exact - Math.rint(exact)) < 0.02) Math.rint(exact).toInt() else Math.floor(exact).toInt()
        if (n < 1) return emptyList()
        return (1..n).map { j ->
            if (j == n && Math.abs(exact - n) < 0.02) BlendStep(currentUs, 1.0)
            else BlendStep(previousUs + Math.round(j * delta), minOf(1.0, j * delta / gap))
        }
    }
}
