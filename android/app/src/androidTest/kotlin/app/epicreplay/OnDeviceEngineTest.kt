package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.ChatRole
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelError
import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceBackend
import app.epicreplay.core.models.OnDeviceConfig
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T409 on the phone: the JNI bridge, the real engine and its files, in the app process (spike S1 ran native
 * binaries). The models are pushed by `tools/engine_device_test.sh` into `files/models/`. Timings and memory
 * go to `files/t409_metrics.txt` (never the prompts or replies).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceEngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = AppGraph(ctx)
    private val metrics = File(ctx.filesDir, "t409_metrics.txt")
    private val opened = mutableListOf<OnDeviceBackend>()

    private fun profile(id: String, weights: String, projector: String, ram: Int = 1) = OnDeviceProfile(
        id = id, label = id, roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "",
        engine = "llama.cpp",
        files = listOf(ModelFile(weights, "https://example.org/$weights", "0".repeat(64), 1, "weights"), ModelFile(projector, "https://example.org/$projector", "0".repeat(64), 1, "projector")),
        contextTokens = 4096, minFreeRamMb = ram, minFreeStorageMb = 1, license = "test",
    )

    private val gemma = profile("gemma-4-e2b-device", "gemma-4-E2B-it-Q4_0.gguf", "mmproj-gemma-4-E2B-it-Q8_0.gguf")
    private val qwen = profile("qwen3-5-4b-device", "qwen35-Qwen3.5-4B-Q4_K_M.gguf", "qwen35-mmproj-F16.gguf")

    private fun backend(p: OnDeviceProfile, config: OnDeviceConfig = OnDeviceConfig()) =
        graph.onDeviceBackend(p, Role.VISION, config).also { opened += it }

    @After fun closeAll() { opened.forEach { it.close() }; opened.clear() }

    private fun installed(p: OnDeviceProfile) = p.files.all { File(graph.modelDir, it.name).isFile }

    private fun peakKb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.split(Regex("\\s+"))[1].toLong()
    private fun note(line: String) = metrics.appendText(line + "\n")

    // the test APK's own assets, not the app's
    private fun sheet() = InstrumentationRegistry.getInstrumentation().context.assets.open("sheet.jpg").readBytes()
    private val cellSchema: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object")); put("required", JsonArray(listOf(JsonPrimitive("cell"))))
        put("properties", buildJsonObject { put("cell", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(1)); put("maximum", JsonPrimitive(9)) }) })
    }
    private val question = "The image is a 3x3 grid of nine numbered cells. Which cell shows the red square?"

    @Test
    fun aMissingModelIsNotInstalledAndTooLittleMemoryIsRefusedBeforeLoading() = runBlocking {
        val absent = profile("absent", "nope.gguf", "nope-mmproj.gguf")
        val e = runCatching { backend(absent).chat(listOf(ChatMessage(ChatRole.USER, "hi"))) }.exceptionOrNull()
        assertTrue("expected NotInstalled, got $e", e is ModelError.NotInstalled)
        assumeTrue(installed(gemma))
        val before = peakKb()
        val huge = backend(profile("huge", gemma.files[0].name, gemma.files[1].name, ram = 1_000_000))
        val oom = runCatching { huge.chat(listOf(ChatMessage(ChatRole.USER, "hi"))) }.exceptionOrNull()
        assertTrue("expected OutOfMemory, got $oom", oom is ModelError.OutOfMemory)
        assertTrue("the model must not have been loaded", peakKb() - before < 200_000) // nothing near a 2.8 GB model
    }

    @Test
    fun gemmaLooksAtTheSheetThroughJniAndAnswersJsonThatFits() = runBlocking {
        assumeTrue(installed(gemma))
        note("--- gemma look; peak before load ${peakKb()} kB")
        val b = backend(gemma)
        val t0 = System.nanoTime()
        val r = b.look(listOf(LabeledImage(null, sheet())), question, cellSchema, "high")
        val s = (System.nanoTime() - t0) / 1e9
        note("gemma look: first call (load + sheet) %.1f s, prompt tokens ${r.usage.inputTokens}, reply tokens ${r.usage.outputTokens}, peak ${peakKb()} kB".format(s))
        val data = requireNotNull(r.data) { "no JSON in the reply" }
        assertEquals(6, data.getValue("cell").jsonPrimitive.content.toInt())
        assertEquals(0.0, r.usd, 0.0)
        val t1 = System.nanoTime()
        b.look(listOf(LabeledImage(null, sheet())), question, cellSchema, "high")
        note("gemma look: second call %.1f s, peak ${peakKb()} kB".format((System.nanoTime() - t1) / 1e9))
    }

    @Test
    fun gemmaChatAnswersAShortPrompt() = runBlocking {
        assumeTrue(installed(gemma))
        val b = backend(gemma)
        val t0 = System.nanoTime()
        val r = b.chat(listOf(ChatMessage(ChatRole.USER, "Say hello in five words.")))
        note("gemma chat: %.1f s, ${r.usage.outputTokens} tokens, peak ${peakKb()} kB".format((System.nanoTime() - t0) / 1e9))
        assertTrue(r.text.isNotBlank()); assertTrue(r.usage.outputTokens > 0)
    }

    @Test
    fun aTimeoutReallyStopsTheDecodeAndTheNextCallSucceeds() = runBlocking {
        assumeTrue(installed(gemma))
        val limit = 12.0
        val long = backend(gemma, OnDeviceConfig(textTimeoutS = limit, maxTokens = 2000))
        long.chat(listOf(ChatMessage(ChatRole.USER, "Say hi."))) // loads the model (a few seconds), well inside the limit
        val t0 = System.nanoTime()
        try {
            long.chat(listOf(ChatMessage(ChatRole.USER, "Write a very long story about a goat, at least 1500 words.")))
            fail("the call should have timed out")
        } catch (e: ModelError.Timeout) {
            val s = (System.nanoTime() - t0) / 1e9
            note("timeout: returned %.2f s after the call began (limit $limit s)".format(s))
            assertTrue("the decode kept running: $s s", s < limit + 1.5)
        }
        val t1 = System.nanoTime()
        val after = long.chat(listOf(ChatMessage(ChatRole.USER, "Say hello in five words.")))
        note("after the timeout: next call %.1f s".format((System.nanoTime() - t1) / 1e9))
        assertTrue(after.text.isNotBlank())
    }

    @Test
    fun qwenAnswersAtOnceWithReasoningOffAndSeesTheSheet() = runBlocking {
        assumeTrue(installed(qwen))
        val b = backend(qwen, OnDeviceConfig(thinking = false))
        val t0 = System.nanoTime()
        val r = b.chat(listOf(ChatMessage(ChatRole.USER, "What is 17 times 23? Answer with just the number.")))
        note("qwen chat (reasoning off): %.1f s, ${r.usage.outputTokens} tokens, peak ${peakKb()} kB".format((System.nanoTime() - t0) / 1e9))
        assertTrue("391 not in the reply", "391" in r.text)
        assertTrue("it reasoned: ${r.usage.outputTokens} tokens", r.usage.outputTokens < 40)
        val v = b.look(listOf(LabeledImage(null, sheet())), question, cellSchema, "high")
        note("qwen look: reply tokens ${v.usage.outputTokens}, peak ${peakKb()} kB")
        assertEquals(6, requireNotNull(v.data) { "no JSON in the reply" }.getValue("cell").jsonPrimitive.content.toInt())
    }
}
