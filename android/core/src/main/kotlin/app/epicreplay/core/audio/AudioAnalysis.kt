package app.epicreplay.core.audio

/**
 * The measures that place a take (`pc/src/epic_replay/media/audio.py`, plan 4.8): levels in blocks of 10 ms, the silence trim, the first strong onset and the
 * rise nearest to a time. Mono samples in -1 to 1.
 */
object AudioAnalysis {
    const val BLOCK_S = 0.01
    const val SILENCE_DB = -40.0
    const val TAIL_DB = -30.0
    const val ONSET_SHARE = 0.3
    const val PAD_S = 0.02

    /** The root mean square of each block of 10 ms; one block for a take shorter than that. */
    fun blockRms(y: FloatArray, sr: Int): FloatArray {
        val n = maxOf(1, (sr * BLOCK_S).toInt())
        val k = y.size / n
        if (k == 0) return floatArrayOf(if (y.isEmpty()) 0f else Math.sqrt(y.sumOf { it.toDouble() * it }.div(y.size)).toFloat())
        return FloatArray(k) { b ->
            var s = 0.0
            for (i in b * n until (b + 1) * n) s += y[i].toDouble() * y[i]
            Math.sqrt(s / n).toFloat()
        }
    }

    private fun level(max: Float, db: Double): Float = (max * Math.pow(10.0, db / 20)).toFloat() // NumPy keeps a float32 times a Python float in float32

    /** The first and last sample kept by the silence trim; (0, 0) when the take is silent. */
    fun trimBounds(y: FloatArray, sr: Int): Pair<Int, Int> {
        val rms = blockRms(y, sr)
        val max = rms.max()
        if (max <= 0f) return 0 to 0
        val loud = level(max, SILENCE_DB); val tail = level(max, TAIL_DB)
        val first = rms.indexOfFirst { it >= loud }
        val last = rms.indexOfLast { it >= tail }
        val n = (sr * BLOCK_S).toInt(); val pad = (sr * PAD_S).toInt()
        return maxOf(0, first * n - pad) to minOf(y.size, (last + 1) * n + pad)
    }

    /** Seconds from the start to the first strong block (0 for silence). */
    fun firstOnset(y: FloatArray, sr: Int): Double {
        val rms = blockRms(y, sr)
        val max = rms.max()
        if (max <= 0f) return 0.0
        val l = (max * ONSET_SHARE).toFloat()
        return rms.indexOfFirst { it >= l } * BLOCK_S
    }

    /** The start of the sound nearest to [t], within [windowS]: a block that reaches the onset level after two quieter ones; [t] itself when there is none. */
    fun snapToRise(y: FloatArray, sr: Int, t: Double, windowS: Double): Double {
        val rms = blockRms(y, sr)
        val max = rms.max()
        if (max <= 0f) return t
        val l = (max * ONSET_SHARE).toFloat()
        val rises = (2 until rms.size).filter { rms[it] >= l && l > rms[it - 1] && rms[it - 2] < l }.map { it * BLOCK_S }
        return rises.filter { Math.abs(it - t) <= windowS }.minByOrNull { Math.abs(it - t) } ?: t
    }
}
