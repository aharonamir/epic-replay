package app.epicreplay.core.mix

import app.epicreplay.core.audio.Loudness
import app.epicreplay.core.audio.SidechainCompressor

/** One layer of the mix with its sound: 1 or 2 channels at 48 kHz, where it starts, its gain, whether it loops and, for the clip's own sound, the source range. */
class MixLayer(val kind: String, val channels: List<FloatArray>, val startS: Double, val gainDb: Double, val loop: Boolean = false, val inS: Double? = null, val outS: Double? = null)

class MixResult(val left: FloatArray, val right: FloatArray, val lufs: Double, val peakDbfs: Double)

/**
 * S8 on the phone (`stages/s8_mix.py`, plan 4.9): the layers summed on one timeline, the bed ducked under the voice by the PC's sidechain compressor, the sum brought to the
 * loudness target under a peak limiter. FFmpeg's graph is followed step by step (mono layers into stereo at -3 dB each, `adelay` in whole milliseconds, the bed's fades
 * after the loop is cut to the length, `amix normalize=0` as a plain sum, the length fixed at the end); the two-pass `loudnorm` is replaced by one gain and the PC's own
 * correction under a limiter, so the result matches in loudness and level within a dB, not sample for sample (conformance area `compose`, `mix.render`).
 */
object MixRender {
    private const val SQRT_HALF = 0.7071067811865476f

    private fun stereo(c: List<FloatArray>): List<FloatArray> = if (c.size >= 2) listOf(c[0], c[1]) else listOf(FloatArray(c[0].size) { c[0][it] * SQRT_HALF }, FloatArray(c[0].size) { c[0][it] * SQRT_HALF })

    /** The layer as it stands on the timeline: stereo, [n] samples long, with its trim, loop, fades, gain and delay applied in the order of the PC's chain. */
    private fun place(l: MixLayer, n: Int, rules: MixRules): List<FloatArray> {
        val sr = rules.sampleRate
        var ch = stereo(l.channels)
        if (l.kind == "original") {
            val a = Math.round(l.inS!! * sr).toInt().coerceIn(0, ch[0].size); val b = Math.round(l.outS!! * sr).toInt().coerceIn(a, ch[0].size)
            ch = ch.map { it.copyOfRange(a, b) }
        }
        val gain = Math.pow(10.0, l.gainDb / 20.0).toFloat()
        val delay = Math.round(l.startS * 1000).toInt() * sr / 1000
        val out = List(2) { FloatArray(n) }
        val fadeIn = rules.fadeInS; val fadeOut = rules.fadeOutS
        val duration = n.toDouble() / sr
        val outStart = maxOf(0.0, duration - fadeOut)
        for (c in 0..1) {
            val src = ch[c]
            if (src.isEmpty()) continue
            val len = if (l.loop) n else src.size
            for (i in 0 until len) {
                val at = i + delay
                if (at >= n) break
                var v = src[if (l.loop) i % src.size else i] * gain
                if (l.loop) { // fades follow the cut to the length: `atrim=0:duration,afade in,afade out`
                    val t = i.toDouble() / sr
                    if (t < fadeIn) v *= (t / fadeIn).toFloat()
                    if (t >= outStart) v *= maxOf(0.0, 1.0 - (t - outStart) / fadeOut).toFloat()
                }
                out[c][at] = v
            }
        }
        return out
    }

    private fun sum(parts: List<List<FloatArray>>, n: Int): List<FloatArray> {
        val out = List(2) { FloatArray(n) }
        for (p in parts) for (c in 0..1) { val o = out[c]; val s = p[c]; for (i in 0 until n) o[i] += s[i] }
        return out
    }

    /** A peak limiter: a gain that never lets a sample pass [limit], reached over 5 ms ahead and let go over the same time. */
    fun limit(x: List<FloatArray>, limit: Float, sr: Int): List<FloatArray> {
        val n = x[0].size
        val la = maxOf(1, sr * 5 / 1000)
        val need = FloatArray(n) { i -> val p = maxOf(Math.abs(x[0][i]), Math.abs(x[1][i])); if (p > limit) limit / p else 1f }
        if (need.all { it >= 1f }) return x
        // the smallest gain within the lookahead on either side, then averaged over the lookahead so the gain moves smoothly and never goes above what is needed
        val low = FloatArray(n)
        val dq = java.util.ArrayDeque<Int>()
        for (i in -la until n + la) {
            if (i + la < n) { val j = i + la; while (dq.isNotEmpty() && need[dq.last()] >= need[j]) dq.removeLast(); dq.addLast(j) }
            while (dq.isNotEmpty() && dq.first() < i - la) dq.removeFirst()
            if (i in 0 until n) low[i] = need[dq.first()]
        }
        val run = DoubleArray(n + 1)
        for (i in 0 until n) run[i + 1] = run[i] + low[i]
        val g = FloatArray(n) { i -> val a = maxOf(0, i - la / 2); val b = minOf(n, i + la / 2 + 1); ((run[b] - run[a]) / (b - a)).toFloat() }
        return x.map { c -> FloatArray(n) { c[it] * minOf(g[it], 1f) } }
    }

    fun render(layers: List<MixLayer>, durationS: Double, rules: MixRules): MixResult {
        val sr = rules.sampleRate
        val n = Math.round(durationS * sr).toInt()
        val placed = layers.map { it to place(it, n, rules) }
        val voices = placed.filter { it.first.kind == "voice" }.map { it.second }
        var others = placed.filter { it.first.kind != "voice" }
        val vox = if (voices.isNotEmpty()) sum(voices, n) else null
        val parts = ArrayList<List<FloatArray>>()
        for ((l, p) in others) {
            if (l.kind == "bed" && vox != null) {
                val d = rules.duck
                parts += SidechainCompressor(d.threshold, d.ratio, d.attackMs, d.releaseMs, d.makeup, sr).process(p, vox)
            } else parts += p
        }
        if (vox != null) parts += vox
        var mix = sum(parts, n)
        // the loudness target: one gain, under the peak limit, then the PC's own correction (`s8_mix.normalise`)
        val limitLin = Math.pow(10.0, (rules.ceilingDbtp - rules.limitMarginDb) / 20.0).toFloat()
        var lufs = Loudness.integrated(mix, sr)
        if (lufs.isInfinite()) return MixResult(mix[0], mix[1], lufs, Loudness.truePeakDb(mix))
        for (pass in 0..1) {
            val g = Math.pow(10.0, (rules.targetLufs - lufs) / 20.0).toFloat()
            mix = limit(mix.map { c -> FloatArray(n) { c[it] * g } }, limitLin, sr)
            lufs = Loudness.integrated(mix, sr)
        }
        return MixResult(mix[0], mix[1], lufs, Loudness.truePeakDb(mix))
    }
}
