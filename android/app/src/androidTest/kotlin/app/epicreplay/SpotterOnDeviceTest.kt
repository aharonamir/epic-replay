package app.epicreplay

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceConfig
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.spotter.Spotter
import app.epicreplay.core.spotter.SpotterRun
import app.epicreplay.core.spotter.SpotterWords
import app.epicreplay.core.text.EnglishLexicon
import app.epicreplay.media.ClipSheetRenderer
import app.epicreplay.media.SignalDecoder
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T418 on the phone: a contact sheet is drawn from a real clip with the phone's own decoder, and S1, S2 and S3 run on it with the on-device model
 * (the Gemma files pushed into `files/models/`): a play sheet, or a clear stop. Timings go to `files/t418_metrics.txt` (never the answers).
 */
@RunWith(AndroidJUnit4::class)
class SpotterOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val assets = inst.context.assets
    private val graph = AppGraph(ctx)
    private val clip = File(ctx.cacheDir, "strike.mp4")

    private fun prepareClip() {
        assets.open("fixtures/signals/strike_with_sound.mp4").use { i -> clip.outputStream().use { i.copyTo(it) } }
    }

    private fun shared(): File {
        val root = File(ctx.cacheDir, "shared").apply { deleteRecursively() }
        for (f in listOf("copy/spotter.json", "lexicons/en.json", "contracts/play_sheet.schema.json")) {
            File(root, f).apply { parentFile!!.mkdirs() }.also { dst -> ctx.assets.open(f).use { i -> dst.outputStream().use { i.copyTo(it) } } }
        }
        return root
    }

    @Test
    fun aSheetIsDrawnFromARealClipWithTheDecoderOfThePhone() {
        prepareClip()
        ClipSheetRenderer(clip.path, 30.0).use { r ->
            val s = r.render(listOf(0, 20, 40, 60, 80, 100, 120, 140, 149))
            assertEquals(9, s.cells.size)
            assertEquals(listOf(0, 20, 40, 60, 80, 100, 120, 140, 149), s.cells.map { it.frame })
            val bmp = BitmapFactory.decodeByteArray(s.jpeg, 0, s.jpeg.size)
            assertEquals(3 * 448, bmp.width) // three cells of 448 across
            assertEquals(3 * Math.round(90 * 448 / 160.0).toInt(), bmp.height) // three rows of the clip's ratio
            // a frame that cannot be read is left out and the cells are numbered again
            val short = r.render(listOf(0, 20, 5000))
            assertEquals(listOf(1, 2), short.cells.map { it.cell })
        }
    }

    @Test
    fun theSpotterRunsOnThePhoneOnTheOnDeviceModelAndGivesAPlaySheetOrAClearStop() {
        val p = OnDeviceProfile(
            id = "gemma-4-e2b-device", label = "Gemma 4 E2B", roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "", engine = "llama.cpp",
            files = listOf(ModelFile("gemma-4-E2B-it-Q4_0.gguf", "https://example.org/w", "0".repeat(64), 1, "weights"), ModelFile("mmproj-gemma-4-E2B-it-Q8_0.gguf", "https://example.org/p", "0".repeat(64), 1, "projector")),
            contextTokens = 4096, minFreeRamMb = 1, minFreeStorageMb = 1, license = "test",
        )
        assumeTrue("the Gemma files are not on the phone", p.files.all { File(graph.modelDir, it.name).isFile })
        prepareClip()
        val signals = SignalDecoder.scan(clip.path, top = 5)
        val candidates = JsonArray(signals.candidates.map { c ->
            buildJsonObject {
                put("frame", c.frame); put("t", c.t); put("score", c.score); put("motion_z", c.motionZ); put("sound_z", c.soundZ)
                put("sound_onset_t", c.soundOnsetT?.let { JsonPrimitive(it) } ?: JsonNull)
                put("sound_onset_frame", c.soundOnsetT?.let { JsonPrimitive(Math.round(it * signals.fps).toInt()) } ?: JsonNull)
            }
        })
        val info = buildJsonObject {
            put("duration_s", signals.frames / signals.fps); put("fps", signals.fps); put("frames", signals.frames); put("orientation", "landscape")
            put("candidates", candidates)
            put("user", buildJsonObject { put("activity", JsonNull); put("player_name", JsonNull); put("description", JsonNull) })
        }
        val sig = buildJsonObject { put("onsets_t", JsonArray(signals.onsetsT.map { JsonPrimitive(it) })) }
        val meta = buildJsonObject { put("duration_s", signals.frames / signals.fps); put("fps", signals.fps) }
        val root = shared()
        val spotter = Spotter(EnglishLexicon(File(root, "lexicons/en.json").readText()), File(root, "contracts/play_sheet.schema.json").readText())
        val backend = graph.onDeviceBackend(p, Role.VISION, OnDeviceConfig())
        val t0 = System.nanoTime()
        val out = ClipSheetRenderer(clip.path, signals.fps).use { r ->
            try { runBlocking { SpotterRun(spotter, app.epicreplay.core.spotter.VisionAdapter(backend, p.id, SpotterWords.load(root).retryLine), SpotterWords.load(root), r).run("device-run", info, sig, meta, 0.25, p.id) } } finally { backend.close() }
        }
        File(ctx.filesDir, "t418_metrics.txt").appendText("spotter on device: ${(System.nanoTime() - t0) / 1_000_000_000} s, status ${out.status}, note ${out.note.take(200)}\n")
        assertTrue("an unexpected status ${out.status}", out.status in setOf("ok", "degraded", "stopped", "failed"))
        assertTrue("a stop must say why", out.status == "ok" || out.note.isNotBlank())
        if (out.status != "failed") assertNotNull(out.playSheet)
    }
}
