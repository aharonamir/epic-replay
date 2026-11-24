package app.epicreplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.ModelChoice
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.KeyStatus
import app.epicreplay.core.setup.SettingsController
import app.epicreplay.ui.SettingsActions
import app.epicreplay.ui.SettingsPage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** T426b: the settings page on the phone with real files: the models chosen, the model files with their sizes and a delete that leaves nothing and clears the choice, the server form with its key, the keys, the storage. */
@RunWith(AndroidJUnit4::class)
class SettingsPageTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph: AppGraph
    private val keptDirs = listOf("models", "secrets", "settings", "runs")
    private fun kept(d: String) = File(ctx.filesDir, "$d.kept-by-test")
    private var back = 0; private var changed = 0; private var past = 0

    /** The phone's own models, keys, choices and runs are set aside by a rename and put back after, as in the first-run tests. */
    @Before fun setUp() {
        for (d in keptDirs) {
            val dir = File(ctx.filesDir, d)
            if (dir.exists() && !kept(d).exists()) check(dir.renameTo(kept(d))) { "could not set $d aside" } else if (dir.exists()) dir.deleteRecursively()
        }
        graph = AppGraph(ctx)
    }

    @After fun tearDown() {
        for (d in keptDirs) { File(ctx.filesDir, d).deleteRecursively(); if (kept(d).exists()) check(kept(d).renameTo(File(ctx.filesDir, d))) { "could not put $d back" } }
    }

    private val device: OnDeviceProfile get() = graph.profiles.profiles.values.filterIsInstance<OnDeviceProfile>().first()
    private fun settings() = SettingsController({ graph.profiles }, ModelChoice(graph.modelChoice, { graph.profiles }), graph.modelDownloader(), { false })

    private fun show() = rule.setContent {
        MaterialTheme {
            SettingsPage(graph.setup, graph.keys, SettingsActions({ settings().installed() }, { p -> try { settings().delete(p); null } catch (e: Exception) { e.message } },
                { app.epicreplay.core.ui.PastRuns.storage(File(ctx.filesDir, "runs")) }, { changed++ }, { past++ }, { back++ }))
        }
    }

    @Test
    fun thePageShowsTheModelsChosenAndWhatIsOnThePhone() {
        graph.setup.choose(Role.VISION, device.id); graph.setup.choose(Role.TEXT, "deepseek-flash")
        show()
        rule.onNodeWithTag("settings_page").assertIsDisplayed()
        rule.onNodeWithTag("model_vision").assertTextContains(device.label, substring = true)
        rule.onNodeWithTag("model_text").assertTextContains("DeepSeek", substring = true)
        rule.onNodeWithTag("no_models").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings_models").performClick(); assertEquals(1, changed)
        rule.onNodeWithTag("settings_past").performScrollTo().performClick(); assertEquals(1, past)
        rule.onNodeWithTag("settings_back").performScrollTo().performClick(); assertEquals(1, back)
    }

    @Test
    fun aModelOnThePhoneShowsItsSizeAndADeleteRemovesEveryFileAndMakesTheRoleAskAgain() {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val name = device.files.first().name
        File(dir, "$name.part").writeBytes(ByteArray(3_000_000)) // a download that was not finished
        graph.setup.choose(Role.VISION, device.id); graph.setup.choose(Role.TEXT, "deepseek-flash")
        show()
        rule.onNodeWithTag("size_${device.id}").performScrollTo().assertTextContains("not finished", substring = true)
        rule.onNodeWithTag("delete_model_${device.id}").performScrollTo().performClick()
        rule.onNodeWithTag("delete_model_cancel").performClick()
        assertTrue(File(dir, "$name.part").isFile)
        rule.onNodeWithTag("delete_model_${device.id}").performScrollTo().performClick()
        rule.onNodeWithTag("delete_model_confirm").performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertNull(graph.setup.chosen(Role.VISION)); assertEquals("deepseek-flash", graph.setup.chosen(Role.TEXT)?.id)
        rule.onAllNodesWithTag("installed_${device.id}").assertCountEquals(0)
        rule.onNodeWithTag("model_vision").performScrollTo().assertTextContains("Not chosen yet", substring = true)
    }

    @Test
    fun theServerFormTakesAddressModelAndKeyTogetherAndLeavesTheChoicesAlone() {
        graph.setup.choose(Role.VISION, "deepseek-flash"); graph.setup.choose(Role.TEXT, "deepseek-flash")
        show()
        rule.onNodeWithTag("settings_server").performScrollTo().performClick()
        rule.onNodeWithTag("server_base").performScrollTo().performTextReplacement("https://api.openai.com/v1")
        rule.onNodeWithTag("server_model").performScrollTo().performTextInput("gpt-x")
        rule.onNodeWithTag("server_key").performScrollTo().performTextInput("sk-openai-style-0123456789")
        rule.onNodeWithTag("server_save").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("gpt-x", graph.setup.customServer()?.model)
        assertEquals(KeyStatus.PRESENT, graph.keys.rows().first { it.kind == KeyKind.CUSTOM_SERVER }.status)
        assertEquals("deepseek-flash", graph.setup.chosen(Role.VISION)?.id); assertEquals("deepseek-flash", graph.setup.chosen(Role.TEXT)?.id)
        rule.onNodeWithTag("own_server_summary").performScrollTo().assertTextContains("api.openai.com", substring = true)
        graph.customServers.delete(); graph.reloadProfiles()
    }

    @Test
    fun theStorageLineCountsTheFinishedReplays() {
        val runs = File(ctx.filesDir, "runs/r_x").apply { mkdirs() }
        File(runs, "manifest.json").writeText("""{"run_id":"r_x","created_at":"2026-11-24T10:00:00Z","status":"done","stages":[]}""")
        File(runs, "final.mp4").writeBytes(ByteArray(2_000_000))
        show()
        rule.onNodeWithTag("storage").performScrollTo().assertTextContains("1 replay", substring = true)
        assertFalse(File(ctx.filesDir, "runs/r_x").list()!!.isEmpty())
    }
}
