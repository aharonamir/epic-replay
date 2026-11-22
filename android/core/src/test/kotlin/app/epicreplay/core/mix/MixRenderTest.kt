package app.epicreplay.core.mix

import app.epicreplay.core.audio.Loudness
import app.epicreplay.core.audio.SidechainCompressor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The parts of the mixer that the conformance cases reach only through levels in a few windows: the ducker's time constants, the limiter and the loudness target. */
class MixRenderTest {
    private val rules = MixRules.parse(File(System.getProperty("shared.dir"), "mix/rules.json").readText())
    private fun db(x: Double) = 20 * Math.log10(x)

    @Test
    fun theDuckerFollowsTheFormulaOfFfmpegsSidechainCompressor(): Unit {
        val n = 48000 * 4
        val bed = listOf(FloatArray(n) { 0.1f })
        // a key of 0.3 (a steady level) from 0.5 s to 2.0 s
        val key = listOf(FloatArray(n) { if (it in 24000 until 96000) 0.3f else 0f })
        val d = rules.duck
        val out = SidechainCompressor(d.threshold, d.ratio, d.attackMs, d.releaseMs, d.makeup, 48000).process(bed, key)[0]
        fun gain(at: Double) = out[(at * 48000).toInt()] / 0.1
        assertEquals(1.0, gain(0.4), 1e-6, "no key: the bed is as it was")
        // steady state: the soft knee is passed, so the gain is exp((ln(0.3) - ln(0.02)) / ratio + ln(0.02) - ln(0.3)) = 0.02/0.3 * 15^(1/8)
        val steady = 0.02 / 0.3 * Math.pow(15.0, 1.0 / 8)
        assertEquals(steady, gain(1.9), steady * 0.01, "steady gain")
        // the attack: the detector's own time constant is attack/4 (5 ms) on the mean square, and the reduction is in dB of that, so it is most of the way down within a few ms and all of it by 40 ms
        val total = db(steady)
        val drop = { t: Double -> db(gain(0.5 + t)) / total }
        assertTrue(drop(0.0002) < 0.6, "after 0.2 ms ${drop(0.0002)}")
        assertTrue(drop(0.0002) < drop(0.001) && drop(0.001) < drop(0.005) && drop(0.005) < drop(0.04), "it goes down steadily")
        assertTrue(drop(0.005) > 0.8 && drop(0.04) > 0.97, "after 5 ms ${drop(0.005)}, after 40 ms ${drop(0.04)}")
        // the release: 350 ms / 4 = 87.5 ms constant on the mean square: 100 ms after the key it is still well down, 3 s after it is back
        assertTrue(gain(2.0 + 0.1) < 0.5, "100 ms after the key: ${gain(2.1)}")
        assertTrue(gain(3.99) > 0.99, "2 s after the key: ${gain(3.99)}")
    }

    @Test
    fun theLimiterNeverLetsASamplePassItsLimitAndLeavesQuietAudioAlone(): Unit {
        val n = 48000
        val hot = List(2) { FloatArray(n) { i -> (1.4 * Math.sin(i * 0.05)).toFloat() } }
        val out = MixRender.limit(hot, 0.8f, 48000)
        assertTrue(out.all { c -> c.all { Math.abs(it) <= 0.8f + 1e-6f } }, "no sample over the limit")
        assertTrue(out[0].maxOf { Math.abs(it) } > 0.7f, "not flattened to nothing")
        val quiet = List(2) { FloatArray(n) { i -> (0.3 * Math.sin(i * 0.05)).toFloat() } }
        assertTrue(MixRender.limit(quiet, 0.8f, 48000) === quiet, "a signal under the limit comes back as it is")
        // a lone spike: the gain comes down before it and goes back after it, not a hole in the sound
        val spiky = listOf(FloatArray(n) { 0.2f }.also { it[24000] = 2.0f }, FloatArray(n) { 0.2f })
        val s = MixRender.limit(spiky, 0.8f, 48000)
        assertTrue(Math.abs(s[0][24000]) <= 0.8f + 1e-6f)
        assertTrue(s[0][24000 - 2000] > 0.19f && s[0][24000 + 2000] > 0.19f, "far from the spike the sound is whole")
    }

    @Test
    fun aMixIsBroughtToTheTargetAndItsPeakStaysUnderTheLimit(): Unit {
        val noise = FloatArray(48000 * 5) { i -> (((i * 1103515245L + 12345L) shr 8) and 0xFFFF).toInt().let { (it - 32768) / 32768f * 0.05f } }
        for (amp in listOf(0.2f, 2.0f)) {
            val r = MixRender.render(listOf(MixLayer("voice", listOf(FloatArray(noise.size) { noise[it] * amp }), 0.0, 0.0)), 5.0, rules)
            assertEquals(rules.targetLufs, r.lufs, 0.15, "amp $amp")
            assertTrue(r.left.maxOf { Math.abs(it) } <= Math.pow(10.0, (rules.ceilingDbtp - rules.limitMarginDb) / 20) + 1e-6, "amp $amp: sample peak")
        }
        // quiet noise with a click each second: the gain that makes the noise -14 LUFS would put the clicks far over the ceiling; the limiter holds them down
        val clicks = listOf(FloatArray(48000 * 5) { i -> noise[i] * 0.2f + if (i % 48000 == 24000) 0.5f else 0f })
        val c = MixRender.render(listOf(MixLayer("voice", clicks, 0.0, 0.0)), 5.0, rules)
        val limit = Math.pow(10.0, (rules.ceilingDbtp - rules.limitMarginDb) / 20).toFloat()
        assertTrue(c.left.maxOf { Math.abs(it) } <= limit + 1e-6f, "clicks held under ${c.left.maxOf { Math.abs(it) }}")
        assertTrue(c.lufs > rules.targetLufs - 1.5 && c.lufs <= rules.targetLufs + 0.2, "loudness ${c.lufs}")
        val silent = MixRender.render(emptyList(), 2.0, rules)
        assertEquals(96000, silent.left.size, "no layer: silence of the length")
        assertTrue(silent.lufs.isInfinite() && silent.left.all { it == 0f })
    }

    @Test
    fun theLoudnessMeterReadsAToneAtItsKnownLevel(): Unit {
        // BS.1770: a 997 Hz sine of amplitude 1.0 on one channel is -3.01 dBFS rms, which the meter reads as -3.01 LUFS (the K-weighting is 0 dB there), 0.691 under
        val sine = FloatArray(48000 * 3) { Math.sin(2 * Math.PI * 997 * it / 48000).toFloat() }
        assertEquals(-3.0, Loudness.integrated(listOf(sine), 48000), 0.1)
        assertEquals(0.0, Loudness.truePeakDb(listOf(sine)), 0.1)
        assertEquals(0.0, Loudness.integrated(listOf(sine, sine), 48000), 0.1) // two channels: +3 LU
    }
}
