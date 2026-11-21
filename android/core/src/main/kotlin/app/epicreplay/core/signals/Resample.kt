package app.epicreplay.core.signals

/** Frames at the clip's own times, made into the constant 30 a second the PC's working copy has (`stages/s0_intake.py`: FFmpeg's `fps=30`). */
object Resample {
    const val WORK_FPS = 30.0

    /**
     * Each frame comes with its time in microseconds. Output frame k is the latest frame whose time, to the millisecond and counted from the first
     * frame, is not after k/30 s: a clip with more frames a second loses some, one with fewer repeats some, and a gap repeats the frame before it.
     * The last frame is held for one more step (the average one).
     */
    fun atThirtyFramesASecond(decoded: Sequence<Pair<Long, Gray>>): Sequence<Gray> = sequence {
        fun outMs(k: Int) = Math.round(k * 1000.0 / WORK_FPS)
        var prev: Gray? = null; var first = 0L; var k = 0; var lastMs = 0L; var n = 0
        for ((pts, g) in decoded) {
            if (prev == null) first = pts
            val ms = Math.round((pts - first) / 1000.0)
            if (prev != null) while (outMs(k) < ms) { yield(prev); k++ }
            prev = g; lastMs = ms; n++
        }
        // the last frame is held for one more step: the clip's average one, less half a millisecond so a clip already at 30 a second keeps its count
        if (prev != null) { val step = if (n > 1) lastMs.toDouble() / (n - 1) else 1000.0 / WORK_FPS; while (outMs(k) < lastMs + step - 0.5) { yield(prev); k++ } }
    }
}
