package app.epicreplay.core.voice

import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.net.JavaHttpTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * T422's one paid call (off unless `-Dmeasure.voice=true`, and only with the owner's yes): one short take with character timings from ElevenLabs, to see that the
 * request the Kotlin client builds is accepted, that the answer's timings fit the text as sent, and what it cost. The key is read from `pc/.env` and never printed;
 * the take is recorded in `build/measure-voice-recordings`, so a second run costs nothing.
 */
class VoiceLiveTest {
    private val shared = File(System.getProperty("shared.dir"))

    private fun key(): String? {
        val env = File(shared.parentFile, "pc/.env")
        if (!env.isFile) return null
        return env.readLines().filter { "=" in it && !it.trimStart().startsWith("#") }.associate { it.substringBefore("=").trim() to it.substringAfter("=").substringBefore("#").trim() }["ELEVENLABS_API_KEY"]?.ifEmpty { null }
    }

    @Test
    fun oneShortTakeWithCharacterTimingsFromElevenLabs(): Unit {
        assumeTrue(System.getProperty("measure.voice") == "true", "a paid call: run with -Dmeasure.voice=true")
        val apiKey = key() ?: error("no ELEVENLABS_API_KEY in pc/.env")
        val dir = File("build/measure-voice-recordings").apply { mkdirs() }
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.02, dayUsd = 20.0, preCheckpointUsd = 5.0), today = { "measure" }, now = { "t" })
        // the answer is MP3 and the JVM has no decoder: the take's samples are not what is checked here
        val speech = SpeechAdapter(JavaHttpTransport(), { app.epicreplay.core.secrets.Secret(apiKey) }, "eleven_v3", { Pcm(FloatArray(4800), 48000) }, ledger, Recorder(ProviderMode.RECORD, dir), "measure")
        val text = "Top corner!"; val delivery = "shouting, full voice"
        val take = runBlocking { speech.synthesise(text, "fGIZlgPQ75MMlvQ6WxgY", delivery, "en", true) }
        val al = assertNotNull(take.alignment, "the answer has character timings")
        val sent = Voice.tagged(text, delivery)
        assertEquals(sent, al.getValue("chars").toString().trim('"'), "the timings are over the text as sent, tags included")
        assertEquals(sent.length, (al.getValue("starts") as kotlinx.serialization.json.JsonArray).size)
        assertEquals(ElevenLabs.estimateUsd(text, delivery), take.usd, 1e-9)
        println("VOICE take of ${sent.length} characters cost ${take.usd} USD (cache hit ${take.cacheHit}); the ledger has ${ledger.runTotal("measure")} USD")
        assertTrue(ledger.runTotal("measure") <= 0.02)
    }
}
