package app.epicreplay.core.signals

import kotlin.test.Test
import kotlin.test.assertEquals

/** The frames of any clip as 30 a second, counted by the picture each carries (a one-pixel picture whose value is the source frame number). */
class ResampleTest {
    private fun clip(fps: Double, n: Int, startUs: Long = 0L) = (0 until n).asSequence().map { i -> (startUs + Math.round(i * 1e6 / fps)) to Gray(1, 1, byteArrayOf(i.toByte())) }
    private fun picked(s: Sequence<Pair<Long, Gray>>) = Resample.atThirtyFramesASecond(s).map { it[0, 0] }.toList()

    @Test
    fun aThirtyFramesASecondClipIsUnchanged(): Unit {
        assertEquals((0 until 90).toList(), picked(clip(30.0, 90)))
    }

    @Test
    fun aSixtyFramesASecondClipKeepsEverySecondFrame(): Unit {
        assertEquals((0 until 60).map { it * 2 }, picked(clip(60.0, 120)))
    }

    @Test
    fun aTwentyFiveFramesASecondClipRepeatsAFrameNowAndThen(): Unit {
        val out = picked(clip(25.0, 50)) // two seconds of 25 become 60 frames, five of the 50 repeated
        assertEquals(60, out.size)
        assertEquals(50, out.toSet().size)
        assertEquals(0, out.first()); assertEquals(49, out.last())
        assertEquals(out.sorted(), out) // never backwards
        // each frame takes the slot nearest its time (40 ms is slot 1.2, 120 ms is 3.6 so slot 4) and a missing slot repeats the one before
        assertEquals(listOf(0, 1, 2, 2, 3, 4, 5, 6, 7, 7, 8), out.take(11))
    }

    @Test
    fun theClockStartsAtTheFirstFrameAndTheLastFrameIsHeldForOneStep(): Unit {
        assertEquals((0 until 30).toList(), picked(clip(30.0, 30, startUs = 2_000_000L)))
        assertEquals(listOf(0, 0, 1), picked(sequenceOf(0L to Gray(1, 1, byteArrayOf(0)), 50_000L to Gray(1, 1, byteArrayOf(1))))) // a gap repeats the frame before it (0, 33 ms: frame 0; 67 ms: frame 1)
        assertEquals(emptyList(), picked(emptySequence()))
    }
}
