package app.epicreplay.core.mix

import app.epicreplay.core.audio.Wav
import app.epicreplay.core.compose.ComposeMeasure
import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpResponse
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import app.epicreplay.core.style.StyleLoader
import app.epicreplay.core.voice.SpeechAdapter
import app.epicreplay.core.voice.VoiceStage
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

/** S8 on the sample run: the PC's recorded takes placed by S7, the pack's real sounds, mixed; the PC's own run of the same data measured -14.0 LUFS and -2.0 dBTP. */
class MixStageTest {
    private val shared = File(System.getProperty("shared.dir"))
    private fun json(f: String) = Json.parseToJsonElement(File(shared, f).readText()).jsonObject
    private val storyboard = json("fixtures/sample_run/storyboard.json")
    private val rules = MixRules.parse(File(shared, "mix/rules.json").readText())

    private fun voiceRun() = runBlocking {
        val speech = SpeechAdapter(object : HttpTransport { override suspend fun send(request: HttpRequest, profileId: String): HttpResponse = error("a replay sends nothing") }, { null }, "eleven_v3", { error("replay") }, null, Recorder(ProviderMode.REPLAY, File(shared.parentFile, "pc/tests/fixtures/recordings")))
        VoiceStage(speech).run(json("fixtures/sample_run/script.json"), storyboard)
    }

    @Test
    fun theSampleRunMixesToTheTargetAndItsKeyLinesLandOnTheirTargets(): Unit {
        val v = voiceRun()
        val pack = StyleLoader.loadShared(shared).resolved("football_commentator", "en")
        val uc = json("fixtures/sample_run/user_context.json"); val meta = json("fixtures/sample_run/clip_meta.json")
        val audio = MixAudio { file ->
            when {
                file.startsWith("run/voice/") -> listOf(v.takes.getValue(file.removePrefix("run/voice/").removeSuffix(".wav")).samples)
                file.startsWith("assets/") -> Wav.readChannels(File(shared, "assets/" + file.removePrefix("assets/")).readBytes()).first
                file == "run/work.mp4" -> { val n = 48000 * 15; List(2) { c -> FloatArray(n) { i -> (((i * 2654435761L + c * 97) shr 9) and 0xFFFF).toInt().let { (it - 32768) / 32768f * 0.1f } } } }
                else -> error("unexpected $file")
            }
        }
        val r = MixStage.run(storyboard, v.doc!!, pack, uc, meta, rules, audio)
        assertEquals("ok", r.status, r.note)
        val doc = r.doc!!
        assertEquals(28.9, doc.getValue("duration_s").jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(-14.0, doc.getValue("loudness_lufs").jsonPrimitive.content.toDouble(), 0.5)
        assertEquals(14, doc.getValue("layers").jsonArray.size) // the same layers as the PC's mix.json of this run
        assertEquals(listOf("l1.wav", "crowd_bed_01.wav", "work.mp4"), listOf(0, 7, 13).map { doc.getValue("layers").jsonArray[it].jsonObject.getValue("file").jsonPrimitive.content })
        // the key lines are where S7 placed them: found in the mix by correlation, within the 150 ms of SC-4 (the PC's run: 5 ms)
        val mono = FloatArray(r.left!!.size) { (r.left[it] + r.right!![it]) / 2f }
        val hits = ComposeMeasure.onsets(v.doc, v.takes.mapValues { it.value.samples }, mono, 48000)
        assertEquals(listOf("l3", "l6"), hits.map { it.getValue("line_id").jsonPrimitive.content })
        for (h in hits) assertTrue(Math.abs(h.getValue("error_ms").jsonPrimitive.content.toInt()) <= 50, h.toString())
        println("MIX sample run: ${doc.getValue("loudness_lufs")} LUFS ${doc.getValue("true_peak_dbtp")} dBTP, onsets ${hits.map { it.getValue("error_ms") }}")
    }
}
