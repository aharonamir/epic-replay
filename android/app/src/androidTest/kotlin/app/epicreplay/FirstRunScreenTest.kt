package app.epicreplay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
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
import app.epicreplay.core.download.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)

    private val kept = File(ctx.filesDir, "models.kept-by-test")

    /** Secrets and settings are cleared; downloaded models are set aside (a rename) and put back, never deleted. */
    private fun clean() {
        for (d in listOf("secrets", "settings")) File(ctx.filesDir, d).deleteRecursively()
        File(ctx.filesDir, "models").deleteRecursively()
    }

    @Before fun setUp() {
        val models = File(ctx.filesDir, "models")
        if (models.exists() && !kept.exists()) check(models.renameTo(kept)) { "could not set the models aside" }
        clean(); graph = AppGraph(ctx)
    }

    @After fun tearDown() {
        clean()
        if (kept.exists()) check(kept.renameTo(File(ctx.filesDir, "models"))) { "could not put the models back" }
    }

    private fun show() = rule.setContent {
        MaterialTheme {
            FirstRunScreen(graph.setup, graph.keys, FirstRunActions(
                startDownload = { p: OnDeviceProfile -> downloads += p.id },
                cancelDownload = {},
                downloadStatus = status,
                acknowledgeDownload = {},
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
        rule.onNodeWithText("Frames go to DeepSeek (cloud); costs money.", substring = true).assertExists()
        rule.onAllNodesWithText("Stays on this phone; free; needs a", substring = true).assertCountEquals(4) // two models, for each of the two roles
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
        rule.onNodeWithText("Still frames from the clip, and descriptions drawn from them with the script, are sent to the model providers", substring = true).assertExists()
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
