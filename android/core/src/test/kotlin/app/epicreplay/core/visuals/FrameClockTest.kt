package app.epicreplay.core.visuals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The steady 30 a second clock of a segment, on source frames of any rate (the PC's `fps=30` and `framerate`). */
class FrameClockTest {
    private fun run(times: List<Long>, blend: Boolean, limit: Int? = null): List<ClockOut> {
        val c = FrameClock(30.0, blend, limit)
        return times.flatMap { c.onFrame(it) } + c.finish()
    }
    private fun at(fps: Double, n: Int, stretch: Double = 1.0) = (0 until n).map { Math.round(it * 1e6 / fps * stretch) }

    @Test
    fun aSlowedThirtyFpsClipHasFourPicturesPerSourceFrameMixedQuarterHalfThreeQuartersAndTheFrame(): Unit {
        val outs = run(at(30.0, 15, 4.0), blend = true) // 15 source frames, each 133 ms apart
        assertEquals(60, outs.size) // 1 + 14 * 4 + the last frame held for its own gap (3)
        assertEquals((0 until 60).toList(), outs.map { it.slot })
        assertEquals(listOf(1.0, 0.25, 0.5, 0.75, 1.0), outs.take(5).map { Math.round(it.weight * 1000) / 1000.0 })
        assertEquals(listOf(0.0, 0.0, 0.0), outs.takeLast(3).map { it.weight }) // held
        assertTrue(outs.zipWithNext().all { (a, b) -> b.timeUs - a.timeUs in 33_332L..33_335L }, "a steady 30 a second")
    }

    @Test
    fun aThirtyFpsCutIsUnchangedAndASixtyFpsOneKeepsEverySecondFrame(): Unit {
        val same = run(at(30.0, 30), blend = false)
        assertEquals(30, same.size); assertTrue(same.all { it.weight == 1.0 || it.slot == 0 })
        val sixty = run(at(60.0, 60), blend = false) // one second of 60 fps is 30 pictures
        assertEquals(30, sixty.size)
        // each picture is a source frame: weight 0 or 1 only
        assertTrue(sixty.all { it.weight == 0.0 || it.weight == 1.0 })
    }

    @Test
    fun aTwentyFiveFpsCutRepeatsAFrameNowAndThen(): Unit {
        val outs = run(at(25.0, 25), blend = false, limit = 30)
        assertEquals(30, outs.size)
        assertEquals((0 until 30).toList(), outs.map { it.slot })
    }

    @Test
    fun theGridIsOneForTheWholeStreamSoNoRemainderIsLost(): Unit {
        // frames 50 ms apart (20 a second): slots at 0, 33.3, 66.7, 100, 133.3 ...
        val outs = run(listOf(0L, 50_000L, 100_000L, 150_000L), blend = true)
        assertEquals(listOf(0, 1, 2, 3, 4), outs.take(5).map { it.slot })
        assertEquals(listOf(0L, 33_333L, 66_667L, 100_000L, 133_333L), outs.take(5).map { it.timeUs })
        // frames 20 ms apart: still a picture every 33 ms
        val dense = run((0..10).map { it * 20_000L }, blend = false)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), dense.map { it.slot })
    }

    @Test
    fun aLimitMakesExactlyThatManyPicturesAndHoldsTheLastFrameWhenTheSourceEndsShort(): Unit {
        val short = run(at(30.0, 10, 4.0), blend = true, limit = 55) // 40 pictures of the cut, 15 held
        assertEquals(55, short.size)
        assertTrue(short.takeLast(15).all { it.weight == 0.0 })
        val long = run(at(30.0, 30), blend = false, limit = 20) // the source has more than are wanted
        assertEquals(20, long.size)
        assertEquals(0, run(at(30.0, 5), blend = false, limit = 0).size)
    }

    @Test
    fun theInterpolationLimitsOfFramerateSnapAWeightNearlyOneFrame(): Unit {
        val c = FrameClock(30.0, true)
        c.onFrame(0)
        val outs = c.onFrame(34_000) // a gap barely longer than a step: the slot is at 33,333 us, weight 0.98
        assertEquals(1, outs.size); assertEquals(1.0, outs[0].weight)
        val c2 = FrameClock(30.0, true)
        c2.onFrame(0)
        assertEquals(0, c2.onFrame(30_000).size) // under a step: no picture yet
    }

    @Test
    fun aGapOfMoreThanOneFrameKeepsTheStreamFlowingAcrossIt(): Unit {
        val outs = run(listOf(0L, 33_333L, 200_000L, 233_333L), blend = false)
        assertEquals((0 until 8).toList(), outs.map { it.slot }) // 0 to 233 ms: eight slots, the gap repeats the frame before it
    }
}
