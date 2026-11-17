package app.epicreplay.core.models

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class FakeBackendTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val profile = ProfileLoader.loadDir(File(shared, "models"), File(shared, "contracts/model_profile.schema.json"), Platform.PC)
        .profiles.getValue("deepseek-flash")

    @Test
    fun itAnswersInOrderAndRecordsTheCalls() = runBlocking {
        val b = FakeBackend(profile)
            .reply("""{"cell": 6}""", data = buildJsonObject { put("cell", JsonPrimitive(6)) }, usage = Usage(1000, 500))
            .reply("second")
        val a = b.look(listOf(LabeledImage("Grid 1", byteArrayOf(1, 2))), "Which cell?", buildJsonObject { put("type", JsonPrimitive("object")) })
        assertEquals(6, a.data!!.getValue("cell").let { (it as JsonPrimitive).content.toInt() })
        assertEquals(0.0009, a.usd) // 1000 and 500 tokens at the default profile's prices
        assertEquals("second", b.chat(listOf(ChatMessage(ChatRole.USER, "hi"))).text)
        assertEquals(2, b.calls.size)
        assertIs<FakeBackend.Call.Look>(b.calls[0]); assertIs<FakeBackend.Call.Chat>(b.calls[1])
        assertEquals("Grid 1", (b.calls[0] as FakeBackend.Call.Look).images[0].label)
    }

    @Test
    fun itCanFailWithEveryErrorOfTheContract() = runBlocking {
        val errors = listOf(
            ModelError.Unreachable("p", "not reachable"), ModelError.Timeout("p", "too slow"), ModelError.SchemaMisfit("p", "no fit"),
            ModelError.NotInstalled("p", listOf("w.gguf")), ModelError.OutOfMemory("p", 5000, 3000),
        )
        for (e in errors) {
            val b = FakeBackend(profile).fail(e)
            val thrown = assertFailsWith<ModelError> { b.chat(emptyList()) }
            assertEquals(e.message, thrown.message)
        }
        assertTrue("5000" in errors.last().message!! && "3000" in errors.last().message!!)
        assertTrue("w.gguf" in errors[3].message!!)
    }

    @Test
    fun aCallWithNothingQueuedFailsTheTest() {
        val b = FakeBackend(profile)
        assertFailsWith<IllegalStateException> { runBlocking { b.chat(emptyList()) } }
    }

    @Test
    fun toolsNeedAProfileThatSupportsThem() {
        val noTools = (profile as ServerProfile).copy(id = "no-tools", supportsTools = false)
        val tool = ToolDef("check_lines", "Check lines", JsonObject(emptyMap()))
        assertFailsWith<IllegalArgumentException> { runBlocking { FakeBackend(noTools).reply("x").chat(emptyList(), tools = listOf(tool)) } }
        runBlocking { FakeBackend(profile).reply("x").chat(emptyList(), tools = listOf(tool)) }
    }
}
