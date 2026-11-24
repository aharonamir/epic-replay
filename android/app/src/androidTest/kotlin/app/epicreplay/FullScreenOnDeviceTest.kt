package app.epicreplay

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.Role
import app.epicreplay.core.secrets.KeyKind
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T426b, FR-PLAYER-1, in the app's own activity (which does not restart when turned): a finished replay opened from the past list plays full screen, the phone is turned to landscape for a
 * landscape video, and Exit puts everything back. The phone's own keys, choices, models and runs are set aside by a rename and put back after.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val keptDirs = listOf("models", "secrets", "settings", "runs")
    private fun kept(d: String) = File(ctx.filesDir, "$d.kept-by-test")
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        for (d in keptDirs) {
            val dir = File(ctx.filesDir, d)
            if (dir.exists() && !kept(d).exists()) check(dir.renameTo(kept(d))) { "could not set $d aside" } else if (dir.exists()) dir.deleteRecursively()
        }
        val graph = AppGraph(ctx)
        graph.setup.choose(Role.VISION, "deepseek-flash"); graph.setup.choose(Role.TEXT, "deepseek-flash")
        check(graph.keys.save(KeyKind.MODEL_PROVIDER, "sk-test-provider-0123456789") == null); check(graph.keys.save(KeyKind.ELEVENLABS, "voice-test-key-0123456789") == null)
        graph.setup.confirmConsent()
        val d = File(ctx.filesDir, "runs/r_full").apply { mkdirs() }
        inst.context.assets.open("fixtures/clip/work_15s_1080p.mp4").use { i -> File(d, "final.mp4").outputStream().use { i.copyTo(it) } }
        File(d, "manifest.json").writeText("""{"run_id":"r_full","created_at":"2026-11-24T10:00:00Z","status":"done","style":"football_commentator","language":"en","total_usd":0.01,"stages":[]}""")
        File(d, "user_context.json").writeText("{}")
        File(d, "script.json").writeText("""{"headline":"Full screen test","lines":[]}""")
    }

    @After fun tearDown() {
        scenario?.close()
        for (d in keptDirs) { File(ctx.filesDir, d).deleteRecursively(); if (kept(d).exists()) check(kept(d).renameTo(File(ctx.filesDir, d))) { "could not put $d back" } }
    }

    private fun requested(): Int { var r = 0; scenario!!.onActivity { r = it.requestedOrientation }; return r }

    @Test
    fun aFinishedReplayPlaysFullScreenInLandscapeAndExitPutsItBack() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("past").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("past").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("open_r_full").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("open_r_full").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fullscreen").fetchSemanticsNodes().isNotEmpty() }
        val before = requested()
        rule.onNodeWithTag("fullscreen").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fullscreen_player").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
        rule.onNodeWithTag("exit_fullscreen").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fullscreen_player").fetchSemanticsNodes().isEmpty() }
        assertEquals(before, requested())
        assertTrue(rule.onAllNodesWithTag("player").fetchSemanticsNodes().isNotEmpty())
    }
}
