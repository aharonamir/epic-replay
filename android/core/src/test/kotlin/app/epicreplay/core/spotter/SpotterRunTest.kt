package app.epicreplay.core.spotter

import app.epicreplay.core.conformance.ConformanceRunner
import app.epicreplay.core.conformance.TextFunctions
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelError
import app.epicreplay.core.models.ModelResult
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Testing
import app.epicreplay.core.models.ToolDef
import app.epicreplay.core.models.Usage
import app.epicreplay.core.text.EnglishLexicon
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** S2 and S3 over a backend: the calls, the limits, what a failure leaves out, and the same play sheet as the PC from the same answers. */
class SpotterRunTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val spotter = Spotter(EnglishLexicon(File(shared, "lexicons/en.json").readText()), File(shared, "contracts/play_sheet.schema.json").readText())
    private val words = SpotterWords.load(shared)
    private val runner = ConformanceRunner(shared, TextFunctions.table(shared))

    /** Answers by the picture it is shown: the sheet's name is its JPEG; the events call is the one with a "Grid 1". */
    private class Routed(val sheet: (String) -> JsonObject?, val events: () -> JsonObject?) : ModelBackend {
        override val profile: Profile = Testing.profile()
        val calls = AtomicInteger()
        val questions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val detail = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult {
            calls.incrementAndGet(); questions += question; this.detail += detail
            val data = if (images.first().label == "Grid 1") events() else sheet(String(images.first().jpeg))
            return ModelResult(data?.toString() ?: "", data, emptyList(), Usage(10, 10), 0.0035, 0.1)
        }
        override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult = error("the Spotter does not chat")
    }

    private val renderer = SheetRenderer { frames -> RenderedSheet("sheet-${frames.first()}-${frames.last()}".toByteArray(), frames.mapIndexed { k, f -> RenderedSheet.Cell(k + 1, f, Math.round(f / 30.0 * 1000) / 1000.0) }) }

    private fun case(name: String): Pair<File, JsonObject> { val d = File(shared, "conformance/spotter/$name"); return d to runner.load(d) }
    private fun input(c: Pair<File, JsonObject>, key: String): JsonElement = runner.resolve(c.second.getValue("inputs").jsonObject.getValue(key).jsonObject, c.first)

    /** The case's own looks, replayed: each sheet gets its recorded answer, the events call its recorded one. The renderer draws the cells the case has. */
    private fun replay(name: String): Pair<SpotterOutcome, JsonObject> {
        val c = case(name)
        val info = input(c, "info").jsonObject
        val looks = input(c, "looks").jsonArray.map { it.jsonObject }
        val ev = input(c, "events_look").let { it as? JsonObject }
        val byFrames = looks.associate { l -> l.getValue("cells").jsonArray.map { it.jsonObject.getValue("frame").jsonPrimitive.content.toInt() } to l }
        val answers = looks.associate { l -> "sheet-${l.getValue("cells").jsonArray.first().jsonObject.getValue("frame").jsonPrimitive.content}-${l.getValue("cells").jsonArray.last().jsonObject.getValue("frame").jsonPrimitive.content}" to (l["answer"] as? JsonObject) }
        val backend = Routed({ answers[it] }) { ev?.get("answer") as? JsonObject }
        assertEquals(looks.size, byFrames.size)
        val out = runBlocking { SpotterRun(spotter, backend, words, renderer).run("run-1", info, input(c, "signals").jsonObject, input(c, "clip_meta").jsonObject, input(c, "snap_s").jsonPrimitive.content.toDouble(), "deepseek-flash") }
        return out to runner.resolve(c.second.getValue("expected").jsonObject, c.first).jsonObject
    }

    @Test
    fun theSameAnswersGiveThePcsPlaySheetForTheRealRecordedAnswers(): Unit {
        for (n in 1..6) {
            val (out, want) = replay("play_sheet_real_$n")
            assertEquals("ok", out.status, "real_$n: ${out.note}")
            assertEquals(emptyList(), ConformanceRunner.compare(out.playSheet!!, want.getValue("play_sheet"), 0.001), "real_$n")
        }
    }

    @Test
    fun theFiveSheetsAndTheEventsCallAreSentOnceEachWithTheWordsOfTheSharedFile(): Unit {
        val c = case("play_sheet_real_1")
        val info = input(c, "info").jsonObject
        val backend = Routed({ null }) { null }
        runBlocking { SpotterRun(spotter, backend, words, renderer).run("r", info, JsonObject(emptyMap()), input(c, "clip_meta").jsonObject, 0.25, "m") }
        assertEquals(6, backend.calls.get())
        assertEquals(1, backend.questions.count { it == words.eventsQuestion })
        assertEquals(setOf("high"), backend.detail.toSet())
        assertContains(backend.questions.first { it != words.eventsQuestion }, "numbered 1 to 9")
        assertTrue(backend.questions.any { "part 1 of 2" in it } && backend.questions.any { "part 2 of 2" in it })
        assertEquals(3, backend.questions.count { "sudden motion or sound" in it })
    }

    @Test
    fun aFailedSheetOrAFailedEventsCallLeavesOnlyItselfOut(): Unit {
        val c = case("play_sheet_real_2")
        val info = input(c, "info").jsonObject
        val looks = input(c, "looks").jsonArray.map { it.jsonObject }
        val failing = looks[0].getValue("cells").jsonArray.let { "sheet-${it.first().jsonObject.getValue("frame").jsonPrimitive.content}-${it.last().jsonObject.getValue("frame").jsonPrimitive.content}" }
        val answers = looks.associate { l -> val cs = l.getValue("cells").jsonArray; "sheet-${cs.first().jsonObject.getValue("frame").jsonPrimitive.content}-${cs.last().jsonObject.getValue("frame").jsonPrimitive.content}" to (l["answer"] as? JsonObject) }
        val backend = object : ModelBackend {
            val inner = Routed({ answers[it] }) { throw ModelError.Timeout("deepseek-flash", "timed out") }
            override val profile get() = inner.profile
            override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult {
                if (images.first().label == null && String(images.first().jpeg) == failing) throw ModelError.SchemaMisfit("deepseek-flash", "no fit")
                return inner.look(images, question, schema, detail)
            }
            override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?) = inner.chat(messages, schema, tools)
        }
        val out = runBlocking { SpotterRun(spotter, backend, words, renderer).run("r", info, input(c, "signals").jsonObject, input(c, "clip_meta").jsonObject, 0.25, "m") }
        assertEquals("ok", out.status)
        assertContains(out.note, "1 sheet(s) left out"); assertContains(out.note, "the events call was left out")
        val sheets = out.trace.getValue("sheets").jsonArray.map { it.jsonObject }
        assertEquals(1, sheets.count { it["answer"] is JsonNull })
        assertContains(sheets.first { it["answer"] is JsonNull }.getValue("left_out").jsonPrimitive.content, "SchemaMisfit")
        assertEquals(2, out.playSheet!!.getValue("events").jsonArray.size) // the fallback: two events
    }

    @Test
    fun everySheetFailedProposesTheStrongestCandidateAndNoCandidateIsAClearStop(): Unit {
        val c = case("play_sheet_every_sheet_failed")
        val info = input(c, "info").jsonObject
        val dead = Routed({ null }) { null }
        val out = runBlocking { SpotterRun(spotter, dead, words, renderer).run("r", info, JsonObject(emptyMap()), input(c, "clip_meta").jsonObject, 0.25, "m") }
        assertEquals("degraded", out.status)
        assertEquals("signal_peak", out.playSheet!!.getValue("climax").jsonObject.getValue("method").jsonPrimitive.content)
        val bare = JsonObject(info + ("candidates" to JsonArray(emptyList())))
        val none = runBlocking { SpotterRun(spotter, dead, words, renderer).run("r", bare, JsonObject(emptyMap()), input(c, "clip_meta").jsonObject, 0.25, "m") }
        assertEquals("failed", none.status); assertEquals(null, none.playSheet); assertContains(none.note, "no candidate")
    }

    @Test
    fun aSensitiveAnswerStopsTheRunWithItsReason(): Unit {
        val (out, _) = replay("play_sheet_a_sensitive_clip")
        assertEquals("stopped", out.status)
        assertContains(out.note, "sensitive: people smash shop windows")
        assertNotNull(out.playSheet)
    }

    @Test
    fun aDescriptorBreachIsEmptiedForTheUserAndSaidSo(): Unit {
        val (out, want) = replay("play_sheet_a_descriptor_breach_is_emptied")
        assertEquals("ok", out.status)
        assertContains(out.note, "emptied for the user")
        assertEquals(want.getValue("cleared").jsonArray.map { it.jsonPrimitive.content }, out.cleared)
    }

    @Test
    fun aSixthSheetCallAndASecondEventsCallAreRefusedByTheCodeNotThePrompt(): Unit {
        // the limits are counted per run object: a second use of one SpotterRun cannot send more than five sheets and one events call in all
        val c = case("play_sheet_real_3")
        val info = input(c, "info").jsonObject
        val backend = Routed({ null }) { null }
        val run = SpotterRun(spotter, backend, words, renderer)
        runBlocking {
            run.run("r", info, JsonObject(emptyMap()), input(c, "clip_meta").jsonObject, 0.25, "m")
            val again = run.run("r", info, JsonObject(emptyMap()), input(c, "clip_meta").jsonObject, 0.25, "m")
            assertEquals(6, backend.calls.get())
            assertTrue(again.trace.getValue("sheets").jsonArray.all { it.jsonObject.containsKey("refused") })
            assertContains(again.trace.getValue("events_call").jsonObject.getValue("refused").jsonPrimitive.content, "at most 1 events call")
        }
    }
}
