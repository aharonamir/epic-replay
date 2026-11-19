package app.epicreplay.core.models

import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpResponse
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.net.RedactingTransport
import app.epicreplay.core.secrets.Secret
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CloudBackendTest {
    private val sentinel = "sk-TESTKEY-0123456789abcdefXYZ"
    private val deepseek = Testing.profile()
    private val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
    private val jpeg = byteArrayOf(1, 2, 3, 4)

    private fun backend(
        profile: ServerProfile = deepseek, role: Role = Role.VISION, transport: HttpTransport = CapturingTransport(),
        key: Secret? = Secret(sentinel), resizer: ImageResizer? = null, gate: Semaphore = Semaphore(profile.maxParallel),
    ) = CloudBackend(profile, role, transport, { key }, resizer, gate)

    private fun body(t: CapturingTransport, n: Int = 0): JsonObject = Json.parseToJsonElement(String(t.requests[n].body!!)).jsonObject

    @Test
    fun theUrlAndTheKeyHeaderComeFromTheProfile() = runBlocking {
        val t = CapturingTransport()
        backend(transport = t).look(listOf(LabeledImage(null, jpeg)), "Q?")
        assertEquals("https://api.deepseek.com/chat/completions", t.requests[0].url)
        assertEquals("Bearer $sentinel", t.requests[0].headers["Authorization"])
        assertEquals("application/json", t.requests[0].headers["Content-Type"])
        // a trailing slash in api_base never doubles
        val t2 = CapturingTransport()
        backend(deepseek.copy(apiBase = "https://example.org/v1///"), transport = t2).chat(listOf(ChatMessage(ChatRole.USER, "x")))
        assertEquals("https://example.org/v1/chat/completions", t2.requests[0].url)
    }

    @Test
    fun aProfileWithoutAKeyVariableSendsNoAuthorizationEvenIfAKeyIsAround() = runBlocking {
        val t = CapturingTransport()
        backend(deepseek.copy(apiKeyEnv = null), transport = t).chat(listOf(ChatMessage(ChatRole.USER, "x")))
        assertFalse("Authorization" in t.requests[0].headers)
    }

    @Test
    fun aMissingKeyIsNamedByProfileNeverByValue() {
        val e = assertFailsWith<ModelError.KeyMissing> { runBlocking { backend(key = null).chat(listOf(ChatMessage(ChatRole.USER, "x"))) } }
        assertTrue("deepseek-flash" in e.message!!)
    }

    @Test
    fun theTimeoutIsTheProfilesForTheRoleAndTheSameNumberAsThePcRecords() = runBlocking {
        val v = CapturingTransport(); val x = CapturingTransport()
        backend(transport = v).look(listOf(LabeledImage(null, jpeg)), "q")
        backend(role = Role.TEXT, transport = x).chat(listOf(ChatMessage(ChatRole.USER, "x")))
        assertEquals("45.0", v.requests[0].timeoutS.content) // look: a float
        assertEquals("60", x.requests[0].timeoutS.content) // chat: a whole number as an int
    }

    @Test
    fun theJsonModesChooseTheResponseFormat() = runBlocking {
        fun fmt(mode: String): JsonObject? {
            val t = CapturingTransport()
            runBlocking { backend(deepseek.copy(jsonMode = mode), role = Role.TEXT, transport = t).chat(listOf(ChatMessage(ChatRole.USER, "x")), schema) }
            return body(t)["response_format"] as? JsonObject
        }
        assertEquals("""{"type":"json_object"}""", fmt("json_object").toString())
        assertEquals("""{"type":"json_schema","json_schema":{"name":"answer","schema":{"type":"object"}}}""", fmt("json_schema").toString())
        assertNull(fmt("prompt"))
        val t = CapturingTransport()
        backend(role = Role.TEXT, transport = t).chat(listOf(ChatMessage(ChatRole.USER, "x"))) // no schema: no format
        assertNull(body(t)["response_format"])
        Unit
    }

    @Test
    fun theImageDetailFollowsTheProfile() = runBlocking {
        fun detailSent(profileDetail: String?, asked: String): String? {
            val t = CapturingTransport()
            runBlocking { backend(deepseek.copy(image = ImageSpec(null, profileDetail)), transport = t).look(listOf(LabeledImage("Grid 1", jpeg)), "q", null, asked) }
            val content = body(t).getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
            assertEquals("Grid 1", content[0].jsonObject.getValue("text").jsonPrimitive.content) // the label comes first as text
            return content[1].jsonObject.getValue("image_url").jsonObject["detail"]?.jsonPrimitive?.content
        }
        assertEquals("low", detailSent("request", "low"))
        assertEquals("high", detailSent("high", "low")) // the profile's own value wins
        assertNull(detailSent(null, "low")) // null: no detail field at all
        Unit
    }

    @Test
    fun anImageIsResizedOnlyWhenTheProfileAsksAndAResizerExists() = runBlocking {
        val sized = deepseek.copy(image = ImageSpec(64, "request"))
        assertFailsWith<IllegalStateException> { backend(sized).look(listOf(LabeledImage(null, jpeg)), "q") }
        val t = CapturingTransport()
        val seen = mutableListOf<Int>()
        backend(sized, transport = t, resizer = object : ImageResizer {
            override fun resize(jpeg: ByteArray, maxSidePx: Int): ByteArray { seen += maxSidePx; return byteArrayOf(9) }
        }).look(listOf(LabeledImage(null, jpeg)), "q")
        assertEquals(listOf(64), seen)
        assertTrue("data:image/jpeg;base64,CQ==" in String(t.requests[0].body!!)) // base64 of the one byte 9
    }

    @Test
    fun theProfilesExtraBodyIsMergedAtTheTopLevel() = runBlocking {
        val t = CapturingTransport()
        val extra = buildJsonObject { put("reasoning_effort", JsonPrimitive("none")); put("model", JsonPrimitive("override")) }
        backend(deepseek.copy(extraBody = extra), role = Role.TEXT, transport = t).chat(listOf(ChatMessage(ChatRole.USER, "x")))
        assertEquals("none", body(t).getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("override", body(t).getValue("model").jsonPrimitive.content) // body.update(extra_body): the profile's field wins
    }

    @Test
    fun toolsAreSentOnlyForAProfileThatSupportsThem() = runBlocking {
        val tool = ToolDef("check_lines", "Check lines", buildJsonObject { put("type", JsonPrimitive("object")) })
        val t = CapturingTransport()
        backend(role = Role.TEXT, transport = t).chat(listOf(ChatMessage(ChatRole.USER, "x")), null, listOf(tool))
        assertEquals("check_lines", body(t).getValue("tools").jsonArray[0].jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { backend(deepseek.copy(supportsTools = false), role = Role.TEXT).chat(emptyList(), null, listOf(tool)) }
        Unit
    }

    @Test
    fun theReplyGivesTextDataUsageCostAndToolCalls() = runBlocking {
        val calls = """[{"id":"call_1","type":"function","function":{"name":"check_lines","arguments":"{\"lines\": []}"}}]"""
        val t = CapturingTransport({ Testing.reply("```json\n{\"cell\": 6} trailing\n```", 1000, 500, calls) })
        val r = backend(role = Role.TEXT, transport = t).chat(listOf(ChatMessage(ChatRole.USER, "x")), schema)
        assertEquals(6, r.data!!.getValue("cell").jsonPrimitive.content.toInt())
        assertEquals(1000L to 500L, r.usage.inputTokens to r.usage.outputTokens)
        assertEquals(0.0009, r.usd)
        assertEquals("call_1" to "check_lines", r.toolCalls[0].id to r.toolCalls[0].name)
        assertEquals("""{"lines": []}""", (r.toolCalls[0].arguments as JsonPrimitive).content) // the JSON text the server sent
    }

    @Test
    fun aReplyWithoutUsageCountsZero() = runBlocking {
        val t = CapturingTransport({ """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}""" })
        val r = backend(transport = t).look(listOf(LabeledImage(null, jpeg)), "q")
        assertEquals(0L, r.usage.inputTokens); assertEquals(0.0, r.usd); assertEquals("hi", r.text); assertNull(r.data)
    }

    @Test
    fun anErrorStatusIsReportedWithoutTheBody() {
        val t = CapturingTransport({ """{"error":"bad key $sentinel"}""" }, status = 401)
        val e = assertFailsWith<ModelError.Server> { runBlocking { backend(transport = t).look(listOf(LabeledImage(null, jpeg)), "q") } }
        assertEquals(401, e.status)
        assertFalse(sentinel in e.message!!)
    }

    @Test
    fun aReplyThatIsNotAChatCompletionIsAServerError() {
        val e = assertFailsWith<ModelError.Server> { runBlocking { backend(transport = CapturingTransport({ "not json at all" })).look(emptyList(), "q") } }
        assertTrue("did not answer with JSON" in e.message!!)
        assertFailsWith<ModelError.Server> { runBlocking { backend(transport = CapturingTransport({ """{"choices":[]}""" })).look(emptyList(), "q") } }
    }

    @Test
    fun noMoreThanMaxParallelCallsAreInFlight() = runBlocking {
        val inFlight = AtomicInteger(); var peak = 0
        val slow = object : HttpTransport {
            override suspend fun send(request: HttpRequest, profileId: String): HttpResponse {
                peak = maxOf(peak, inFlight.incrementAndGet()); delay(30); inFlight.decrementAndGet()
                return HttpResponse(200, Testing.REPLY.toByteArray())
            }
        }
        val b = backend(deepseek.copy(maxParallel = 2), transport = slow, gate = Semaphore(2))
        (1..8).map { async { b.look(listOf(LabeledImage(null, jpeg)), "q") } }.awaitAll()
        assertEquals(2, peak)
    }

    @Test
    fun aKeyNeverSurvivesAFailureAtTheTransportBoundary() {
        val failing = object : HttpTransport {
            override suspend fun send(request: HttpRequest, profileId: String): HttpResponse =
                throw IOException("connect failed for https://x?auth=$sentinel (header Authorization: Bearer $sentinel)")
        }
        val wrapped = RedactingTransport(failing) { listOf(Secret(sentinel)) }
        val e = assertFailsWith<ModelError> { runBlocking { backend(transport = wrapped).look(listOf(LabeledImage(null, jpeg)), "q") } }
        assertFalse(sentinel in e.message!!)
        assertNull(e.cause) // no cause that could carry the original message
        val timeout = object : HttpTransport {
            override suspend fun send(request: HttpRequest, profileId: String): HttpResponse = throw ModelError.Timeout(profileId, "took too long: $sentinel")
        }
        val t = assertFailsWith<ModelError.Timeout> { runBlocking { backend(transport = RedactingTransport(timeout) { listOf(Secret(sentinel)) }).look(emptyList(), "q") } }
        assertFalse(sentinel in t.message!!)
        // nothing printable about the request or the backend holds the key
        val req = HttpRequest("POST", "https://x", mapOf("Authorization" to "Bearer $sentinel"), null, JsonPrimitive(1))
        assertFalse(sentinel in req.toString())
        assertFalse(sentinel in backend().toString())
    }

    @Test
    fun aBackendRefusesARoleTheProfileDoesNotList() {
        val textOnly = deepseek.copy(roles = listOf(Role.TEXT))
        assertFailsWith<IllegalArgumentException> { backend(textOnly, role = Role.VISION) }
        assertNotNull(backend(textOnly, role = Role.TEXT))
    }
}
