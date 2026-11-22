package app.epicreplay.core.audio

/**
 * FFmpeg's `sidechaincompress` (`af_sidechaincompress.c`, defaults of the filter except what S8 sets: threshold, ratio, attack, release, makeup): the bed is turned down
 * while the key (the voice) is loud. Detection is on the mean square of the key's channels, smoothed by an attack and a release coefficient, with a soft knee of 2.82843.
 */
class SidechainCompressor(private val threshold: Double, private val ratio: Double, attackMs: Double, releaseMs: Double, private val makeup: Double, sampleRate: Int) {
    private val knee = 2.82843
    private val thres = Math.log(threshold)
    private val linKneeStart = threshold / Math.sqrt(knee)
    private val linKneeStop = threshold * Math.sqrt(knee)
    private val adjKneeStart = linKneeStart * linKneeStart
    private val kneeStart = Math.log(linKneeStart)
    private val kneeStop = Math.log(linKneeStop)
    private val compressedKneeStop = (kneeStop - thres) / ratio + thres
    private val attackCoeff = minOf(1.0, 1.0 / (attackMs * sampleRate / 4000.0))
    private val releaseCoeff = minOf(1.0, 1.0 / (releaseMs * sampleRate / 4000.0))
    private var linSlope = 0.0

    private fun hermite(x: Double, x0: Double, x1: Double, p0: Double, p1: Double, m0In: Double, m1In: Double): Double {
        val width = x1 - x0
        val t = (x - x0) / width
        val m0 = m0In * width; val m1 = m1In * width
        val t2 = t * t; val t3 = t2 * t
        val ct2 = -3 * p0 - 2 * m0 + 3 * p1 - m1
        val ct3 = 2 * p0 + m0 - 2 * p1 + m1
        return ct3 * t3 + ct2 * t2 + m0 * t + p0
    }

    private fun gain(): Double {
        val slope = Math.log(linSlope) * 0.5 // detection by the mean square
        var g = (slope - thres) / ratio + thres
        val delta = 1.0 / ratio
        if (knee > 1.0 && slope < kneeStop) g = hermite(slope, kneeStart, kneeStop, kneeStart, compressedKneeStop, 1.0, delta)
        return Math.exp(g - slope)
    }

    /** The [bed] (channels of equal length) turned down by the [key] (channels, the same length): the compressed channels. */
    fun process(bed: List<FloatArray>, key: List<FloatArray>): List<FloatArray> {
        val n = bed[0].size
        val out = bed.map { FloatArray(n) }
        for (i in 0 until n) {
            var abs = 0.0
            for (c in key) abs += Math.abs(c[i].toDouble())
            abs /= key.size
            abs *= abs
            linSlope += (abs - linSlope) * (if (abs > linSlope) attackCoeff else releaseCoeff)
            val g = if (linSlope > 0.0 && linSlope > adjKneeStart) gain() else 1.0
            for (c in bed.indices) out[c][i] = (bed[c][i] * (g * makeup)).toFloat()
        }
        return out
    }
}
