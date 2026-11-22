package app.epicreplay.core.audio

/** Where a placed take is found in the finished audio (`stages/s10_compose.py landed`): the position near the plan where the take correlates best, by energy-normalised cross-correlation. */
object Landed {
    const val SEARCH_S = 0.5

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * Math.PI / len * (if (inverse) 1 else -1)
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    fun landed(take: FloatArray, out: FloatArray, plannedStart: Double, sampleRate: Int): Double {
        val a = maxOf(0, ((plannedStart - SEARCH_S) * sampleRate).toInt())
        val b = minOf(out.size, ((plannedStart + SEARCH_S) * sampleRate).toInt() + take.size)
        if (b - a < take.size || take.isEmpty()) return plannedStart
        val m = b - a; val k = take.size
        var size = 1
        while (size < m + k) size = size shl 1
        val sr = DoubleArray(size); val si = DoubleArray(size); val tr = DoubleArray(size); val ti = DoubleArray(size)
        for (i in 0 until m) sr[i] = out[a + i].toDouble()
        for (i in 0 until k) tr[i] = take[i].toDouble()
        fft(sr, si, false); fft(tr, ti, false)
        for (i in 0 until size) { // seg * conj(take)
            val xr = sr[i] * tr[i] + si[i] * ti[i]; val xi = si[i] * tr[i] - sr[i] * ti[i]
            sr[i] = xr; si[i] = xi
        }
        fft(sr, si, true)
        val run = DoubleArray(m + 1)
        for (i in 0 until m) run[i + 1] = run[i] + out[a + i].toDouble() * out[a + i]
        var best = Double.NEGATIVE_INFINITY; var at = 0
        for (j in 0..(m - k)) {
            val v = sr[j] / (Math.sqrt(maxOf(run[j + k] - run[j], 0.0)) + 1e-9)
            if (v > best) { best = v; at = j }
        }
        return (a + at).toDouble() / sampleRate
    }
}
