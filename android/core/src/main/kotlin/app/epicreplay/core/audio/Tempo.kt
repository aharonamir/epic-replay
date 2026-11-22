package app.epicreplay.core.audio

/**
 * Faster by a factor with the pitch kept (the PC's FFmpeg `atempo`, 1.0 to 1.12 in S7): waveform-similarity overlap-add. Frames of 30 ms are taken a little
 * further on in the take than they are put in the result, each shifted by up to 8 ms to the place that continues the sound before it best, and faded
 * into each other. The result is `size / factor` samples long.
 */
object Tempo {
    fun speedUp(y: FloatArray, sr: Int, factor: Double): FloatArray {
        if (factor <= 1.0 + 1e-9 || y.size < 4) return y
        val frame = (sr * 0.030).toInt() and 1.inv() // even
        val hop = frame / 2
        val search = (sr * 0.008).toInt()
        val outLen = Math.round(y.size / factor).toInt()
        if (y.size < frame * 2) { // too short to take frames from: plain resampling of the time axis is the only thing left, so leave it as it is
            return FloatArray(outLen) { y[minOf(y.size - 1, (it * factor).toInt())] }
        }
        val window = FloatArray(frame) { (0.5 - 0.5 * Math.cos(2 * Math.PI * it / frame)).toFloat() }
        val out = FloatArray(outLen + frame)
        val norm = FloatArray(outLen + frame)
        var prevPos = 0 // where the previous frame was taken
        var m = 0
        while (m * hop < outLen) {
            val nominal = Math.round(m * hop * factor).toInt()
            var pos = nominal.coerceIn(0, y.size - frame)
            if (m > 0) {
                // the continuation of the previous frame, hop samples on, is what this frame should start like
                val want = prevPos + hop
                if (want + frame <= y.size) {
                    var best = Double.NEGATIVE_INFINITY
                    for (d in -search..search) {
                        val p = nominal + d
                        if (p < 0 || p + frame > y.size) continue
                        var c = 0.0
                        var i = 0
                        while (i < hop) { c += y[want + i].toDouble() * y[p + i]; i += 2 }
                        if (c > best) { best = c; pos = p }
                    }
                }
            }
            val at = m * hop
            for (i in 0 until frame) { if (at + i < out.size) { out[at + i] += y[pos + i] * window[i]; norm[at + i] += window[i] } }
            prevPos = pos
            m++
        }
        return FloatArray(outLen) { if (norm[it] > 1e-6f) out[it] / norm[it] else out[it] }
    }
}
