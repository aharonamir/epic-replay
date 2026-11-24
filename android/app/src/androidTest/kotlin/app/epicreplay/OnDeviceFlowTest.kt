package app.epicreplay

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.run.FlowOrder
import app.epicreplay.core.run.RunFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T427: the app's own path (S0 to the play card, the Gemma model on the phone for both roles, the launcher the app uses) over the newest real clip on the phone. The owner's run spent 300 s
 * on its first contact sheet; the same sheet answered in 10 s in a test of the backend alone. Only seconds, statuses and the stage notes go to `files/t427_flow.txt`.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OnDeviceFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val graph = AppGraph(ctx)

    @Test
    fun theFlowFromTheClipToTheCardOnTheOnDeviceModel() {
        val gemma = graph.profiles.profiles["gemma-4-e2b-device"]
        assumeTrue("Gemma is not offered", gemma != null)
        assumeTrue("the Gemma files are not on the phone", graph.modelDownloader().installed(gemma as app.epicreplay.core.models.OnDeviceProfile))
        val source = File(ctx.filesDir, "runs").listFiles()?.filter { File(it, "upload.mp4").isFile && File(it, "user_context.json").isFile }?.maxByOrNull { it.name }
        assumeTrue("no real clip on the phone", source != null)
        val d = File(ctx.filesDir, "t427_runs/flow").apply { deleteRecursively(); mkdirs() }
        File(source!!, "upload.mp4").copyTo(File(d, "upload.mp4")); File(source, "user_context.json").copyTo(File(d, "user_context.json"))
        val calls = File(ctx.filesDir, "t427_calls.txt").apply { delete() }
        val timed: (app.epicreplay.core.models.Profile, app.epicreplay.core.models.Role) -> app.epicreplay.core.models.ModelBackend = { p, r -> Timing(graph.backendFor(p, r), calls) }
        val launcher = RunLauncher(graph, ctx, ProviderMode.LIVE, File(ctx.cacheDir, "t427_rec"), app.epicreplay.core.setup.ModelsUsed(gemma, gemma), timed, { _ -> object : app.epicreplay.core.voice.Speech { override suspend fun synthesise(text: String, voiceId: String, delivery: String, language: String, timings: Boolean): app.epicreplay.core.voice.Take = throw IllegalStateException("no voice in this test: nothing is sent to ElevenLabs") } })
        val t0 = System.nanoTime()
        val status = runBlocking { launcher.flow(d).run(FlowOrder.held(d)) }
        val stages = RunFiles.read(File(d, "manifest.json"))!!.getValue("stages").jsonArray.joinToString("; ") { s -> val o = s.jsonObject; "${o.getValue("stage").jsonPrimitive.content} ${o.getValue("status").jsonPrimitive.content} ${o.getValue("duration_s").jsonPrimitive.content} s ${o["note"]?.jsonPrimitive?.content?.take(120) ?: ""}" }
        File(ctx.filesDir, "t427_flow.txt").appendText("flow: $status in ${(System.nanoTime() - t0) / 1_000_000_000} s: $stages\n")
        assertEquals("waiting_play_card", status)
        val trace = RunFiles.read(File(d, "spotter_trace.json"))!!
        val left = trace.getValue("sheets").jsonArray.count { it.jsonObject["left_out"]?.let { l -> l !is kotlinx.serialization.json.JsonNull } == true }
        assertEquals("no sheet is left out", 0, left)
        // on to the commentator: the card is answered as it stands, and the run goes on until the voice (which is refused here, so nothing is sent anywhere)
        val ps = RunFiles.read(File(d, "play_sheet.json"))!!; val uc = RunFiles.read(File(d, "user_context.json"))!!; val meta = RunFiles.read(File(d, "clip_meta.json"))!!
        val frame = ps.getValue("climax").jsonObject.getValue("frame").jsonPrimitive.content.toInt()
        val r = app.epicreplay.core.card.PlayCard.answer(ps, uc, meta, frame, JsonObject(emptyMap()), JsonObject(emptyMap()), false)
        RunFiles.write(File(d, "play_sheet.json"), r.playSheet); RunFiles.write(File(d, "user_context.json"), r.userContext)
        val t1 = System.nanoTime()
        val status2 = runBlocking { launcher.flow(d).run() }
        val after = RunFiles.read(File(d, "manifest.json"))!!.getValue("stages").jsonArray.map { it.jsonObject }.associate { it.getValue("stage").jsonPrimitive.content to (it.getValue("status").jsonPrimitive.content + " " + it.getValue("duration_s").jsonPrimitive.content + " s " + (it["note"]?.jsonPrimitive?.content?.take(100) ?: "")) }
        File(ctx.filesDir, "t427_flow.txt").appendText("after the card: $status2 in ${(System.nanoTime() - t1) / 1_000_000_000} s: $after\n")
        assertTrue("S5 ${after["S5"]}", after["S5"]?.startsWith("ok") == true || after["S5"]?.startsWith("degraded") == true)
    }
}

/** Seconds, sizes and tokens of every call (never the question or the answer), for T427. */
private class Timing(private val inner: app.epicreplay.core.models.ModelBackend, private val log: File) : app.epicreplay.core.models.ModelBackend {
    override val profile get() = inner.profile
    private suspend fun <T : app.epicreplay.core.models.ModelResult> timed(what: String, sizes: String, call: suspend () -> T): T {
        val t0 = System.nanoTime()
        try { return call().also { log.appendText("$what $sizes: ${(System.nanoTime() - t0) / 1_000_000_000} s, ${it.usage.inputTokens} in, ${it.usage.outputTokens} out, ${it.text.length} chars\n") }
        } catch (e: Throwable) { log.appendText("$what $sizes: ${(System.nanoTime() - t0) / 1_000_000_000} s, ${e.javaClass.simpleName}: ${e.message?.take(100)}\n"); throw e }
    }
    override suspend fun look(images: List<app.epicreplay.core.models.LabeledImage>, question: String, schema: kotlinx.serialization.json.JsonObject?, detail: String) =
        timed("look", "${images.size} image(s) ${images.sumOf { it.jpeg.size } / 1024} KB, ${question.length} chars asked, schema ${schema?.toString()?.length ?: 0} chars") { inner.look(images, question, schema, detail) }
    override suspend fun chat(messages: List<app.epicreplay.core.models.ChatMessage>, schema: kotlinx.serialization.json.JsonObject?, tools: List<app.epicreplay.core.models.ToolDef>?) =
        timed("chat", "${messages.size} message(s) ${messages.sumOf { it.content.length }} chars, schema ${schema?.toString()?.length ?: 0} chars") { inner.chat(messages, schema, tools) }
}
