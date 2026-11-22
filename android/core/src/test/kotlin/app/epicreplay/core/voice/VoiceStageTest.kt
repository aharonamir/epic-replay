package app.epicreplay.core.voice

import app.epicreplay.core.audio.AudioAnalysis
import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.conformance.ConformanceRunner
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** S7 on the PC's recorded takes of the sample run: the placement of the Python run. */
class VoiceStageTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val recordings = File(shared.parentFile, "pc/tests/fixtures/recordings")
    private fun json(f: String) = Json.parseToJsonElement(File(shared, f).readText()).jsonObject
    private val script = json("fixtures/sample_run/script.json")
    private val storyboard = json("fixtures/sample_run/storyboard.json")
    private val expected = json("fixtures/voice/sample_run_voice.json")

    private fun adapter(ledger: Ledger? = null) = SpeechAdapter({ error("no network") }.let { _ -> object : app.epicreplay.core.net.HttpTransport {
        override suspend fun send(request: app.epicreplay.core.net.HttpRequest, profileId: String) = error("a replay sends nothing")
    } }, { null }, "eleven_v3", { error("no conversion in a replay") }, ledger, Recorder(ProviderMode.REPLAY, recordings))

    @Test
    fun theRecordedTakesOfTheSampleRunAreFoundUnderThePcsKeysAndPlacedAsThePythonRunPlacedThem(): Unit {
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.0001), today = { "2026-11-21" }, now = { "t" })
        val r = runBlocking { VoiceStage(adapter(ledger)).run(script, storyboard) }
        assertEquals("ok", r.status, r.note)
        assertEquals(0.0, ledger.runTotal("run"), 0.0) // recorded: nothing reserved or spent, whatever the cap
        val got = r.doc!!.getValue("lines").jsonArray.map { it.jsonObject }
        val want = expected.getValue("lines").jsonArray.map { it.jsonObject }
        assertEquals(want.map { it.getValue("id") }, got.map { it.getValue("id") })
        for ((g, w) in got.zip(want)) {
            val id = w.getValue("id").jsonPrimitive.content
            assertEquals(w.keys, g.keys, id) // the same fields, as the Python run writes them
            // what does not depend on the tempo change must agree to the millisecond
            for (k in listOf("raw_duration_s", "dropped", "hit_offset_s", "hit_source", "target_s", "provider", "voice", "cache_hit"))
                if (k in w) assertEquals(emptyList(), ConformanceRunner.compare(g.getValue(k), w.getValue(k), 0.001), "$id $k")
            val sped = w.getValue("speed_factor").jsonPrimitive.content.toDouble() > 1.0
            val tolerance = if (sped) 0.03 else 0.001 // FFmpeg's atempo and ours round the length of a sped-up take differently
            for (k in listOf("speed_factor", "duration_s", "timeline_start", "timeline_end"))
                assertEquals(emptyList(), ConformanceRunner.compare(g.getValue(k), w.getValue(k), if (k == "speed_factor") 0.001 else tolerance), "$id $k")
        }
        assertEquals(1.7, got[2].getValue("duration_s").jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun theFittedTakesAreTheLengthsTheDocSays(): Unit {
        val r = runBlocking { VoiceStage(adapter()).run(script, storyboard) }
        for (l in r.doc!!.getValue("lines").jsonArray.map { it.jsonObject }) {
            val pcm: Pcm = r.takes.getValue(l.getValue("id").jsonPrimitive.content)
            assertEquals(l.getValue("duration_s").jsonPrimitive.content.toDouble(), pcm.durationS, 0.002)
            assertTrue(AudioAnalysis.blockRms(pcm.samples, pcm.sampleRate).max() > 0.01f, "a take with sound")
        }
    }
}
