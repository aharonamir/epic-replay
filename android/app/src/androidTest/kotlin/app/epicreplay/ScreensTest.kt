package app.epicreplay

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.card.PlayCard
import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.style.PackFile
import app.epicreplay.core.style.StyleLoader
import app.epicreplay.core.ui.CardAnswerInput
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.RunState
import app.epicreplay.core.ui.StartForm
import app.epicreplay.core.ui.StartInput
import app.epicreplay.ui.CardActions
import app.epicreplay.ui.EpicTheme
import app.epicreplay.ui.PlayCardPanel
import app.epicreplay.ui.ProgressScreen
import app.epicreplay.ui.StartActions
import app.epicreplay.ui.StartScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** T425: the three screens on the phone with their parts, from data and with no run behind them: what is enabled when, what each tap sends. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val rule = createComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val appAssets = inst.targetContext.assets
    private val tests = inst.context.assets
    private fun text(a: android.content.res.AssetManager, p: String) = a.open(p).bufferedReader().use { it.readText() }
    private fun json(p: String) = Json.parseToJsonElement(text(tests, p)).jsonObject
    private val words = PageWords.parse(text(appAssets, "copy/page.json"))
    private val cards = StartForm.narrators(
        StyleLoader(text(appAssets, "contracts/style_pack.schema.json"), Json.parseToJsonElement(text(appAssets, "styles/voices.json")) as JsonObject, { true })
            .load(appAssets.list("styles")!!.filter { it.endsWith(".json") && it != "voices.json" }.map { PackFile(it, text(appAssets, "styles/$it")) }),
    )
    private val consent = ConsentCopy(text(appAssets, "copy/consent.json"))

    @Test
    fun theStartScreenNeedsAClipAndTheConsentAndSendsWhatWasChosen() {
        var clip by mutableStateOf<String?>(null)
        var made: StartInput? = null
        var chosen: String? = null
        rule.setContent { EpicTheme { StartScreen(cards, words, consent.consentText, "Still frames go to the vision provider.", clip, null, StartActions({}, {}), { chosen = it.id }, { made = it }) } }
        rule.onNodeWithTag("make").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("FILM IT.").assertExists()
        rule.onNodeWithTag("consent").performScrollTo().performClick()
        rule.onNodeWithTag("make").performScrollTo().assertIsNotEnabled() // the consent alone is not enough
        clip = "strike.mp4"; rule.waitForIdle()
        rule.onNodeWithTag("make").performScrollTo().assertIsEnabled() // a clip and the consent
        rule.onNodeWithTag("narrator_news_reporter").performScrollTo().performClick()
        assertEquals("news_reporter", chosen)
        rule.onNodeWithTag("language_zh").performScrollTo().performClick()
        rule.onNodeWithTag("player_name").performScrollTo().performTextInput("Noa")
        rule.onNodeWithTag("mute").performScrollTo().performClick()
        rule.onNodeWithTag("make").performScrollTo().performClick()
        assertEquals(StartInput("strike.mp4", "news_reporter", "zh", "Noa", true, true), made)
    }

    private val manifest = Json.parseToJsonElement(
        """{"status":"waiting_play_card","style":"football_commentator","language":"en","stages":[
        {"stage":"S0","status":"ok","duration_s":2.0,"cost_usd":0.0,"note":""},{"stage":"S1","status":"ok","duration_s":3.0,"cost_usd":0.0,"note":""},
        {"stage":"S2","status":"ok","duration_s":10.0,"cost_usd":0.01,"note":""},{"stage":"S3","status":"ok","duration_s":1.0,"cost_usd":0.0,"note":""},
        {"stage":"A","status":"waiting","duration_s":0.0,"cost_usd":0.0,"note":"waiting for the play card"}]}""",
    ).jsonObject

    @Test
    fun theProgressScreenShowsTheEightStepsTheBarAndOpensTheCardAtStepFour() {
        val state = RunState.of(manifest, emptyMap(), words, hasClip = true, hasVideo = false)
        rule.setContent { EpicTheme { ProgressScreen(state, words, {}) { Text("the card", Modifier.testTag("card_slot")) } } }
        rule.onNodeWithTag("step_n").assertTextEquals("Step 4 of 8")
        rule.onNodeWithTag("status_line").assertTextEquals("YOUR TURN.")
        for (n in 1..3) rule.onNodeWithTag("segment_${n}_done").assertExists()
        rule.onNodeWithTag("segment_4_waiting_user").assertExists(); rule.onNodeWithTag("segment_5_waiting").assertExists()
        rule.onNodeWithTag("step_1").assertTextContains("Checking your clip", substring = true); rule.onNodeWithTag("step_1").assertTextContains("5 s", substring = true)
        rule.onNodeWithTag("card_slot").assertExists()
        rule.onAllNodesWithTag("another").assertCountEquals0()
    }

    @Test
    fun aStoppedRunSaysWhatHappenedInThePagesWordsAndOffersAnotherClip() {
        val stopped = Json.parseToJsonElement("""{"status":"stopped","style":null,"language":"en","stages":[{"stage":"S0","status":"ok","duration_s":1.0,"note":""},{"stage":"S1","status":"ok","duration_s":1.0,"note":""},{"stage":"S2","status":"ok","duration_s":1.0,"note":""},{"stage":"S3","status":"stopped","duration_s":1.0,"note":"sensitive: someone may be hurt or upset."}]}""").jsonObject
        var again = 0
        rule.setContent { EpicTheme { ProgressScreen(RunState.of(stopped, emptyMap(), words, true, false), words, { again++ }) } }
        rule.onNodeWithTag("status_line").assertTextContains("SOMEONE MAY BE HURT OR UPSET", substring = true)
        rule.onNodeWithTag("another").performClick()
        assertEquals(1, again)
    }

    private val ps = json("fixtures/sample_run/play_sheet.json").let { p ->
        JsonObject(p + ("events" to JsonArray(p.getValue("events").jsonArray.map { e -> if (e.jsonObject.getValue("id").jsonPrimitive.content == "e5") JsonObject(e.jsonObject + ("confidence" to JsonPrimitive(0.5))) else e })))
    }
    private val meta = json("fixtures/sample_run/clip_meta.json")

    @Test
    fun thePlayCardWantsTheMomentAndTheMarkedItemBeforeYesAndSendsWhatTheUserDid() {
        var sent: CardAnswerInput? = null
        rule.setContent { EpicTheme { Scrolling { PlayCardPanel(ps, meta, 0.05, words, { null }, CardActions({ sent = it }, {}, {})) } } }
        rule.onNodeWithTag("yes").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("why_not").assertTextContains("Pick the moment first", substring = true)
        rule.onNodeWithTag("this_one").performScrollTo().assertIsEnabled().assertTextContains("THIS ONE", substring = true, ignoreCase = true) // a filled button while the moment is unanswered
        rule.onNodeWithTag("frame_label").assertTextContains("Frame 313", substring = true)
        rule.onNodeWithTag("later").performScrollTo().performClick()
        rule.onNodeWithTag("frame_label").assertTextContains("Frame 314", substring = true) // a tap moves one frame, and answers the moment
        rule.onNodeWithTag("this_one").performScrollTo().assertTextContains("✓", substring = true) // and a ticked one once it is answered (the nudge answered it)
        rule.onNodeWithTag("on10").performScrollTo().performClick()
        rule.onNodeWithTag("frame_label").assertTextContains("Frame 324", substring = true)
        rule.onNodeWithTag("yes").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("why_not").assertTextContains("Keep or remove each item", substring = true)
        rule.onNodeWithTag("remove_e5").performScrollTo().performClick()
        rule.onNodeWithTag("yes").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("cost").assertTextContains("$0.05", substring = true)
        rule.onNodeWithTag("yes").performClick()
        assertEquals(324, sent!!.frame); assertEquals("""{"events":[{"id":"e5","keep":false}]}""", sent!!.edits.toString()); assertTrue(!sent!!.generic)
    }

    @Test
    fun anotherMomentFixSomethingAndNotAtAllDoWhatTheyShouldOnTheCard() {
        var sent: CardAnswerInput? = null
        var stopped: String? = null
        rule.setContent { EpicTheme { Scrolling { PlayCardPanel(ps, meta, 0.05, words, { null }, CardActions({ sent = it }, { stopped = it }, {})) } } }
        val other = rule.onNodeWithTag("other_289").performScrollTo().fetchSemanticsNode().size
        assertTrue("the time of another moment is on one line: ${other.height} px high", other.height <= 160) // a button is at least 48 dp (about 150 px); a time broken over lines is far taller
        rule.onNodeWithTag("other_289").performClick() // the other candidate becomes the key moment
        rule.onNodeWithTag("frame_label").assertTextContains("Frame 289", substring = true)
        rule.onNodeWithTag("fix").performScrollTo().performClick()
        rule.onNodeWithTag("edit_activity").performScrollTo().performTextReplacement("football set piece")
        rule.onNodeWithTag("keep_e5").performScrollTo().performClick()
        rule.onNodeWithTag("yes").performScrollTo().performClick()
        assertTrue(sent!!.edits.toString().contains("\"activity\":\"football set piece\""))
        sent = null
        rule.onNodeWithTag("reject").performScrollTo().performClick()
        rule.onNodeWithTag("generic").assertIsEnabled() // the moment was answered
        rule.onNodeWithTag("back_to_card").performClick()
        rule.onNodeWithTag("reject").performScrollTo().performClick()
        rule.onNodeWithTag("stop_here").performClick()
        assertEquals("stopped at the play card", stopped)
        assertTrue(sent == null)
    }

    @Test
    fun aClipNobodyCheckedAsksIfEveryoneInItIsFineBeforeAnythingElse() {
        val unchecked = JsonObject(ps + ("sensitive" to Json.parseToJsonElement("""{"flag":false,"reason":null,"checked":false}""")))
        var fine = 0; var stopped: String? = null
        rule.setContent { EpicTheme { PlayCardPanel(unchecked, meta, 0.05, words, { null }, CardActions({}, { stopped = it }, { fine++ })) } }
        rule.onNodeWithTag("fine_question").assertExists(); rule.onAllNodesWithTag("yes").assertCountEquals0()
        rule.onNodeWithTag("fine_no").performClick(); assertEquals("you said someone in it may not be fine", stopped)
    }

    private fun shot(name: String) {
        val out = java.io.File(inst.targetContext.filesDir, "t425_$name.png")
        out.outputStream().use { o -> rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 90, o) }
    }

    /** The three screens drawn for the owner to look at (files/t425_*.png; nothing is asserted but that they draw). */
    @Test
    fun theScreensDrawForTheOwnerToLookAt() {
        val clip = java.io.File(inst.targetContext.cacheDir, "shots.mp4").also { f -> tests.open("fixtures/clip/work_15s_1080p.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
        val frames = app.epicreplay.media.ClipFrames(clip.path)
        val source = app.epicreplay.ui.FrameSource { n -> frames.frameAt(n)?.asImageBitmap() }
        val state = RunState.of(manifest, emptyMap(), words, hasClip = true, hasVideo = false)
        var showCard by mutableStateOf(false)
        rule.setContent {
            if (!showCard) EpicTheme { StartScreen(cards, words, consent.consentText, "Still frames go to the vision provider.", "strike.mp4", null, StartActions({}, {}), {}, {}) }
            else EpicTheme(app.epicreplay.ui.accentOf("#FFD23F", null)) { ProgressScreen(state, words, {}) { PlayCardPanel(ps, meta, 0.05, words, source, CardActions({}, {}, {})) } }
        }
        shot("start")
        showCard = true
        rule.waitForIdle(); Thread.sleep(800); rule.waitForIdle()
        shot("card")
        frames.close()
    }

    @Test
    fun aMissingVoiceKeyIsSaidBeforeTheRunAndAFailedRunOffersToTryAgainFromWhereItBroke() {
        var key = 0; var retried = 0
        rule.setContent { EpicTheme { StartScreen(cards, words, consent.consentText, "x", "strike.mp4", null, StartActions({}, {}), {}, {}, voiceKeyMissing = true, onAddKey = { key++ }) } }
        rule.onNodeWithTag("voice_key_missing").performScrollTo().assertTextContains("ElevenLabs key", substring = true)
        rule.onNodeWithTag("consent").performScrollTo().performClick()
        rule.onNodeWithTag("make").performScrollTo().assertIsNotEnabled() // a clip and the consent are not enough without the voice
        rule.onNodeWithTag("add_key").performScrollTo().performClick(); assertEquals(1, key)
    }

    @Test
    fun aRunThatFailedAtTheVoiceSaysSoAndOffersTheKeyAndTryAgain() {
        val failed = Json.parseToJsonElement("""{"status":"failed","style":null,"language":"en","stages":[{"stage":"S0","status":"ok","duration_s":1.0,"note":""},{"stage":"S7","status":"failed","duration_s":1.0,"note":"The voice could not be made (SpeechError: the ElevenLabs key is not set)"}]}""").jsonObject
        var retried = 0; var key = 0
        rule.setContent { EpicTheme { ProgressScreen(RunState.of(failed, emptyMap(), words, true, false), words, {}, voiceKey = true, onRetry = { retried++ }, onAddKey = { key++ }) } }
        rule.onNodeWithTag("status_line").assertTextContains("RECORDING THE VOICE", substring = true)
        rule.onNodeWithTag("voice_key_note").assertTextContains("ElevenLabs key", substring = true)
        rule.onNodeWithTag("add_key").performScrollTo().performClick(); rule.onNodeWithTag("retry").performScrollTo().performClick()
        assertEquals(1, key); assertEquals(1, retried)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() = assertCountEquals(0)

@androidx.compose.runtime.Composable
private fun Scrolling(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { content() }
}
