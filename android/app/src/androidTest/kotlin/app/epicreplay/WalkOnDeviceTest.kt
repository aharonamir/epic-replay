package app.epicreplay

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.card.PlayCard
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.FakeBackend
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelResult
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ToolDef
import app.epicreplay.core.models.Usage
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.setup.ModelsUsed
import app.epicreplay.core.voice.Speech
import app.epicreplay.core.voice.Take
import app.epicreplay.media.ClipProbe
import app.epicreplay.ui.AppRoot
import app.epicreplay.ui.AppViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T425, spec US4 on the phone: a clip is chosen, the narrator and the consent are set, "Make my replay" runs S0 and S1 on the phone, S2 and S3 over a scripted vision model (the
 * answers the PC recorded for one real clip, replayed for a synthetic one), the play card is answered with taps, and S4 to S10 run (the Commentator over `FakeBackend`, the voice a fake
 * take) to a video that plays. No network, no key, nothing in the app's ledger or run list.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class WalkOnDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val tests = inst.context.assets
    private val graph = AppGraph(ctx)
    private val work = File(ctx.cacheDir, "walk").apply { deleteRecursively(); mkdirs() }

    /** The vision model: the recorded answers of the PC's real run 2 (`conformance/spotter/play_sheet_real_2`), the sheet answer for every sheet and the events answer for the events call. */
    private class ScriptedVision(override val profile: Profile, val sheet: JsonObject, val events: JsonObject) : ModelBackend {
        val calls = AtomicInteger()
        override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult {
            calls.incrementAndGet()
            val data = if (images.first().label == "Grid 1") events else sheet
            return ModelResult(data.toString(), data, emptyList(), Usage(10, 10), 0.0, 0.1)
        }
        override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult = error("the Spotter does not chat")
    }

    /** A voice that costs nothing: a quiet tone as long as the text takes to say. */
    private val voice = object : Speech {
        override suspend fun synthesise(text: String, voiceId: String, delivery: String, language: String, timings: Boolean): Take {
            val n = (48000 * (0.2 + 0.065 * text.length)).toInt()
            return Take(Pcm(FloatArray(n) { (0.2 * Math.sin(it * 0.06) * Math.min(1.0, Math.min(it, n - it) / 2400.0)).toFloat() }, 48000), null, 0.0, true)
        }
    }

    @Test
    fun theWalkFromAClipToAVideoThatPlays() {
        val real = Json.parseToJsonElement(tests.open("conformance/spotter/play_sheet_real_2/case.json").bufferedReader().use { it.readText() }).jsonObject.getValue("inputs").jsonObject
        val looks = real.getValue("looks").jsonObject.getValue("value").jsonArray.map { it.jsonObject }
        val vision = ScriptedVision(graph.profiles.profiles.getValue("deepseek-flash"), looks.first().getValue("answer").jsonObject, real.getValue("events_look").jsonObject.getValue("value").jsonObject.getValue("answer").jsonObject)
        val textModel = FakeBackend(graph.profiles.profiles.getValue("deepseek-flash")).also { f -> repeat(12) { f.reply("I cannot write this one.") } }
        val models = graph.profiles.profiles.getValue("deepseek-flash").let { ModelsUsed(it, it) }
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 5.0, dayUsd = 20.0, preCheckpointUsd = 5.0), today = { "walk" }, now = { "t" })
        val launcher = RunLauncher(graph, ctx, ProviderMode.LIVE, File(work, "recordings"), models, { _, role -> if (role == Role.VISION) vision else textModel }, { voice }, ledger)
        val vm = AppViewModel(graph, ctx, launcher, File(work, "runs"))
        val clip = File(work, "strike.mp4").also { f -> tests.open("fixtures/clip/work_15s_1080p.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
        vm.useClip(clip, "strike.mp4")

        rule.setContent { AppRoot(vm, { true }, { }) }
        rule.onNodeWithTag("consent").performScrollTo().performClick()
        rule.onNodeWithTag("narrator_news_reporter").performScrollTo().performClick()
        rule.onNodeWithTag("make").performScrollTo().performClick()

        // S0 to S3 on the phone: the progress screen, then the play card
        rule.waitUntil(180_000) { rule.onAllNodesWithTag("play_card").fetchSemanticsNodes().isNotEmpty() || vm.session.view.value?.state?.status in setOf("failed", "stopped") }
        val atCard = vm.session.view.value!!
        assertEquals("the run stopped before the card: ${atCard.state.message}", "waiting_play_card", atCard.state.status)
        assertEquals(4, atCard.state.stepN)
        rule.onNodeWithTag("status_line").assertTextEqualsNoCase("Your turn.")
        // the key moment first, then every item marked "Check this", then yes
        rule.onNodeWithTag("this_one").performScrollTo().performClick()
        for (id in PlayCard.toCheck(atCard.playSheet!!)) rule.onNodeWithTag("keep_$id").performScrollTo().performClick()
        rule.onNodeWithTag("yes").performScrollTo().performClick()

        // S4 to S10 to the result
        rule.waitUntil(300_000) { rule.onAllNodesWithTag("result_headline").fetchSemanticsNodes().isNotEmpty() || vm.session.view.value?.state?.status in setOf("failed", "stopped") }
        val done = vm.session.view.value!!
        assertEquals("the run did not finish: ${done.state.message} ${done.state.steps.map { it.status }}", "done", done.state.status)
        rule.onNodeWithTag("player").assertExists()
        assertTrue(done.state.steps.all { it.status == "done" })
        val render = RunFiles.read(File(done.dir, "render.json"))!!
        val frames = ClipProbe.readable(File(done.dir, "final.mp4").path)!!.videoSamplesUs.size
        assertEquals(Math.round(render.getValue("duration_s").jsonPrimitive.content.toDouble() * 30).toInt(), frames)
        assertEquals("news_reporter", RunFiles.read(File(done.dir, "manifest.json"))!!.getValue("style").jsonPrimitive.content)
        assertTrue("the vision model was asked for the sheets and the events: ${vision.calls.get()}", vision.calls.get() >= 3)
        assertTrue("the text model was asked: ${textModel.calls.size}", textModel.calls.isNotEmpty() || RunFiles.read(File(done.dir, "script.json")) != null)
        File(ctx.filesDir, "t425_metrics.txt").appendText("walk: ${done.state.steps.map { "${it.n}:${it.durationS}s" }}, cost ${done.state.costUsd}, frames $frames, vision calls ${vision.calls.get()}, script status ${RunFiles.read(File(done.dir, "commentator_trace.json"))?.get("status")}\n")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEqualsNoCase(text: String) = assertTextEquals(text.uppercase())
