package app.epicreplay

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.Secret
import app.epicreplay.ui.FirstRunActions
import app.epicreplay.ui.FirstRunScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T411: the first-run screen walks US1 (the choice and what each option means, the key before anything else, the
 * download prompt) and US3 (the consent words for the places chosen, asked again when a role moves to the cloud).
 * The graph is the app's own, over a clean private folder; the download and the model question are fakes (no network).
 */
@RunWith(AndroidJUnit4::class)
class FirstRunScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph: AppGraph
    private val asked = mutableListOf<Role>()
    private val downloads = mutableListOf<String>()

    private fun clean() {
        for (d in listOf("secrets", "settings", "models")) File(ctx.filesDir, d).deleteRecursively()
    }

    @Before fun setUp() { clean(); graph = AppGraph(ctx) }
    @After fun tearDown() { clean() }

    private fun show() = rule.setContent {
        MaterialTheme {
            FirstRunScreen(graph.setup, graph.keys, FirstRunActions(
                download = { p: OnDeviceProfile, _ -> downloads += p.id },
                unmetered = { true },
                ask = { role -> asked += role; "answer for $role" },
            ))
        }
    }

    @Test
    fun aFreshInstallAsksBothQuestionsWithWhatEachChoiceMeans() {
        show()
        rule.onNodeWithText("Where do the models run?").assertExists()
        rule.onNodeWithText("Looking at the clip").assertExists()
        rule.onNodeWithText("Writing the script").assertExists()
        rule.onNodeWithText("Frames go to DeepSeek V4 Flash; costs money.", substring = true).assertExists()
        rule.onNodeWithText("Stays on this phone; free; needs a", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun cloudWithNoKeyAsksForTheKeyAndMakesNoRequest() {
        graph.setup.choose(Role.VISION, "deepseek-flash"); graph.setup.choose(Role.TEXT, "deepseek-flash")
        show()
        rule.onNodeWithText("A key is needed before anything is sent.").assertExists()
        rule.onNodeWithText("Ask both models one question").assertDoesNotExist()
        assert(asked.isEmpty())
    }

    @Test
    fun thePhoneNotDownloadedShowsTheNoticeAndDownloadsOnTheTap() {
        graph.setup.choose(Role.VISION, "gemma-4-e2b-device"); graph.setup.choose(Role.TEXT, "gemma-4-e2b-device")
        show()
        rule.onNodeWithText("Licence: Apache-2.0", substring = true).assertExists()
        rule.onNodeWithText("Download").assertIsEnabled().performClick()
        rule.waitForIdle()
        assert(downloads == listOf("gemma-4-e2b-device")) { "$downloads" }
    }

    @Test
    fun theConsentNamesTheFramesAndTheTextAndNeedsTheTickBeforeAnything() {
        graph.setup.choose(Role.VISION, "deepseek-flash"); graph.setup.choose(Role.TEXT, "deepseek-flash")
        graph.secrets.put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        show()
        rule.onNodeWithText("Still frames from the clip are sent to", substring = true).assertExists()
        rule.onNodeWithText("I agree").assertIsNotEnabled()
        rule.onNodeWithText("I filmed this or have permission to use it.", substring = true).performClick()
        rule.onNodeWithText("I agree").assertIsEnabled().performClick()
        rule.onNodeWithText("Ask both models one question").assertExists()
    }

    @Test
    fun readyAsksBothRolesOneQuestionEach() {
        graph.setup.choose(Role.VISION, "deepseek-flash"); graph.setup.choose(Role.TEXT, "deepseek-flash")
        graph.secrets.put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        graph.setup.confirmConsent()
        show()
        rule.onNodeWithText("Ask both models one question").performClick()
        rule.waitUntil(5_000) { asked.size == 2 }
        rule.onNodeWithText("Vision: answer for VISION").assertExists()
        rule.onNodeWithText("Text: answer for TEXT").assertExists()
    }
}
