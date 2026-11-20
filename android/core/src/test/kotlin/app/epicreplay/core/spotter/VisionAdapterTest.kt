package app.epicreplay.core.spotter

import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelError
import app.epicreplay.core.models.ModelResult
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Testing
import app.epicreplay.core.models.ToolDef
import app.epicreplay.core.models.Usage
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The retry, the recordings and the caps around a vision call (`providers/vision.py` `_ask`). */
class VisionAdapterTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val words = SpotterWords.load(shared)
    private val good = Json.parseToJsonElement("""{"activity":"a kick","outcome":"it flies","actor":"a player","high_point_cell":4,"sure":7}""").jsonObject
    private val bad = Json.parseToJsonElement("""{"activity":"a kick"}""").jsonObject

    private class Scripted(val replies: MutableList<JsonObject?>, val usd: Double = 0.0035) : ModelBackend {
        override val profile: Profile = Testing.profile()
        val questions = mutableListOf<String>()
        override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult {
            questions += question
            val d = replies.removeAt(0)
            return ModelResult(d?.toString() ?: "no json", d, emptyList(), Usage(1000, 500), usd, 0.2)
        }
        override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?) = error("not used")
    }

    private val image = listOf(LabeledImage(null, "jpeg-bytes".toByteArray()))
    private val request = buildJsonObject {
        put("detail", "high"); put("schema", words.overviewSchema)
        put("sheet_cells", JsonArray(listOf(0, 20, 40).mapIndexed { k, f -> buildJsonObject { put("cell", k + 1); put("frame", f) } }))
    }
    private val q = words.overviewQuestion("These frames cover about three seconds around a moment of sudden motion or sound.")
    private fun adapter(b: ModelBackend, ledger: Ledger? = null, recorder: Recorder? = null, estimate: Double = 0.008) = VisionAdapter(b, "deepseek-flash", words.retryLine, estimate, ledger, recorder)
    private fun ask(a: VisionAdapter) = runBlocking { a.ask(request, image, q, words.overviewSchema, "high") }

    @Test
    fun aReplyThatFitsIsOneCallAndOneThatDoesNotIsAskedOnceMoreWithTheRetryLine(): Unit {
        val one = Scripted(mutableListOf(good))
        assertEquals(1, ask(adapter(one)).attempts); assertEquals(listOf(q), one.questions)
        val two = Scripted(mutableListOf(bad, good))
        val r = ask(adapter(two))
        assertEquals(2, r.attempts); assertEquals(listOf(q, q + "\n" + words.retryLine), two.questions); assertEquals(0.007, r.usd, 1e-9)
        val none = Scripted(mutableListOf(null, bad))
        val e = assertFailsWith<ModelError.SchemaMisfit> { ask(adapter(none)) }
        assertContains(e.message!!, "2 replies did not fit the schema")
        assertEquals(2, none.questions.size) // never a third
    }

    @Test
    fun theRecordingKeysAreTheOnesThePythonAdapterMakes(): Unit {
        // keys made by the PC's `cache_key` for the same adapter, model, request, question and file hash (checked 2026-11-20)
        val dir = Files.createTempDirectory("rec").toFile()
        val rec = Recorder(ProviderMode.RECORD, dir)
        ask(adapter(Scripted(mutableListOf(bad, good)), recorder = rec))
        val files = File(dir, "vision").list()!!.map { it.removeSuffix(".json") }.toSet()
        assertEquals(setOf("e206d7d2ac440ae2cfd16f56d6532312c944b3a76f2c29f56186b5c69455dcb2".let { _ -> keyFor(q) }, keyFor(q + "\n" + words.retryLine)), files)
        assertEquals("e206d7d2ac440ae2cfd16f56d6532312c944b3a76f2c29f56186b5c69455dcb2", keyFor(q))
        assertEquals("aca37e3553e0135b03a8d4c8952e5eb476b3f065b339d02dd631a2d0e151c323", keyFor(q + "\n" + words.retryLine))
    }

    private fun keyFor(question: String): String = app.epicreplay.core.record.cacheKey(
        "vision", "deepseek-flash", JsonObject(request + ("question" to JsonPrimitive(question))),
        listOf("0111dbc398b94eacda6759809c050530868ee7e313b3381c2f95ce8b55331c50"),
    )

    @Test
    fun aRecordedReplyCostsNothingAndIsNotSentAgain(): Unit {
        val dir = Files.createTempDirectory("rec").toFile()
        val live = Scripted(mutableListOf(good))
        val first = ask(adapter(live, recorder = Recorder(ProviderMode.RECORD, dir)))
        assertEquals(0.0035, first.usd, 1e-9)
        val replay = Scripted(mutableListOf())
        val again = ask(adapter(replay, recorder = Recorder(ProviderMode.REPLAY, dir)))
        assertTrue(again.cacheHit); assertEquals(0.0, again.usd); assertEquals(emptyList(), replay.questions)
        assertEquals(good, again.data)
    }

    @Test
    fun theCapsStopACallAtTheLimitAndARecordingReservesNothing(): Unit {
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.02, dayUsd = 20.0, preCheckpointUsd = 0.30), today = { "2026-11-20" }, now = { "t" })
        val backend = Scripted(mutableListOf(good, good, good, good), usd = 0.008)
        val a = adapter(backend, ledger = ledger, estimate = 0.008)
        ask(a); ask(a) // 0.016 spent, 0.004 left
        assertFailsWith<CapExceeded> { ask(a) } // 0.016 + an estimate of 0.008 is over 0.02
        assertEquals(2, backend.questions.size) // the third call was never made
        assertEquals(0.016, ledger.runTotal("run"), 1e-9)
        // a call blocked by a cap is logged
        assertEquals(1, ledger.blocked("run").size)
    }

    @Test
    fun aTimeoutIsBilledAtItsEstimateAndAReplyThatMisfitsIsStillPaidFor(): Unit {
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(), today = { "2026-11-20" }, now = { "t" })
        val timing = object : ModelBackend {
            override val profile: Profile = Testing.profile()
            override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult = throw ModelError.Timeout("deepseek-flash", "timed out")
            override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?) = error("not used")
        }
        assertFailsWith<ModelError.Timeout> { ask(adapter(timing, ledger = ledger, estimate = 0.008)) }
        assertEquals(0.008, ledger.runTotal("run"), 1e-9)
        val misfitting = Scripted(mutableListOf(bad, good), usd = 0.0035)
        ask(adapter(misfitting, ledger = ledger))
        assertEquals(0.008 + 0.007, ledger.runTotal("run"), 1e-9) // both tries cost, the retry too
    }
}
