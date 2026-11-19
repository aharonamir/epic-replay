package app.epicreplay.core.signals

import kotlin.math.floor
import kotlin.math.min

class Candidate(val frame: Int, val t: Double, val score: Double, val motionZ: Double, val soundZ: Double, val soundOnsetT: Double?)

class Signals(
    val frames: Int, val fps: Double, val hasSound: Boolean, val cuts: List<Int>,
    val onsetsT: List<Double>, val loudPeaksT: List<Double>, val candidates: List<Candidate>,
)

/** `s1_signals.scan_signals`: the scan on what a decoder gives, grey frames and mono audio at [Audio.SR] that starts `offset` s after the video. */
object SignalScan {
    fun findCuts(share: DoubleArray, level: Double = 0.35): List<Int> {
        val usual = if (share.isEmpty()) 0.0 else Peaks.median(share)
        return share.indices.filter { share[it] > level && share[it] > 8 * usual }
    }

    fun scan(frames: Sequence<Gray>, fps: Double, y: FloatArray, offset: Double = 0.0, top: Int = 3, minGapS: Double = 0.5): Signals {
        val c = Motion.curves(frames)
        val n = c.flow.size
        val t = DoubleArray(n) { it / fps }
        val cuts = findCuts(c.share)
        val keep = BooleanArray(n) { true }
        for (cut in cuts) for (i in Math.max(0, cut - 3)..min(n - 1, cut + 3)) keep[i] = false
        val medFlow = Peaks.median(c.flow); val medChange = Peaks.median(c.change)
        val fz = Peaks.robustZ(DoubleArray(n) { if (keep[it]) c.flow[it] else medFlow })
        val cz = Peaks.robustZ(DoubleArray(n) { if (keep[it]) c.change[it] else medChange })
        val mz = DoubleArray(n) { Math.max(fz[it], cz[it]) }

        val hasSound = y.size > Audio.SR / 10 && (y.maxOfOrNull { Math.abs(it) } ?: 0f).toDouble() > 1e-3
        var sz = DoubleArray(n)
        var onsets = DoubleArray(0)
        var loud = emptyList<Double>()
        if (hasSound) {
            val o = Audio.onsetEnvelope(y)
            val times = DoubleArray(o.times.size) { o.times[it] + offset }
            val perFrame = DoubleArray(n)
            for (i in times.indices) {
                val f = floor(times[i] * fps).toInt().coerceIn(0, n - 1)
                perFrame[f] = Math.max(perFrame[f], o.envelope[i])
            }
            for (i in 0 until n) if (t[i] < 0.15) perFrame[i] = 0.0 // encoder start-up click, not an event
            sz = Peaks.robustZ(perFrame)
            onsets = Audio.onsetTimes(times, o.envelope).filter { it >= 0.15 }.toDoubleArray()
            loud = Audio.loudPeaks(y).map { it.at + offset }.filter { it >= 0.15 }
        }
        val score = DoubleArray(n) { Math.min(mz[it], 12.0) + Math.min(sz[it], 12.0) }
        val peaks = Peaks.find(score, distance = Math.max(1, (minGapS * fps).toInt()), prominence = 2.0)
        // highest score first; equal scores by the uncapped sum of the z-scores; then the earlier frame
        val order = peaks.sortedWith(compareBy<Int>({ -score[it] }, { -(mz[it] + sz[it]) })).take(top)
        val candidates = order.map { f ->
            val near = onsets.filter { it >= t[f] - 0.25 && it <= t[f] + 0.1 }
            Candidate(f, t[f], rint(score[f], 2), rint(mz[f], 2), rint(sz[f], 2), near.minOrNull())
        }
        return Signals(n, fps, hasSound, cuts, onsets.toList(), loud, candidates)
    }

    /** Python's `round(x, digits)`: half to even. */
    fun rint(x: Double, digits: Int): Double = java.math.BigDecimal(x).setScale(digits, java.math.RoundingMode.HALF_EVEN).toDouble() // the exact binary value, as Python rounds
}
