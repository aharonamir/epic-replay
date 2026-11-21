package app.epicreplay.core.commentator

import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.conformance.ConformanceRunner
import app.epicreplay.core.conformance.TextFunctions
import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.ChatRole
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelError
import app.epicreplay.core.models.ModelResult
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Testing
import app.epicreplay.core.models.ToolCall
import app.epicreplay.core.models.ToolDef
import app.epicreplay.core.models.Usage
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The Commentator's loop over `chat()`: the tools, the turns, the feedback, the fallback, the PC's recordings and the caps. */
class CommentatorRunTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val words = CommentatorWords.load(shared)
    private val checks = Checks.fromShared(shared)
    private val commentator = Commentator(words, checks, File(shared, "contracts/script.schema.json").readText())
    private val runner = ConformanceRunner(shared, TextFunctions.table(shared))

    private fun fixture(name: String) = Json.parseToJsonElement(File(shared, "fixtures/sample_run/$name.json").readText()).jsonObject
    private val ps = fixture("play_sheet"); private val sb = fixture("storyboard"); private val uc = fixture("user_context")
    private fun slots(style: String = "football_commentator") = Json.parseToJsonElement(File(shared, "conformance/slots/${style}_en/expected/output.json").readText()).jsonArray.map { it.jsonObject }
    private fun pack(style: String = "football_commentator") = runner.resolve(buildJsonObject { put("style", style); put("language", "en") }, File(shared, "conformance"))
        .jsonObject

    /** The sample script's lines as a model's draft. */
    private val good: JsonObject = fixture("script").let { sc ->
        buildJsonObject {
            put("lines", JsonArray(sc.getValue("lines").jsonArray.map { l ->
                val o = l.jsonObject
                buildJsonObject {
                    put("id", o.getValue("id")); put("text", o.getValue("text")); put("delivery", o.getValue("delivery")); put("event_refs", o.getValue("event_refs"))
                    o["hit_word"]?.let { put("hit_word", it) }
                }
            }))
            put("headline", sc.getValue("headline")); put("lower_third", sc.getValue("lower_third"))
        }
    }
    private fun withText(d: JsonObject, i: Int, text: String): JsonObject = JsonObject(d + ("lines" to JsonArray(d.getValue("lines").jsonArray.mapIndexed { k, l -> if (k == i) JsonObject(l.jsonObject + ("text" to JsonPrimitive(text))) else l })))

    private class Scripted(override val profile: Profile, val replies: MutableList<() -> ModelResult>) : ModelBackend {
        val chats = mutableListOf<List<ChatMessage>>()
        val toolsSeen = mutableListOf<List<ToolDef>?>()
        val schemas = mutableListOf<JsonObject?>()
        override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String) = error("not used")
        override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult {
            chats += messages.toList(); toolsSeen += tools; schemas += schema
            return replies.removeAt(0)()
        }
    }

    private fun toolTurn(vararg calls: Triple<String, String, JsonObject>, usd: Double = 0.001) = {
        ModelResult("", null, calls.map { (id, name, args) -> ToolCall(id, name, JsonPrimitive(args.toString())) }, Usage(1000, 300), usd, 0.1)
    }
    private fun jsonTurn(draft: JsonObject, usd: Double = 0.001) = { ModelResult(draft.toString(), draft, emptyList(), Usage(1000, 300), usd, 0.1) }
    private fun text(t: String) = { ModelResult(t, null, emptyList(), Usage(10, 10), 0.0, 0.1) }

    private val withTools get() = Testing.profile() // deepseek-flash takes tool calls
    private fun run(b: ModelBackend, mode: CommentatorMode? = null, ledger: Ledger? = null, recorder: Recorder? = null, style: String = "football_commentator") =
        runBlocking {
            val r = CommentatorRun(commentator, b, words, "deepseek-flash", ledger, recorder, mode = mode ?: if (b.profile.supportsTools) CommentatorMode.TOOLS else CommentatorMode.FEEDBACK)
            r.write(slots(style), ps, uc, sb, pack(style))
        }

    @Test
    fun theAgentChecksThenSubmitsAndTheFirstAcceptedSubmitIsTheScript(): Unit {
        val b = Scripted(withTools, mutableListOf(
            toolTurn(Triple("c1", "check_lines", withText(good, 0, "Ronaldo steps up."))),
            toolTurn(Triple("c2", "check_lines", good), Triple("c3", "submit_script", good)),
        ))
        val r = run(b)
        assertEquals("ok", r.outcome.status); assertEquals(2, r.turns); assertEquals(CommentatorMode.TOOLS, r.mode)
        assertEquals(listOf("check_lines", "check_lines", "submit_script"), r.calls.map { it.getValue("tool").jsonPrimitive.content })
        assertEquals(listOf(1, 0, 0), r.calls.map { it.getValue("failures").jsonPrimitive.content.toInt() }.map { if (it > 0) 1 else 0 })
        // what the model saw: the system prompt, the brief, then its own tool calls answered by id
        val second = b.chats[1]
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL), second.map { it.role })
        assertEquals("c1", second[3].toolCallId); assertEquals("c1", second[2].toolCalls.single().id)
        assertContains(second[3].content, "'Ronaldo' is not in the play sheet")
        assertEquals(setOf("check_lines", "submit_script"), b.toolsSeen[0]!!.map { it.name }.toSet())
        assertEquals(0.002, r.usd, 1e-9)
    }

    @Test
    fun aRejectedSubmitIsAnsweredWithItsFailuresAndTheAgentTriesAgain(): Unit {
        val b = Scripted(withTools, mutableListOf(
            toolTurn(Triple("s1", "submit_script", withText(good, 0, "Ronaldo steps up."))),
            toolTurn(Triple("s2", "submit_script", good)),
        ))
        val r = run(b)
        assertEquals("ok", r.outcome.status)
        assertContains(b.chats[1].last().content, "\"rejected\":true")
    }

    @Test
    fun anAgentThatNeverSubmitsStopsAtTheLimitAndThenOneStructuredDraftIsAsked(): Unit {
        val never = (1..words.maxIterations).map { toolTurn(Triple("c$it", "check_lines", withText(good, 0, "Ronaldo steps up."))) }
        val b = Scripted(withTools, (never + listOf(jsonTurn(good))).toMutableList())
        val r = run(b)
        assertEquals(words.maxIterations + 1, b.chats.size)
        assertEquals(words.maxIterations, r.turns)
        // the structured call is a plain JSON call with the schema and no tools
        assertEquals(null, b.toolsSeen.last()); assertEquals(words.draftSchema, b.schemas.last())
        assertContains(b.chats.last()[0].content, words.structuredDraftIntro)
        assertEquals("degraded", r.outcome.status) // it was not the agent's script
        assertContains(r.outcome.note, "the agent submitted no script that passes; generic lines for none")
        assertEquals("structured_draft", r.calls.last().getValue("tool").jsonPrimitive.content)
    }

    @Test
    fun aPlainTextAnswerEndsTheAgentAndAFailedStructuredCallLeavesGenericLines(): Unit {
        val b = Scripted(withTools, mutableListOf(text("I am done."), { throw ModelError.Timeout("deepseek-flash", "timed out") }))
        val r = run(b)
        assertEquals(1, r.turns)
        assertEquals("degraded", r.outcome.status)
        assertContains(r.outcome.note, "the structured draft failed (Timeout)")
        assertEquals(7, r.outcome.script.getValue("lines").jsonArray.size)
        assertTrue(r.outcome.script.getValue("lines").jsonArray.all { it.jsonObject.getValue("generic").jsonPrimitive.content == "true" })
    }

    @Test
    fun aProfileWithoutToolsGetsAJsonDraftAndItsFailuresBackForABoundedNumberOfTurns(): Unit {
        val noTools = object { val profile: Profile = Testing.profile() } // the mode is forced: no shipped profile that can be run here lacks tools
        val bad = withText(good, 0, "Ronaldo steps up.")
        val b = Scripted(noTools.profile, mutableListOf(jsonTurn(bad), jsonTurn(good)))
        val r = run(b, CommentatorMode.FEEDBACK)
        assertEquals("ok", r.outcome.status); assertEquals(2, r.turns); assertEquals(CommentatorMode.FEEDBACK, r.mode)
        val second = b.chats[1]
        assertEquals(ChatRole.USER, second.last().role); assertContains(second.last().content, "'Ronaldo' is not in the play sheet")
        assertTrue(b.toolsSeen.all { it == null }); assertTrue(b.schemas.all { it == words.draftSchema })
        // never good: after three turns the best draft is mended
        val b2 = Scripted(noTools.profile, mutableListOf(jsonTurn(bad), jsonTurn(withText(bad, 1, "Rooney...")), jsonTurn(bad)))
        val r2 = run(b2, CommentatorMode.FEEDBACK)
        assertEquals(3, r2.turns); assertEquals("degraded", r2.outcome.status); assertContains(r2.outcome.note, "no draft passed in 3 turn(s)")
        assertEquals(3, b2.chats.size)
        // a draft that is not JSON counts as an empty draft and the next turn is asked
        val b3 = Scripted(noTools.profile, mutableListOf(text("not json"), jsonTurn(good)))
        assertEquals("ok", run(b3, CommentatorMode.FEEDBACK).outcome.status)
    }

    @Test
    fun theStructuredModeAsksOneDraftAndMendsIt(): Unit {
        val b = Scripted(withTools, mutableListOf(jsonTurn(withText(good, 0, "Ronaldo steps up."))))
        val r = run(b, CommentatorMode.STRUCTURED)
        assertEquals(1, b.chats.size); assertEquals("degraded", r.outcome.status)
        assertContains(r.outcome.note, "generic lines for l1")
        assertEquals(1, r.outcome.draft.getValue("lines").jsonArray.count { it.jsonObject.getValue("id").jsonPrimitive.content == "l1" })
    }

    @Test
    fun thePcsRecordedRunsAreReplayedUnderTheSameKeysWithNoCallAndNoCost(): Unit {
        val pcRecordings = File(shared.parentFile, "pc/tests/fixtures/recordings")
        for (style in listOf("news_reporter", "nature_documentary")) {
            val b = Scripted(withTools, mutableListOf())
            val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.0001, dayUsd = 20.0, preCheckpointUsd = 0.3), today = { "2026-11-20" }, now = { "t" })
            val r = run(b, CommentatorMode.TOOLS, ledger, Recorder(ProviderMode.REPLAY, pcRecordings), style)
            assertTrue(r.replayed, "$style: the PC's recording was not found: the prompt or the brief differs from the PC's")
            assertEquals(0, b.chats.size); assertEquals("ok", r.outcome.status)
            assertEquals(0.0, ledger.runTotal("run"), 0.0) // a recorded run costs nothing and a cap it could not reach does not stop it
            // and it is the script the cases give for the same recorded draft
            val want = runner.resolve(runner.load(File(shared, "conformance/commentator/script_recorded_run_$style")).getValue("expected").jsonObject, File(shared, "conformance/commentator/script_recorded_run_$style"))
            assertEquals(emptyList(), ConformanceRunner.compare(r.outcome.script, want.jsonObject.getValue("script"), 0.001), style)
        }
    }

    @Test
    fun theCapsStopTheAgentBeforeItsFirstCall(): Unit {
        val b = Scripted(withTools, mutableListOf())
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.001, dayUsd = 20.0, preCheckpointUsd = 0.3), today = { "2026-11-20" }, now = { "t" })
        assertFailsWith<CapExceeded> { run(b, CommentatorMode.TOOLS, ledger) }
        assertEquals(0, b.chats.size)
        assertEquals(1, ledger.blocked("run").size)
    }

    @Test
    fun aRunIsRecordedAsThePcRecordsItAndReadBackWithoutACall(): Unit {
        val dir = java.nio.file.Files.createTempDirectory("rec").toFile()
        val b = Scripted(withTools, mutableListOf(toolTurn(Triple("s1", "submit_script", good))))
        val first = run(b, CommentatorMode.TOOLS, null, Recorder(ProviderMode.RECORD, dir))
        assertEquals("ok", first.outcome.status)
        val file = File(dir, "commentator").listFiles()!!.single()
        val payload = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(setOf("draft", "calls", "usage", "usage_usd", "turns"), payload.keys) // the PC reads `draft`, `calls` and (when it pays) `usage`
        assertEquals(setOf("input_tokens", "output_tokens"), payload.getValue("usage").jsonObject.keys)
        val again = run(Scripted(withTools, mutableListOf()), CommentatorMode.TOOLS, null, Recorder(ProviderMode.REPLAY, dir))
        assertTrue(again.replayed); assertEquals(first.outcome.script, again.outcome.script)
        assertNotNull(PythonJson.dumps(JsonPrimitive("x")))
    }

    @Test
    fun aToolThisLoopDoesNotHaveAcceptsNothingAndAFailedAgentGoesOnToTheStructuredDraftAtItsEstimate(): Unit {
        val b = Scripted(withTools, mutableListOf(
            toolTurn(Triple("x1", "make_it_pass", good)), // an unknown tool with a good draft
            { throw ModelError.Timeout("deepseek-flash", "timed out") }, // then the agent times out
            jsonTurn(good),
        ))
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(), today = { "2026-11-20" }, now = { "t" })
        val r = runBlocking { CommentatorRun(commentator, b, words, "deepseek-flash", ledger, null, mode = CommentatorMode.TOOLS).write(slots(), ps, uc, sb, pack()) }
        assertEquals("degraded", r.outcome.status)
        assertContains(r.outcome.note, "the agent failed (Timeout)")
        assertEquals(3, b.chats.size) // two agent turns, then the structured call
        assertContains(b.chats[1].last().content, "unknown tool make_it_pass")
        assertTrue(ledger.runTotal("run") >= 0.0198 - 1e-6, "the agent counts at its estimate: ${ledger.runTotal("run")}") // six turns of 5000 in and 1500 out at the profile's prices
    }

    @Test
    fun aTimeoutInTheFeedbackLoopIsSettledAndTheBestDraftSoFarIsMended(): Unit {
        val b = Scripted(withTools, mutableListOf(jsonTurn(withText(good, 0, "Ronaldo steps up.")), { throw ModelError.Timeout("deepseek-flash", "timed out") }))
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(), today = { "2026-11-20" }, now = { "t" })
        val r = runBlocking { CommentatorRun(commentator, b, words, "deepseek-flash", ledger, null, mode = CommentatorMode.FEEDBACK).write(slots(), ps, uc, sb, pack()) }
        assertEquals("degraded", r.outcome.status)
        assertContains(r.outcome.note, "the draft call failed (Timeout) after 1 turn(s)")
        assertContains(r.outcome.note, "generic lines for l1")
        assertTrue(ledger.runTotal("run") > 0.0, "a timeout may have been billed")
    }
}
