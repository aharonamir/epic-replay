package app.epicreplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.PastItem
import app.epicreplay.core.ui.PastRuns
import app.epicreplay.core.ui.ResultModel
import app.epicreplay.ui.EpicTheme
import app.epicreplay.ui.PastScreen
import app.epicreplay.ui.ResultActions
import app.epicreplay.ui.ResultScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** T426: the result screen and the past replays on the phone, over run folders made of the sample run's own files: what each panel shows, what each tap does, and that a delete leaves nothing. */
@RunWith(AndroidJUnit4::class)
class ResultScreenTest {
    @get:Rule val rule = createComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val tests = inst.context.assets
    private val words = PageWords.parse(inst.targetContext.assets.open("copy/page.json").bufferedReader().use { it.readText() })
    private val root = File(inst.targetContext.cacheDir, "result-test-runs").apply { deleteRecursively(); mkdirs() }

    private fun fromAsset(path: String, to: File) { to.parentFile!!.mkdirs(); tests.open(path).use { i -> to.outputStream().use { i.copyTo(it) } } }

    /** A finished run folder: the sample run's script and a voice list, a manifest saying the models, a real (short) video. */
    private fun run(id: String, parent: String? = null, generic: Boolean = false): File {
        val d = File(root, id).apply { mkdirs() }
        fromAsset("fixtures/clip/work_15s_1080p.mp4", File(d, "final.mp4")); fromAsset("fixtures/clip/work_15s_1080p.mp4", File(d, "upload.mp4"))
        var script = tests.open("fixtures/sample_run/script.json").bufferedReader().use { it.readText() }
        if (generic) script = script.replaceFirst("\"generic\": false", "\"generic\": true")
        File(d, "script.json").writeText(script)
        File(d, "voice.json").writeText(tests.open("fixtures/voice/sample_run_voice.json").bufferedReader().use { it.readText() })
        File(d, "user_context.json").writeText(if (parent == null) "{}" else """{"parent_run_id":"$parent"}""")
        File(d, "render.json").writeText("""{"duration_s":15.0}""")
        File(d, "manifest.json").writeText(
            """{"run_id":"$id","created_at":"2026-11-24T10:00:00Z","status":"done","style":"football_commentator","language":"en","total_usd":0.0412,
              "stack":{"models":{"vision":{"profile":"gemma","label":"Gemma on the phone","location":"local","model":"gemma.task"},"text":{"profile":"deepseek-flash","label":"DeepSeek Flash","location":"cloud","model":"deepseek-v4-flash"}}},
              "stages":[{"stage":"S0","status":"ok","duration_s":2.0,"cost_usd":0.0},{"stage":"S5","status":"degraded","duration_s":9.0,"cost_usd":0.0412,"note":"generic lines for l2"}]}""",
        )
        return d
    }

    @Test
    fun theResultShowsTheVideoTheFallbackTheScriptTheModelsAndTheCost() {
        val d = run("r_one", generic = true)
        var shared = 0; var made = 0; var deleted = 0
        rule.setContent { EpicTheme { ResultScreen(File(d, "final.mp4"), ResultModel.of(d, words, "Your replay"), words, ResultActions({ shared++ }, { made++ }, { deleted++ })) } }
        rule.onNodeWithTag("result_headline").assertTextContains("YOUR REPLAY")
        rule.onNodeWithTag("player").assertIsDisplayed()
        rule.onAllNodesWithTag("fallback_note").assertCountEquals(2) // the lines written by code, and the step that left calls out
        rule.onAllNodesWithTag("line_0").assertCountEquals(0) // the panels are closed at first
        rule.onNodeWithTag("section_script").performScrollTo().performClick()
        rule.onNodeWithTag("line_2").performScrollTo().assertTextContains("KEY MOMENT", substring = true, ignoreCase = true)
        rule.onNodeWithTag("line_0").assertTextContains("1.3 s", substring = true)
        rule.onNodeWithTag("checks_passed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("section_models").performScrollTo().performClick()
        rule.onAllNodesWithTag("model_row").assertCountEquals(2)
        rule.onNodeWithText("Gemma on the phone, gemma.task, on this phone").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("DeepSeek Flash, deepseek-v4-flash, at a cloud provider").assertIsDisplayed()
        rule.onNodeWithTag("section_cost").performScrollTo().performClick()
        rule.onNodeWithTag("total_cost").performScrollTo().assertTextContains("0.0412 USD", substring = true)
        rule.onNodeWithTag("share").performScrollTo().performClick(); rule.onNodeWithTag("new_clip").performClick()
        assertEquals(1, shared); assertEquals(1, made); assertEquals(0, deleted)
    }

    @Test
    fun aResultWithNoFallbackSaysNothingAboutOne() {
        val d = run("r_clean")
        File(d, "manifest.json").writeText(File(d, "manifest.json").readText().replace("degraded", "ok"))
        File(d, "script.json").writeText(File(d, "script.json").readText().replace("\"generic\": true", "\"generic\": false")) // the sample's sign-off is a coded line
        rule.setContent { EpicTheme { ResultScreen(File(d, "final.mp4"), ResultModel.of(d, words, "Your replay"), words, ResultActions({}, {}, {})) } }
        rule.onAllNodesWithTag("fallback_note").assertCountEquals(0)
    }

    @Test
    fun deletingAsksFirstAndNamesTheVersionsThatGoWithTheClip() {
        val first = run("r_first"); run("r_second", parent = "r_first")
        var deleted = 0
        rule.setContent { EpicTheme { ResultScreen(File(first, "final.mp4"), ResultModel.of(first, words, "Your replay"), words, ResultActions({}, {}, { deleted++ })) } }
        rule.onNodeWithTag("delete").performScrollTo().performClick()
        rule.onNodeWithText("This removes all 2 versions of this clip.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("delete_cancel").performClick()
        assertEquals(0, deleted)
        rule.onNodeWithTag("delete").performScrollTo().performClick(); rule.onNodeWithTag("delete_confirm").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun thePastListShowsEachFinishedVideoAndADeleteLeavesNothingOfTheClip() {
        run("r_a"); run("r_b", parent = "r_a"); run("r_c")
        File(root, "r_c/manifest.json").writeText(File(root, "r_c/manifest.json").readText().replace("2026-11-24T10", "2026-11-25T10"))
        var items by mutableStateOf(PastRuns.list(root, "Your replay"))
        var opened: PastItem? = null
        rule.setContent { EpicTheme { PastScreen(items, words, { opened = it }, { PastRuns.delete(root, it.runId) { false }; items = PastRuns.list(root, "Your replay") }, {}) } }
        rule.onNodeWithTag("past_count").assertTextContains("3 videos")
        rule.onNodeWithTag("open_r_c").performScrollTo().performClick(); assertEquals("r_c", opened?.runId)
        rule.onNodeWithTag("delete_r_b").performScrollTo().performClick()
        rule.onNodeWithText("This removes all 2 versions of this clip.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("past_delete_confirm").performClick()
        rule.waitForIdle()
        assertEquals("both versions of the clip are gone from storage: folders, uploads, working copies", listOf("r_c"), root.list()!!.toList())
        rule.onNodeWithTag("past_count").assertTextContains("1 video")
        rule.onAllNodesWithTag("past_r_a").assertCountEquals(0)
        rule.onNodeWithTag("delete_r_c").performScrollTo().performClick(); rule.onNodeWithTag("past_delete_confirm").performClick()
        rule.waitForIdle()
        assertTrue(root.list()!!.isEmpty())
        rule.onNodeWithTag("past_empty").assertIsDisplayed()
        assertFalse(File(root, "r_c").exists())
    }
}
