package app.epicreplay

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.audio.Wav
import app.epicreplay.core.compose.EncoderChoice
import app.epicreplay.core.compose.OverlayPlan
import app.epicreplay.core.compose.OverlaySpec
import app.epicreplay.core.mix.MixRules
import app.epicreplay.core.mix.MixStage
import app.epicreplay.core.style.PackFile
import app.epicreplay.core.style.StyleLoader
import app.epicreplay.media.AndroidMixAudio
import app.epicreplay.media.ClipFrames
import app.epicreplay.media.ClipProbe
import app.epicreplay.media.ComposeStage
import app.epicreplay.media.Composer
import app.epicreplay.media.OverlayArt
import app.epicreplay.media.OverlayFonts
import app.epicreplay.media.SegmentExporter
import app.epicreplay.media.SignalDecoder
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T423 on the phone, on the sample run's data: S8 mixes the PC's fitted takes with the pack's real sounds, S10 composes the segments, the overlays and the mix into
 * `final.mp4` with Media3, and the file's frames, sound and key-line timing are measured. The segments are stills of the synthetic ramp clip at the storyboard's size
 * and frame counts (S9 has its own device tests). The hardware encoder's refusal is forced with an encoder selector that has no encoder.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ComposeOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val testAssets = inst.context.assets
    private val appAssets = ctx.assets
    private val metrics = File(ctx.filesDir, "t423_metrics.txt")

    private fun text(a: android.content.res.AssetManager, path: String) = a.open(path).use { String(it.readBytes()) }
    private fun json(a: android.content.res.AssetManager, path: String) = Json.parseToJsonElement(text(a, path)).jsonObject
    private fun note(s: String) { metrics.appendText(s + "\n"); println(s) }

    private fun pack(): JsonObject {
        val files = appAssets.list("styles")!!.filter { it.endsWith(".json") && it != "voices.json" }.map { PackFile(it, text(appAssets, "styles/$it")) }
        return StyleLoader(text(appAssets, "contracts/style_pack.schema.json"), json(appAssets, "styles/voices.json"), { true }).load(files).resolved("football_commentator", "en")
    }

    private fun luma(p: Int) = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
    private fun yellow(p: Int) = Color.red(p) > 200 && Color.green(p) in 150..240 && Color.blue(p) < 130 // the football pack's accent, #FFD23F

    /** The run folder of the sample: its documents, the fitted takes, and one still segment of each storyboard entry at the plan's size and frames. */
    private fun runFolder(): File {
        val run = File(ctx.cacheDir, "run_t423").apply { deleteRecursively(); mkdirs() }
        for (f in listOf("storyboard.json", "script.json", "user_context.json")) File(run, f).writeText(text(testAssets, "fixtures/sample_run/$f"))
        File(run, "clip_meta.json").writeText(text(testAssets, "fixtures/sample_run/clip_meta.json").replace("\"has_audio\": true", "\"has_audio\": false")) // the synthetic clip has no sound
        File(run, "voice.json").writeText(text(testAssets, "fixtures/voice/sample_run_voice.json"))
        File(run, "voice").mkdirs()
        for (n in testAssets.list("fixtures/voice/sample_run_takes")!!) File(run, "voice/$n").writeBytes(testAssets.open("fixtures/voice/sample_run_takes/$n").use { it.readBytes() })
        val clip = File(run, "ramp.mp4").also { f -> testAssets.open("fixtures/visuals/ramp_30fps_4s.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
        val sb = json(testAssets, "fixtures/sample_run/storyboard.json")
        File(run, "segments").mkdirs()
        val exporter = SegmentExporter(ctx, clip)
        sb.getValue("segments").jsonArray.forEachIndexed { i, s ->
            val o = s.jsonObject
            val frames = Math.round((o.getValue("timeline_end").jsonPrimitive.double - o.getValue("timeline_start").jsonPrimitive.double) * 30).toInt()
            val e = buildJsonObject { put("id", o.getValue("id")); put("method", "still"); put("frames", frames); put("hold_s", 0.0); put("freeze_frame", 10 + 20 * i) }
            runBlocking { exporter.export(e, 1920, 1080, File(run, "segments/${o.getValue("id").jsonPrimitive.content}.mp4")) }
        }
        return run
    }

    @Test
    fun theSampleRunComposesToAVideoOfTheStoryboardsLengthWithTheLabelOnIt() {
        metrics.delete()
        val run = runFolder()
        val sb = json(testAssets, "fixtures/sample_run/storyboard.json"); val script = json(testAssets, "fixtures/sample_run/script.json"); val voice = json(testAssets, "fixtures/voice/sample_run_voice.json")
        val pack = pack()
        // S8: the pack's real sounds from the app, the fitted takes from the run
        val t0 = System.nanoTime()
        val mix = MixStage.run(sb, voice, pack, json(testAssets, "fixtures/sample_run/user_context.json"), Json.parseToJsonElement(File(run, "clip_meta.json").readText()).jsonObject, MixRules.parse(text(appAssets, "mix/rules.json")), AndroidMixAudio(appAssets, run, 48000))
        val mixMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("S8 ${mix.status}: ${mix.note}", mix.status == "ok")
        File(run, "mix.wav").writeBytes(Wav.writeChannels(listOf(mix.left!!, mix.right!!), 48000))
        note("S8 mixed the sample run in $mixMs ms: ${mix.doc!!.getValue("loudness_lufs")} LUFS ${mix.doc!!.getValue("true_peak_dbtp")} dBTP")
        // S10
        val stage = ComposeStage(Composer(ctx), OverlayFonts.load(appAssets))
        val t1 = System.nanoTime()
        val log = ArrayList<String>()
        val r = runBlocking { stage.run(run, sb, script, voice, pack) { log += it } }
        val composeMs = (System.nanoTime() - t1) / 1_000_000
        note("S10 composed in $composeMs ms: ${r.status} ${r.note}\n${r.doc}\nevents $log")
        assertEquals("S10 ${r.note}", "ok", r.status)
        val doc = r.doc!!
        assertEquals(28.9, doc.getValue("duration_s").jsonPrimitive.double, 0.001)
        assertEquals(1920, doc.getValue("width").jsonPrimitive.content.toInt()); assertEquals(1080, doc.getValue("height").jsonPrimitive.content.toInt())
        assertEquals("h264", doc.getValue("video_codec").jsonPrimitive.content); assertEquals("aac", doc.getValue("audio_codec").jsonPrimitive.content)
        assertEquals(-14.0, doc.getValue("loudness_lufs").jsonPrimitive.double, 1.5)
        val hits = doc.getValue("climax_onsets").jsonArray
        assertEquals(2, hits.size)
        for (h in hits) assertTrue("key line $h", Math.abs(h.jsonObject.getValue("error_ms").jsonPrimitive.content.toInt()) <= 150)
        assertEquals(867, ClipProbe.readable(File(run, "final.mp4").path)!!.videoSamplesUs.size)
        // the label: on every export, in the corner, drawn in the pack's accent; the title only in its second; the replay bug only in the replay
        ClipFrames(File(run, "final.mp4").path).use { c ->
            fun corner(frame: Int): Int { val b = c.frameAt(frame)!!; return (0 until 300).sumOf { dx -> (0 until 120).count { dy -> yellow(b.getPixel(b.width - 1 - 40 - dx, b.height - 1 - 40 - dy)) } } }
            for (t in listOf(0.5, 5.0, 20.0, 27.5)) assertTrue("the label's accent bar at $t s: ${corner((t * 30).toInt())} pixels", corner((t * 30).toInt()) > 150)
            fun centre(frame: Int): Int { val b = c.frameAt(frame)!!; return (0 until 400 step 4).sumOf { dx -> (0 until 150 step 4).count { dy -> luma(b.getPixel(b.width / 2 - 200 + dx, b.height / 2 - 75 + dy)) > 215 } } }
            assertTrue("the title's letters at 0.5 s: ${centre(15)}", centre(15) > 60)
            assertEquals("no title at 5 s", 0, centre(150))
            fun bug(frame: Int): Int { val b = c.frameAt(frame)!!; return (0 until 200).sumOf { dx -> (0 until 60).count { dy -> yellow(b.getPixel((b.width * 0.04).toInt() + 5 + dx, (b.height * 0.05).toInt() + 5 + dy)) } } }
            assertTrue("the replay bug at 21 s: ${bug(630)}", bug(630) > 1500)
            assertEquals("no replay bug at 5 s", 0, bug(150))
            val sample = File(ctx.filesDir, "t423_frame_21s.png"); sample.outputStream().use { c.frameAt(630)!!.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    @Test
    fun aHardwareEncoderThatRefusesItsOutputIsRetriedAndTheExportFinishesOnTheSoftwareEncoder() {
        val dir = File(ctx.cacheDir, "fallback").apply { deleteRecursively(); mkdirs() }
        val clip = File(dir, "ramp.mp4").also { f -> testAssets.open("fixtures/visuals/ramp_30fps_4s.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
        val seg = File(dir, "s.mp4")
        runBlocking { SegmentExporter(ctx, clip).export(buildJsonObject { put("id", "s1"); put("method", "still"); put("frames", 60); put("hold_s", 0.0); put("freeze_frame", 10) }, 320, 180, seg) }
        val mix = File(dir, "mix.wav").apply { writeBytes(Wav.writeChannels(List(2) { FloatArray(96000) { i -> (0.2 * Math.sin(i * 0.05)).toFloat() } }, 48000)) }
        val spec = OverlaySpec("ai_label", mapOf("label" to "AI COMMENTARY"), 0.0, 2.0, 0.2)
        val art = OverlayArt.render(spec, buildJsonObject { put("accent", "#FFD23F"); put("skin", "stadium") }, 320, 180, false, OverlayFonts.load(appAssets))
        val log = ArrayList<String>()
        val out = File(dir, "final.mp4")
        // an encoder selector with no encoder: what the phone's encoder does when it gives nothing the muxer takes
        val r = runBlocking { Composer(ctx) { emptyList() }.compose(listOf(seg), mix, listOf(spec to art), out) { log += it } }
        note("fallback: ${r.encoder}, events $log")
        assertEquals(EncoderChoice.SOFTWARE, r.encoder)
        assertEquals(3, log.size)
        assertTrue(log.toString(), "first try" in log[0] && "trying again" in log[0] && "the retry" in log[1] && "software encoder" in log[1] && "software encoder" in log[2])
        assertEquals(60, ClipProbe.readable(out.path)!!.videoSamplesUs.size)
        assertTrue(SignalDecoder.decodeChannels(out.path, 48000)!!.let { it.size == 2 && Math.abs(it[0].size / 48000.0 - 2.0) < 0.1 })
    }

    @Test
    fun aTakeFromTheProviderIsDecodedWithItsChannelsKeptAndAtTheRateAsked() {
        val f = File(ctx.cacheDir, "take.mp3").also { it.writeBytes(testAssets.open("fixtures/voice/take_44100_128.mp3").use { i -> i.readBytes() }) }
        val ch = SignalDecoder.decodeChannels(f.path, 48000)!!
        assertEquals(1, ch.size)
        assertTrue("about a second at 48 kHz: ${ch[0].size}", ch[0].size in 28800..62400)
    }
}
