package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.commentator.Commentator
import app.epicreplay.core.commentator.CommentatorMode
import app.epicreplay.core.commentator.CommentatorRun
import app.epicreplay.core.commentator.CommentatorWords
import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceConfig
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.storyboard.Storyboard
import app.epicreplay.core.style.PackFile
import app.epicreplay.core.style.Slots
import app.epicreplay.core.style.StyleLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T419 on the phone: the Commentator on the on-device Gemma 4 E2B (a profile that takes no tool calls), the JSON draft whose failures are given back
 * against the one structured draft, on the PC's sample and on a second clip built from the Spotter's real answers. Counts and timings go to
 * `files/t419_metrics.txt` (not the lines).
 */
@RunWith(AndroidJUnit4::class)
class CommentatorOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val testAssets = inst.context.assets
    private val appAssets = ctx.assets
    private val graph = AppGraph(ctx)

    private fun text(a: android.content.res.AssetManager, p: String) = a.open(p).bufferedReader().use { it.readText() }
    private fun json(a: android.content.res.AssetManager, p: String) = Json.parseToJsonElement(text(a, p)).jsonObject

    @Test
    fun theFeedbackLoopAgainstTheStructuredDraftOnTheOnDeviceModel() {
        val p = OnDeviceProfile(
            id = "gemma-4-e2b-device", label = "Gemma 4 E2B", roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "", engine = "llama.cpp",
            files = listOf(ModelFile("gemma-4-E2B-it-Q4_0.gguf", "https://example.org/w", "0".repeat(64), 1, "weights"), ModelFile("mmproj-gemma-4-E2B-it-Q8_0.gguf", "https://example.org/p", "0".repeat(64), 1, "projector")),
            contextTokens = 6144, minFreeRamMb = 1, minFreeStorageMb = 1, license = "test",
        )
        assumeTrue("the Gemma files are not on the phone", p.files.all { File(graph.modelDir, it.name).isFile })
        // the shared data the app packages
        val schema = text(appAssets, "contracts/style_pack.schema.json")
        val voices = json(appAssets, "styles/voices.json")
        val files = appAssets.list("styles")!!.filter { it.endsWith(".json") && it != "voices.json" }.map { PackFile(it, text(appAssets, "styles/$it")) }
        val packs = StyleLoader(schema, voices, { true }).load(files)
        val pack = packs.resolved("football_commentator", "en")
        val root = File(ctx.cacheDir, "shared").apply { deleteRecursively() }
        for (f in listOf("copy/commentator.json", "lexicons/en.json", "lexicons/zh.json", "contracts/script.schema.json")) File(root, f).apply { parentFile!!.mkdirs() }.writeText(text(appAssets, f))
        val words = CommentatorWords.load(root)
        val commentator = Commentator(words, Checks.fromShared(root), File(root, "contracts/script.schema.json").readText())
        val uc = json(testAssets, "fixtures/sample_run/user_context.json"); val meta = json(testAssets, "fixtures/sample_run/clip_meta.json")
        val inputs = linkedMapOf(
            "sample_run" to json(testAssets, "fixtures/sample_run/play_sheet.json"),
            "clip_2" to json(testAssets, "conformance/spotter/play_sheet_real_2/expected/output.json").getValue("play_sheet").jsonObject,
        )
        val metrics = File(ctx.filesDir, "t419_metrics.txt").apply { delete() }
        val backend = graph.onDeviceBackend(p, Role.TEXT, OnDeviceConfig())
        try {
            for ((name, ps) in inputs) {
                val sb: JsonObject = Storyboard.build(ps, meta, 20.0)
                val slots = Slots.build(sb, pack, ps.getValue("climax").jsonObject["event_id"]?.jsonPrimitive?.content).map { it.jsonObject }
                for (mode in listOf(CommentatorMode.FEEDBACK, CommentatorMode.STRUCTURED)) {
                    val t0 = System.nanoTime()
                    val r = runBlocking { CommentatorRun(commentator, backend, words, p.id, null, null, "run", mode).write(slots, ps, uc, sb, pack) }
                    val code = r.outcome.note.substringAfter("generic lines for ", "").let { if (it.isEmpty() || it == "none") 0 else it.split(", ").size }
                    metrics.appendText("$name ${mode.name}: ${r.outcome.status}, turns ${r.turns}, lines written by code $code of ${r.outcome.script.getValue("lines").jsonArray.size}, ${(System.nanoTime() - t0) / 1_000_000_000} s, note ${r.outcome.note.take(120)}\n")
                    assertTrue("a script always comes out", r.outcome.script.getValue("lines").jsonArray.size == slots.size)
                }
            }
        } finally { backend.close() }
    }
}
