package app.epicreplay.core.ui

import app.epicreplay.core.run.FlowOrder
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.run.RunFlow
import app.epicreplay.core.run.StageResult
import app.epicreplay.core.run.StageRunner
import app.epicreplay.core.style.StyleLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The logic behind the three screens: the start form, the play card and the run in hand, with no screen and no model. */
class UiLogicTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val styles = StyleLoader.loadShared(shared)
    private val words = PageWords.load(shared)
    private fun json(f: String) = Json.parseToJsonElement(File(shared, f).readText()).jsonObject
    private val ps = json("fixtures/sample_run/play_sheet.json"); private val meta = json("fixtures/sample_run/clip_meta.json")

    @Test
    fun theNarratorsComeFromThePacksInTheirOrderAndTheDefaultIsPreselected() {
        val cards = StartForm.narrators(styles)
        assertEquals(listOf("football_commentator", "news_reporter", "nature_documentary"), cards.map { it.id })
        assertEquals("football_commentator", StartForm.defaultNarrator(styles))
        assertEquals("Football commentator", cards[0].name); assertEquals("OFF THE POST AND IN! Unbelievable!", cards[0].caption); assertEquals("#FFD23F", cards[0].accent)
        assertTrue(cards.all { it.offersMandarin }) // the three packs have a zh block
    }

    @Test
    fun makeMyReplayNeedsAClipAndTheConsentAndTheUserContextIsThePcs() {
        val card = StartForm.narrators(styles)[1]
        val i = StartInput(null, card.id, "zh", "  Lee   Park  ", true, false)
        assertFalse(StartForm.canStart(i)); assertFalse(StartForm.canStart(i.copy(clipName = "clip.mp4"))); assertFalse(StartForm.canStart(i.copy(consent = true)))
        assertTrue(StartForm.canStart(i.copy(clipName = "clip.mp4", consent = true)))
        val uc = StartForm.userContext(i.copy(clipName = "clip.mp4", consent = true), card, JsonObject(mapOf("confirmed" to JsonPrimitive(true))))
        assertEquals("news_reporter", uc.getValue("style").jsonPrimitive.content); assertEquals("zh", uc.getValue("language").jsonPrimitive.content)
        assertEquals("Lee Park", uc.getValue("player_name").jsonPrimitive.content); assertEquals("false", uc.getValue("keep_original_audio").jsonPrimitive.content)
        assertEquals(json("fixtures/sample_run/user_context.json").keys + "language", uc.keys) // the same fields as the PC's (the sample run is older than `language`)
        assertNull(StartForm.cleanName("   ")); assertEquals(40, StartForm.cleanName("x".repeat(60))!!.length)
        assertEquals("en", StartForm.language(card.copy2(mandarin = false), "zh"))
    }

    private fun NarratorCard.copy2(mandarin: Boolean) = NarratorCard(id, name, description, caption, accent, mandarin)

    @Test
    fun theKeyMomentIsAlwaysAnItemToCheckAndTheCardIsConfirmedOnlyWhenEverythingIsAnswered() {
        val unsure = JsonObject(ps + ("events" to kotlinx.serialization.json.JsonArray(ps.getValue("events").jsonArray.map { e -> if (e.jsonObject.getValue("id").jsonPrimitive.content == "e5") JsonObject(e.jsonObject + ("confidence" to JsonPrimitive(0.5))) else e })))
        var s = CardModel.start(unsure, meta)
        assertEquals(313, s.frame); assertEquals(449, s.lastFrame); assertEquals(10.43, s.seconds)
        assertEquals(words.card("why"), CardModel.whyNot(unsure, s, words)) // the moment first
        s = CardModel.thisOne(s)
        assertEquals(words.card("why_items"), CardModel.whyNot(unsure, s, words)) // then the item marked "Check this"
        assertEquals(listOf("e5"), CardModel.events(unsure, s).filter { it.toCheck }.map { it.id })
        s = CardModel.decide(s, "e5", false)
        assertNull(CardModel.whyNot(unsure, s, words))
        val input = CardModel.answerInput(s)
        assertEquals(313, input.frame); assertEquals("""{"events":[{"id":"e5","keep":false}]}""", input.edits.toString())
        assertEquals(listOf(289 to "foot strikes ball; motion peak"), CardModel.otherMoments(unsure))
    }

    @Test
    fun nudgingMovesTheFrameAndStopsAtTheEndsOfTheClip() {
        var s = CardModel.start(ps, meta)
        s = CardModel.nudge(s, 1); assertEquals(314, s.frame); assertTrue(s.keyMomentAnswered)
        s = CardModel.nudge(s, -10); assertEquals(304, s.frame)
        assertEquals(0, CardModel.nudge(s, -1000).frame); assertEquals(449, CardModel.nudge(s, 1000).frame)
        assertEquals(289, CardModel.pick(s, 289).frame)
    }

    @Test
    fun theSentencesTypedInFixSomethingAreSentOnlyWhenChangedAndTheyCanBeCheckedByTheRules() {
        var s = CardModel.thisOne(CardModel.start(ps, meta))
        assertEquals("{}", CardModel.answerInput(s).edits.toString())
        s = CardModel.editActivity(CardModel.toggleFixing(s), "football set piece")
        assertEquals("""{"activity":"football set piece"}""", CardModel.answerInput(s).edits.toString())
        assertTrue(CardModel.answerInput(s, generic = true).generic)
    }

    @Test
    fun aClipNobodyCheckedAsksWhetherEveryoneInItIsFineFirst() {
        val unchecked = JsonObject(ps + ("sensitive" to Json.parseToJsonElement("""{"flag":false,"reason":null,"checked":false}""")))
        val s = CardModel.thisOne(CardModel.start(unchecked, meta))
        assertTrue(s.needsFineCheck); assertEquals(words.card("why_check"), CardModel.whyNot(unchecked, s, words))
        assertNull(CardModel.whyNot(unchecked, CardModel.fine(s), words))
    }

    private fun scripted(dir: File): RunFlow = RunFlow(dir, FlowOrder.ORDER.filter { it.name != "A" }.associate { def ->
        def.name to StageRunner { ctx ->
            for (o in def.outputs) File(ctx.dir, o).writeText(if (o == "play_sheet.json") File(shared, "fixtures/sample_run/play_sheet.json").readText().replace("\"confirmed_by_user\": true", "\"confirmed_by_user\": false") else if (o == "clip_meta.json") File(shared, "fixtures/sample_run/clip_meta.json").readText() else "{}")
            StageResult("ok")
        }
    }, { true }, JsonObject(emptyMap()))

    @Test
    fun aRunGoesFromTheClipToTheCardIsAnsweredAndGoesOnToTheEnd() = runBlocking {
        val runs = Files.createTempDirectory("runs").toFile()
        val session = RunSession(runs, words, CoroutineScope(Dispatchers.Default), ::scripted, pollMs = 20)
        val clip = File(runs, "my clip.MP4").apply { writeText("video") }
        val dir = session.create(clip, json("fixtures/sample_run/user_context.json"))
        assertTrue(File(dir, "upload.mp4").isFile && File(dir, "manifest.json").isFile)
        assertEquals("running", session.view.value!!.state.status)
        session.start(dir)
        withTimeout(20_000) { while (session.view.value?.atCard != true) kotlinx.coroutines.delay(20) }
        var v = session.view.value!!
        assertEquals("waiting_play_card", v.state.status); assertEquals(4, v.state.stepN); assertEquals("waiting_user", v.state.steps[3].status)
        assertEquals(dir, session.latestOpen())
        // a refused answer changes nothing; a good one goes on to the end
        assertFailsWith<IllegalArgumentException> { session.answer(dir, CardAnswerInput(9999, JsonObject(emptyMap()), false)) }
        session.answer(dir, CardAnswerInput(289, JsonObject(emptyMap()), false))
        withTimeout(20_000) { while (session.view.value?.state?.status != "done") kotlinx.coroutines.delay(20) }
        v = session.view.value!!
        assertEquals(8, v.state.stepN); assertTrue(v.state.steps.all { it.status == "done" }); assertEquals(words.confirmed, v.state.steps[3].name)
        assertEquals(289, RunFiles.read(File(dir, "play_sheet.json"))!!.getValue("climax").jsonObject.getValue("frame").jsonPrimitive.content.toInt())
        assertNull(session.latestOpen())
    }

    @Test
    fun stoppingAtTheCardEndsTheRunWithItsOwnMessageAndTheMedianOfPastRunsIsTheTypicalTime() = runBlocking {
        val runs = Files.createTempDirectory("runs").toFile()
        val session = RunSession(runs, words, CoroutineScope(Dispatchers.Default), ::scripted, pollMs = 20)
        val dir = session.create(File(runs, "c.mp4").apply { writeText("v") }, json("fixtures/sample_run/user_context.json"))
        session.start(dir)
        withTimeout(20_000) { while (session.view.value?.atCard != true) kotlinx.coroutines.delay(20) }
        session.stopAtCard(dir, RunSession.REJECTED)
        val v = session.view.value!!
        assertEquals("stopped", v.state.status); assertEquals(words.stopped("rejected"), v.state.message)
        // typical: each stage's median over the runs' ok and degraded records
        assertTrue(RunSession.typicalOf(runs).keys.containsAll(listOf("S0", "S1", "S2", "S3")))
        assertFalse(RunSession.typicalOf(runs).containsKey("A"))
    }
}

class UiLogicRulesTest {
    private val shared = File(System.getProperty("shared.dir"))
    private fun json(f: String) = Json.parseToJsonElement(File(shared, f).readText()).jsonObject
    private val ps = json("fixtures/sample_run/play_sheet.json"); private val meta = json("fixtures/sample_run/clip_meta.json"); private val uc = json("fixtures/sample_run/user_context.json")

    @Test
    fun theGenericAnswerSendsTheKeyMomentAloneWhateverWasDecidedBefore() {
        var s = CardModel.decide(CardModel.editActivity(CardModel.thisOne(CardModel.start(ps, meta)), "other"), "e5", false)
        val g = CardModel.answerInput(s, generic = true)
        assertEquals("{}", g.edits.toString()); assertTrue(g.generic); assertEquals(313, g.frame)
    }

    @Test
    fun aNonListOfEventEditsIsRefusedAsThePcRefusesIt() {
        for (bad in listOf("\"abc\"", "{\"id\":\"e1\"}", "5", "true")) {
            val e = assertFailsWith<IllegalArgumentException> { app.epicreplay.core.card.PlayCard.answer(ps, uc, meta, 313, Json.parseToJsonElement("""{"events":$bad}""").jsonObject, JsonObject(emptyMap())) }
            assertEquals("each event edit is {id, keep} with an event of this play sheet", e.message)
        }
        for (empty in listOf("null", "[]", "\"\"", "0", "false", "{}")) assertNotNull(app.epicreplay.core.card.PlayCard.answer(ps, uc, meta, 313, Json.parseToJsonElement("""{"events":$empty}""").jsonObject, JsonObject(emptyMap())))
    }
}

class RunSessionConcurrencyTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val words = PageWords.load(shared)

    @Test
    fun aStartWhileAnotherFlowEndsWaitsForItAndNeverRunsTwoAtOnce() = kotlinx.coroutines.runBlocking {
        val runs = java.nio.file.Files.createTempDirectory("runs").toFile()
        val active = java.util.concurrent.atomic.AtomicInteger(); val most = java.util.concurrent.atomic.AtomicInteger(); val started = java.util.concurrent.atomic.AtomicInteger()
        fun flow(dir: File) = RunFlow(dir, FlowOrder.ORDER.filter { it.name != "A" }.associate { def ->
            def.name to StageRunner { ctx ->
                if (def.name == "S0") { started.incrementAndGet(); most.set(maxOf(most.get(), active.incrementAndGet())); try { kotlinx.coroutines.delay(150) } finally { active.decrementAndGet() } }
                for (o in def.outputs) File(ctx.dir, o).writeText(if (o == "play_sheet.json") File(shared, "fixtures/sample_run/play_sheet.json").readText().replace("\"confirmed_by_user\": true", "\"confirmed_by_user\": false") else if (o == "clip_meta.json") File(shared, "fixtures/sample_run/clip_meta.json").readText() else "{}")
                StageResult("ok")
            }
        }, { true }, JsonObject(emptyMap()))
        val session = RunSession(runs, words, CoroutineScope(Dispatchers.Default), ::flow, pollMs = 20)
        val dir = session.create(File(runs, "c.mp4").apply { writeText("v") }, Json.parseToJsonElement(File(shared, "fixtures/sample_run/user_context.json").readText()).jsonObject)
        session.start(dir); session.start(dir); session.pause(); session.start(dir)
        withTimeout(30_000) { while (session.view.value?.atCard != true || session.view.value?.working == true) kotlinx.coroutines.delay(20) }
        assertEquals(1, most.get(), "never two flows at once")
        assertTrue(started.get() >= 1)
    }
}
