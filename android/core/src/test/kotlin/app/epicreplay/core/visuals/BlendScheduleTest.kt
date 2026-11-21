package app.epicreplay.core.visuals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlendScheduleTest {
    @Test
    fun aGapOfFourStepsGivesQuarterHalfThreeQuartersAndTheFrameItself(): Unit {
        val s = BlendSchedule.steps(0, 133_333)
        assertEquals(listOf(33_333L, 66_667L, 100_000L, 133_333L), s.map { it.timeUs })
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0), s.map { it.weight }.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(1.0, s.last().weight)
    }

    @Test
    fun theMicrosecondsOfTheGapDoNotChangeTheCount(): Unit {
        for (gap in listOf(133_332L, 133_333L, 133_334L, 133_340L)) assertEquals(4, BlendSchedule.steps(1_000_000, 1_000_000 + gap).size, "gap $gap")
        assertEquals(1_133_340L, BlendSchedule.steps(1_000_000, 1_133_340).last().timeUs) // the last frame is shown at its own time
    }

    @Test
    fun aGapOfOneStepIsTheLaterFrameAndASmallerGapNothing(): Unit {
        assertEquals(listOf(1.0), BlendSchedule.steps(0, 33_333).map { it.weight })
        assertEquals(emptyList(), BlendSchedule.steps(0, 20_000).map { it.timeUs })
        assertEquals(emptyList(), BlendSchedule.steps(5, 5).map { it.timeUs })
        assertEquals(emptyList(), BlendSchedule.steps(9, 5).map { it.timeUs })
    }

    @Test
    fun anUnevenGapKeepsTheWeightsInstep(): Unit {
        val s = BlendSchedule.steps(0, 100_000) // three steps of 33,333
        assertEquals(3, s.size)
        assertTrue(s.zipWithNext().all { (a, b) -> a.weight < b.weight && a.timeUs < b.timeUs })
        val odd = BlendSchedule.steps(0, 150_000) // 4.5 steps: four, the last one short of the frame
        assertEquals(4, odd.size)
        assertTrue(odd.last().weight < 1.0)
    }
}
