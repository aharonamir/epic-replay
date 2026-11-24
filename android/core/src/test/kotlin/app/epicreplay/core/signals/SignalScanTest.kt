package app.epicreplay.core.signals

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The parts of NumPy and SciPy the scan stands on, against what they gave for the same input (checked on 2026-11-19 in the PC venv). */
class SignalScanTest {
    private val x = doubleArrayOf(0.0, 1.0, 3.0, 3.0, 3.0, 1.0, 0.0, 2.0, 2.0, 0.0, 5.0, 1.0, 5.0, 0.0, 4.0, 0.0, 1.0, 0.0, 2.0, 0.0)

    @Test
    fun peaksAreFoundLikeScipyDoes(): Unit {
        assertContentEquals(intArrayOf(3, 7, 10, 12, 14, 16, 18), Peaks.find(x)) // a flat top counts once, at its middle
        assertContentEquals(intArrayOf(3, 7, 12, 18), Peaks.find(x, distance = 3)) // the higher stays; of two equal, the later
        assertContentEquals(intArrayOf(3, 7, 12, 18), Peaks.find(x, distance = 3, prominence = 2.0))
        assertContentEquals(intArrayOf(3, 10, 12, 14), Peaks.find(x, height = 2.5, distance = 2))
    }

    @Test
    fun robustZIsTheDistanceAboveTheMedianInRobustDeviations(): Unit {
        val z = Peaks.robustZ(x)
        assertEquals(0.0, z[0]); assertEquals(1.349, z[2], 0.001); assertEquals(0.6745, z[7], 0.0001)
    }

    @Test
    fun theOnsetEnvelopeAndTheLoudPeakMatchNumpy(): Unit {
        val recipe = Json.parseToJsonElement("""{"seconds":2,"floor":0,"bursts":[{"at_sample":22050,"length":600,"amp":20000}]}""").jsonObject
        val y = Synthetic.audio(recipe)
        assertEquals(-0.04339599609375f, y[22050 + 5]); assertEquals(0.23211669921875f, y[22050 + 300])
        assertEquals(600, y.count { it != 0f })
        val o = Audio.onsetEnvelope(y)
        assertEquals(169, o.envelope.size)
        assertEquals(3067.78871, o.envelope.sum(), 0.001)
        assertEquals(83, o.envelope.indices.maxByOrNull { o.envelope[it] })
        assertEquals(listOf(0.9752380952380952), Audio.onsetTimes(o.times, o.envelope).toList())
        val loud = Audio.loudPeaks(y).single()
        assertEquals(1.0, loud.at); assertEquals(0.58343505859375f, loud.peak)
    }

    @Test
    fun theCurvesAreTheSameOnOneThreadAndOnMany() {
        val recipe = Json.parseToJsonElement("""{"width":160,"height":90,"fps":30,"frames":150,"pan":[2,1],"objects":[{"x":20,"y":60,"vx":8,"vy":-3,"size":10,"from":60,"to":63}]}""").jsonObject
        val one = Motion.curves(Synthetic.frames(recipe), threads = 1)
        for (threads in listOf(2, 4, 7)) { // batch sizes that do and do not divide the 150 frames
            val many = Motion.curves(Synthetic.frames(recipe), threads = threads)
            assertEquals(150, many.flow.size)
            assertContentEquals(one.flow, many.flow, "flow, $threads threads"); assertContentEquals(one.change, many.change, "change, $threads threads")
            assertContentEquals(one.camera, many.camera, "camera"); assertContentEquals(one.share, many.share, "share")
        }
        assertEquals(0.0, one.flow[0]); assertEquals(1, Motion.curves(Synthetic.frames(recipe).take(1), threads = 4).flow.size)
        assertEquals(0, Motion.curves(emptySequence(), threads = 4).flow.size)
    }
}
