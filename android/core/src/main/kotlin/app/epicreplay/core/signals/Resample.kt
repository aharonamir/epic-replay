package app.epicreplay.core.signals

/** Frames at the clip's own times, made into the constant 30 a second the PC's working copy has (`stages/s0_intake.py`: FFmpeg's `fps=30`). */
object Resample {
    const val WORK_FPS = 30.0

    /**
     * Each frame comes with its time in microseconds. As FFmpeg's `fps=30` does (round to the nearest), a frame takes the output slot nearest its time,
     * counted from the first frame, and holds it until the next frame's slot; of two frames that round to one slot the later is kept. A clip with more
     * frames a second loses some, one with fewer repeats some, and a gap repeats the frame before it. The last frame is held for one more step (the average one).
     */
    fun atThirtyFramesASecond(decoded: Sequence<Pair<Long, Gray>>): Sequence<Gray> = sequence {
        // the nearest slot, half rounding up as FFmpeg's does; a microsecond of slack, since a container's times are whole microseconds and 83333 is 83333.33 really
        fun slot(us: Double) = Math.floor((us * WORK_FPS + 500_000.0 + WORK_FPS) / 1_000_000.0).toInt()
        var prev: Gray? = null; var first = 0L; var k = 0; var lastMs = 0.0; var n = 0 // lastMs in microseconds
        for ((pts, g) in decoded) {
            if (prev == null) first = pts
            val ms = (pts - first).toDouble()
            if (prev != null) while (k < slot(ms)) { yield(prev); k++ }
            prev = g; lastMs = ms; n++
        }
        if (prev != null) { val step = if (n > 1) lastMs / (n - 1) else 1_000_000.0 / WORK_FPS; while (k < slot(lastMs + step)) { yield(prev); k++ } }
    }
}
