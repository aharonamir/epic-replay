package app.epicreplay.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faster with the pitch kept (the job of FFmpeg's `atempo` on the PC, factors 1.0 to 1.12). */
class TempoTest {
    private val sr = 48000
    private fun tone(hz: Double, seconds: Double, amp: Double = 0.4) = FloatArray((sr * seconds).toInt()) { (amp * Math.sin(2 * Math.PI * hz * it / sr)).toFloat() }
    private fun crossings(y: FloatArray) = (1 until y.size).count { (y[it - 1] < 0f) != (y[it] < 0f) }
    private fun rms(y: FloatArray) = Math.sqrt(y.sumOf { it.toDouble() * it } / y.size)

    @Test
    fun theLengthIsTheOriginalOverTheFactorAndThePitchIsKept(): Unit {
        for (f in listOf(1.03, 1.0732, 1.12)) {
            val y = tone(220.0, 1.5)
            val out = Tempo.speedUp(y, sr, f)
            assertEquals(Math.round(y.size / f).toInt(), out.size, "factor $f")
            // 220 Hz is 440 crossings a second whatever the tempo: the number of crossings falls with the length, not the rate
            val rate = crossings(out) / (out.size.toDouble() / sr)
            assertEquals(440.0, rate, 440.0 * 0.03, "factor $f: crossings a second")
            assertEquals(rms(y), rms(out), rms(y) * 0.08, "factor $f: loudness")
        }
    }

    @Test
    fun noFasterIsTheSameSamplesAndAShortTakeIsStillTheRightLength(): Unit {
        val y = tone(300.0, 0.5)
        assertTrue(Tempo.speedUp(y, sr, 1.0) === y)
        val tiny = tone(300.0, 0.02)
        assertEquals(Math.round(tiny.size / 1.1).toInt(), Tempo.speedUp(tiny, sr, 1.1).size)
        assertEquals(0, Tempo.speedUp(FloatArray(0), sr, 1.1).size)
    }

    @Test
    fun aBurstStaysWhereItIsRelativeToTheLength(): Unit {
        val y = FloatArray(sr * 2) { if (it in sr / 2 until sr) (0.5 * Math.sin(2 * Math.PI * 200.0 * it / sr)).toFloat() else 0f }
        val out = Tempo.speedUp(y, sr, 1.1)
        val first = out.indexOfFirst { Math.abs(it) > 0.1f }
        assertEquals(sr / 2 / 1.1, first.toDouble(), sr * 0.03)
    }
}
