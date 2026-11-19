package app.epicreplay.core.signals

/** The sound half of the scan (`stages/s1_signals.py`): spectral flux onsets and loud peaks. Mono samples in -1..1 at [SR]. */
object Audio {
    const val SR = 22050
    const val N_FFT = 1024
    const val HOP = 256

    class Onsets(val times: DoubleArray, val envelope: DoubleArray)

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (N_FFT - 1)) } // np.hanning

    /** Spectral flux: how much louder each frequency got since the previous window. */
    fun onsetEnvelope(y: FloatArray): Onsets {
        if (y.size < N_FFT) return Onsets(DoubleArray(0), DoubleArray(0))
        val n = 1 + (y.size - N_FFT) / HOP
        val bins = N_FFT / 2 + 1
        var previous = DoubleArray(bins)
        val flux = DoubleArray(n)
        val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
        for (w in 0 until n) {
            for (i in 0 until N_FFT) { re[i] = y[w * HOP + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val logm = DoubleArray(bins) { Math.log1p(100.0 * Math.hypot(re[it], im[it])) }
            if (w > 0) { var s = 0.0; for (k in 0 until bins) s += Math.max(0.0, logm[k] - previous[k]); flux[w] = s }
            previous = logm
        }
        return Onsets(DoubleArray(n) { (it.toLong() * HOP + N_FFT / 2.0) / SR }, flux)
    }

    /** Peaks of the flux well above its usual level, moved back to where the rise begins. */
    fun onsetTimes(times: DoubleArray, env: DoubleArray, k: Double = 6.0): DoubleArray {
        if (env.size < 3) return DoubleArray(0)
        val med = Peaks.median(env)
        val mad = Peaks.median(DoubleArray(env.size) { Math.abs(env[it] - med) }) * 1.4826 + 1e-9
        val gap = Math.max(1, (0.05 * SR / HOP).toInt())
        return Peaks.find(env, height = med + k * mad, distance = gap).map { p ->
            var i = p
            while (i > 0 && env[i - 1] < env[i]) i--
            times[i]
        }.toDoubleArray()
    }

    class Loud(val at: Double, val peak: Float)

    /** The loudest block of 10 ms in each stretch above 75 % of the clip's loudest, stretches under 0.35 s apart merged, loudest first. */
    fun loudPeaks(y: FloatArray, blockS: Double = 0.01, floor: Double = 0.75, mergeGapS: Double = 0.35, maxEvents: Int = 6): List<Loud> {
        val blk = Math.max(1, (SR * blockS).toInt())
        val nb = y.size / blk
        if (nb == 0) return emptyList()
        val peaks = FloatArray(nb) { b -> var m = 0f; for (i in b * blk until (b + 1) * blk) m = Math.max(m, Math.abs(y[i])); m }
        val top = peaks.max()
        if (top < 0.05f) return emptyList()
        val limit = (floor * top.toDouble()).toFloat() // NumPy compares a float32 array with a Python float in float32
        class Ev(var at: Double, var end: Double, var peak: Float)
        val events = ArrayList<Ev>()
        for (i in peaks.indices) {
            if (peaks[i] < limit) continue
            val t = i * blockS
            val last = events.lastOrNull()
            if (last != null && t - last.end <= mergeGapS) {
                last.end = t + blockS
                if (peaks[i] > last.peak) { last.peak = peaks[i]; last.at = t }
            } else events.add(Ev(t, t + blockS, peaks[i]))
        }
        return events.sortedByDescending { it.peak }.take(maxEvents).map { Loud(it.at, it.peak) }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
