package app.epicreplay.core.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** The retry and the software fallback of the export (T423 done-when: a hardware encoder that refuses its output). */
class ExportPolicyTest {
    private class Fake(val refuses: Set<Int>) : ExportAttempt {
        val tried = ArrayList<EncoderChoice>()
        override suspend fun run(encoder: EncoderChoice) {
            tried += encoder
            if (tried.size - 1 in refuses) throw ExportFailure("Invalid Nal units from the muxer")
        }
    }

    @Test
    fun anExportThatWorksIsNotRetriedAndLogsNothing(): Unit = runBlocking {
        val f = Fake(emptySet()); val log = ArrayList<String>()
        assertEquals(EncoderChoice.DEFAULT, ExportPolicy.run(f) { log += it })
        assertEquals(listOf(EncoderChoice.DEFAULT), f.tried); assertTrue(log.isEmpty())
    }

    @Test
    fun oneRefusalIsRetriedOnTheSameEncoderAndTold(): Unit = runBlocking {
        val f = Fake(setOf(0)); val log = ArrayList<String>()
        assertEquals(EncoderChoice.DEFAULT, ExportPolicy.run(f) { log += it })
        assertEquals(listOf(EncoderChoice.DEFAULT, EncoderChoice.DEFAULT), f.tried)
        assertTrue(log.size == 2 && "Invalid Nal units" in log[0] && "trying again" in log[0] && "retry" in log[1], log.toString())
    }

    @Test
    fun theHardwareEncoderRefusingTwiceMovesTheExportToTheSoftwareEncoder(): Unit = runBlocking {
        val f = Fake(setOf(0, 1)); val log = ArrayList<String>()
        assertEquals(EncoderChoice.SOFTWARE, ExportPolicy.run(f) { log += it })
        assertEquals(listOf(EncoderChoice.DEFAULT, EncoderChoice.DEFAULT, EncoderChoice.SOFTWARE), f.tried)
        assertEquals(3, log.size); assertTrue("software encoder" in log[1] && "software encoder" in log[2], log.toString())
    }

    @Test
    fun anExportThatFailsOnEveryEncoderThrowsTheLastFailure(): Unit {
        val f = Fake(setOf(0, 1, 2))
        val e = assertFailsWith<ExportFailure> { runBlocking { ExportPolicy.run(f) } }
        assertTrue("Invalid Nal units" in e.message!!); assertEquals(3, f.tried.size)
    }

    @Test
    fun aCancelledExportIsNotRetried(): Unit {
        var n = 0
        assertFailsWith<CancellationException> { runBlocking { ExportPolicy.run({ n++; throw CancellationException("stop") }) } }
        assertEquals(1, n)
    }
}

class ComposeProblemsTest {
    private val sb = kotlinx.serialization.json.Json.parseToJsonElement("""{"fps":30,"duration_s":28.9,"width":1080,"height":1920}""").let { it as kotlinx.serialization.json.JsonObject }
    private fun hit(ms: Int) = kotlinx.serialization.json.Json.parseToJsonElement("""{"line_id":"l3","error_ms":$ms}""") as kotlinx.serialization.json.JsonObject

    @Test
    fun aFileOfTheStoryboardsLengthSizeAndTimingHasNoProblems() {
        assertEquals(emptyList(), ComposeMeasure.problems(sb, 867, 28.9, listOf(hit(5), hit(-149)), 1080, 1920))
    }

    @Test
    fun eachWayAFileCanBeWrongIsNamed() {
        val p = ComposeMeasure.problems(sb, 860, 27.0, listOf(hit(151)), 1920, 1080)
        assertEquals(4, p.size, p.toString())
        assertTrue(p.any { "860 frames" in it } && p.any { "sound" in it } && p.any { "1920x1080" in it } && p.any { "151 ms" in it })
    }
}
