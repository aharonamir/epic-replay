package app.epicreplay.core.record

import app.epicreplay.core.models.FirstJson
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class RecorderTest {
    private lateinit var dir: File
    @BeforeTest fun setUp() { dir = Files.createTempDirectory("rec").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val payload: JsonObject = buildJsonObject { put("text", JsonPrimitive("好球")); put("usage_usd", JsonPrimitive(0.0035)) }
    private fun key(q: String) = cacheKey("vision", "deepseek-flash", buildJsonObject { put("question", JsonPrimitive(q)) }, listOf("ab12"))

    @Test
    fun theKeyChangesWithEachPartOfTheRequest() {
        val base = key("a")
        assertEquals(base, key("a"))
        assertTrue(base != key("b"))
        assertTrue(base != cacheKey("text", "deepseek-flash", buildJsonObject { put("question", JsonPrimitive("a")) }, listOf("ab12")))
        assertTrue(base != cacheKey("vision", "other", buildJsonObject { put("question", JsonPrimitive("a")) }, listOf("ab12")))
        assertTrue(base != cacheKey("vision", "deepseek-flash", buildJsonObject { put("question", JsonPrimitive("a")) }, listOf("cd34")))
    }

    @Test
    fun recordStoresTheAnswerOnceAndReplayAnswersFromIt() = runBlocking {
        var calls = 0
        val rec = Recorder(ProviderMode.RECORD, dir)
        val (first, hit1) = rec.fetch("vision", key("a")) { calls++; payload }
        val (second, hit2) = rec.fetch("vision", key("a")) { calls++; payload }
        assertEquals(1, calls); assertFalse(hit1); assertTrue(hit2); assertEquals(first, second)
        val replay = Recorder(ProviderMode.REPLAY, dir)
        assertEquals(payload, replay.fetch("vision", key("a")) { error("no call in replay") }.first)
        assertTrue(File(dir, "vision/${key("a")}.json").readText().startsWith("{\n  \"text\"")) // two-space indent, as the PC writes it
    }

    @Test
    fun replayNeverCallsAndNamesTheMissingRecording() {
        val e = assertFailsWith<ReplayMiss> { runBlocking { Recorder(ProviderMode.REPLAY, dir).fetch("vision", key("zzz")) { payload } } }
        assertTrue(key("zzz").take(12) in e.message!!)
    }

    @Test
    fun liveIgnoresRecordingsAndStoresNothing() = runBlocking {
        Recorder(ProviderMode.RECORD, dir).fetch("vision", key("a")) { payload }
        val live = Recorder(ProviderMode.LIVE, dir)
        assertFalse(live.has("vision", key("a")))
        var calls = 0
        live.fetch("vision", key("a")) { calls++; payload }
        assertEquals(1, calls)
        live.fetch("vision", key("new")) { payload }
        assertFalse(File(dir, "vision/${key("new")}.json").exists())
    }

    @Test
    fun aRecordingWrittenByThePcIsRead() = runBlocking {
        val k = key("from the pc")
        File(dir, "vision").mkdirs()
        File(dir, "vision/$k.json").writeText("{\n  \"text\": \"x\",\n  \"data\": null,\n  \"usage\": {\"prompt_tokens\": 1},\n  \"usage_usd\": 0.0\n}")
        val (p, hit) = Recorder(ProviderMode.REPLAY, dir).fetch("vision", k) { error("no") }
        assertTrue(hit); assertEquals("x", (p["text"] as JsonPrimitive).content)
    }

    @Test
    fun firstJsonTakesTheFirstObjectAndStripsAFence() {
        assertEquals("""{"a":1}""", FirstJson.of("""{"a": 1} {"b": 2}""").toString())
        assertEquals("""{"a":[1,2]}""", FirstJson.of("```json\n{\"a\": [1, 2]}\n```").toString())
        assertEquals("""{"s":"} not the end"}""", FirstJson.of("""{"s": "} not the end"} tail""").toString())
        assertNull(FirstJson.of("Sure! Here is the JSON: {\"a\": 1}")) // prose first: not JSON, as raw_decode refuses
        assertNull(FirstJson.of("")); assertNull(FirstJson.of("{\"a\": "))
        assertEquals("3", FirstJson.of("3 apples").toString())
    }
}
