package app.epicreplay.core.signals

/** The parts of NumPy and SciPy that the scan uses, written out so the Kotlin scan picks what the Python one picks. */
internal object Peaks {
    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val s = x.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** `robust_z`: how far above the median in robust standard deviations, never below 0. */
    fun robustZ(x: DoubleArray): DoubleArray {
        val med = median(x)
        val mad = median(DoubleArray(x.size) { Math.abs(x[it] - med) }) * 1.4826 + 1e-6
        return DoubleArray(x.size) { Math.max(0.0, (x[it] - med) / mad) }
    }

    /** `scipy.signal.find_peaks`: local maxima (a flat top counts once, at its middle), then `height`, `distance` and `prominence`, in that order. */
    fun find(x: DoubleArray, height: Double? = null, distance: Int? = null, prominence: Double? = null): IntArray {
        var peaks = localMaxima(x)
        if (height != null) peaks = peaks.filter { x[it] >= height }.toIntArray()
        if (distance != null) peaks = byDistance(x, peaks, distance)
        if (prominence != null) peaks = peaks.filter { prominenceOf(x, it) >= prominence }.toIntArray()
        return peaks
    }

    private fun localMaxima(x: DoubleArray): IntArray {
        val out = ArrayList<Int>()
        var i = 1
        val last = x.size - 1
        while (i < last) {
            if (x[i - 1] < x[i]) {
                var ahead = i + 1
                while (ahead < last && x[ahead] == x[i]) ahead++
                if (x[ahead] < x[i]) {
                    out.add((i + ahead - 1) / 2)
                    i = ahead
                }
            }
            i++
        }
        return out.toIntArray()
    }

    private fun byDistance(x: DoubleArray, peaks: IntArray, distance: Int): IntArray {
        val keep = BooleanArray(peaks.size) { true }
        // highest first; equal heights: the later peak first (a stable ascending sort, read from its end)
        val order = peaks.indices.sortedBy { x[peaks[it]] }
        for (j in order.reversed()) {
            if (!keep[j]) continue
            var k = j - 1
            while (k >= 0 && peaks[j] - peaks[k] < distance) { keep[k] = false; k-- }
            k = j + 1
            while (k < peaks.size && peaks[k] - peaks[j] < distance) { keep[k] = false; k++ }
        }
        return peaks.filterIndexed { i, _ -> keep[i] }.toIntArray()
    }

    private fun prominenceOf(x: DoubleArray, peak: Int): Double {
        var leftMin = x[peak]
        var i = peak
        while (i >= 0 && x[i] <= x[peak]) { if (x[i] < leftMin) leftMin = x[i]; i-- }
        var rightMin = x[peak]
        i = peak
        while (i < x.size && x[i] <= x[peak]) { if (x[i] < rightMin) rightMin = x[i]; i++ }
        return x[peak] - Math.max(leftMin, rightMin)
    }
}
