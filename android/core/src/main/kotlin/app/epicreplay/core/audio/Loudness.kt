package app.epicreplay.core.audio

/**
 * EBU R128 / ITU-R BS.1770-4 loudness and true peak, as FFmpeg's `ebur128=peak=true` reports them (`media/audio.py loudness`): K-weighted power of 400 ms blocks every
 * 100 ms, summed over the channels, the absolute gate at -70 LUFS and the relative gate 10 LU under the mean of what passed; the true peak by four times oversampling
 * (FFmpeg goes to 192 kHz with swresample's Kaiser-windowed sinc).
 */
object Loudness {
    private const val ABSOLUTE_GATE = -70.0

    // BS.1770 K-weighting at 48 kHz: the high-shelf and the high-pass
    private val SHELF_B = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
    private val SHELF_A = doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585)
    private val HIPASS_B = doubleArrayOf(1.0, -2.0, 1.0)
    private val HIPASS_A = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621)

    private fun biquad(x: DoubleArray, b: DoubleArray, a: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = b[0] * x[i] + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
        }
        return y
    }

    /** Integrated loudness in LUFS of [channels] at 48 kHz, or negative infinity when no block passes the gates. */
    fun integrated(channels: List<FloatArray>, sampleRate: Int): Double {
        require(sampleRate == 48000) { "the K-weighting is for 48 kHz" }
        val n = channels.minOf { it.size }
        val block = sampleRate * 4 / 10; val step = sampleRate / 10
        if (n < block) return Double.NEGATIVE_INFINITY
        // the square of each K-weighted sample, summed over the channels, with its running sum
        val power = DoubleArray(n)
        for (c in channels) {
            val k = biquad(biquad(DoubleArray(n) { c[it].toDouble() }, SHELF_B, SHELF_A), HIPASS_B, HIPASS_A)
            for (i in 0 until n) power[i] += k[i] * k[i]
        }
        val run = DoubleArray(n + 1)
        for (i in 0 until n) run[i + 1] = run[i] + power[i]
        val energies = ArrayList<Double>()
        var s = 0
        while (s + block <= n) { energies += (run[s + block] - run[s]) / block; s += step }
        fun lufs(e: Double) = -0.691 + 10 * Math.log10(e)
        val passed = energies.filter { it > 0 && lufs(it) > ABSOLUTE_GATE }
        if (passed.isEmpty()) return Double.NEGATIVE_INFINITY
        val relative = lufs(passed.average()) - 10.0
        val kept = passed.filter { lufs(it) > relative }
        return if (kept.isEmpty()) Double.NEGATIVE_INFINITY else lufs(kept.average())
    }

    // the interpolator: a Kaiser-windowed sinc of 32 taps for each of the four phases (swresample's default filter, beta 9; a cutoff of 1.0 matches its peaks to 0.05 dB)
    private const val TAPS = 32
    private const val FACTOR = 4
    private val PHASES: Array<DoubleArray> by lazy {
        fun i0(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (t > 1e-12 * s) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; k++ }; return s }
        val cutoff = 1.0; val beta = 9.0
        Array(FACTOR) { p ->
            val h = DoubleArray(TAPS)
            var sum = 0.0
            for (j in 0 until TAPS) {
                val x = (j - (TAPS / 2 - 1)) - p.toDouble() / FACTOR // distance from the output instant, in input samples
                val sinc = if (x == 0.0) cutoff else Math.sin(Math.PI * cutoff * x) / (Math.PI * x)
                val r = x / (TAPS / 2.0)
                val w = if (Math.abs(r) >= 1) 0.0 else i0(beta * Math.sqrt(1 - r * r)) / i0(beta)
                h[j] = sinc * w; sum += h[j]
            }
            for (j in 0 until TAPS) h[j] /= sum
            h
        }
    }

    /** The true peak in dBFS: the largest absolute value of any channel on the four times oversampled signal (the samples themselves too). */
    fun truePeakDb(channels: List<FloatArray>): Double {
        var peak = 0.0
        for (c in channels) {
            for (i in c.indices) {
                peak = maxOf(peak, Math.abs(c[i].toDouble()))
                for (p in 1 until FACTOR) {
                    val h = PHASES[p]
                    var v = 0.0
                    for (j in 0 until TAPS) { val k = i + j - (TAPS / 2 - 1); if (k in c.indices) v += h[j] * c[k] }
                    peak = maxOf(peak, Math.abs(v))
                }
            }
        }
        return if (peak <= 0) Double.NEGATIVE_INFINITY else 20 * Math.log10(peak)
    }
}
