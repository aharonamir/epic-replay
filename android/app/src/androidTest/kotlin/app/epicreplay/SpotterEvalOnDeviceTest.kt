package app.epicreplay

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.run.FlowOrder
import app.epicreplay.core.run.RunFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T427, FR-027: the PC's `spotter_check.py --profile` on the phone. For each starter clip with an owner's label (the `.mp4` files of `files/eval/clips` and the `.json` files of `files/eval/labels`, pushed by
 * `android/tools/t427_eval_push.sh`) and each repeat, the app's own path runs from the clip to the play card on the on-device model: S0, S1, S2, S3. One line of numbers per run goes to
 * `files/t427_eval.jsonl`: the calls, the ones answered and the retries (schema fit), the proposal's error in frames against `climax_t` (strict) and the nearest of `climax_t` and
 * `other_climax_t` (any), whether a candidate lay within the tolerance (recall), the latency of each call, the seconds of S2, and the process's memory. Only numbers: no description of a clip.
 * Arguments: `-e repeats N` (default 2), `-e only clip.mp4`.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpotterEvalOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val graph = AppGraph(ctx)

    private class Counting(private val inner: ModelBackend, val calls: MutableList<Triple<Double, Boolean, Pair<Long, Long>>>) : ModelBackend {
        override val profile get() = inner.profile
        var live: File? = null
        private suspend fun <T> timed(what: String, call: suspend () -> T, tokens: (T) -> Pair<Long, Long>): T {
            val t0 = System.nanoTime()
            live?.appendText("start $what at ${System.currentTimeMillis() / 1000 % 100000}\n")
            try { return call().also { calls += Triple((System.nanoTime() - t0) / 1e9, true, tokens(it)) } }
            catch (e: Throwable) { calls += Triple((System.nanoTime() - t0) / 1e9, false, 0L to 0L); throw e }
        }
        override suspend fun look(images: List<app.epicreplay.core.models.LabeledImage>, question: String, schema: JsonObject?, detail: String) =
            timed("look ${images.size} image(s) ${images.map { it.jpeg.size / 1024 }} KB, ${question.length} chars", { inner.look(images, question, schema, detail) }) { it.usage.inputTokens to it.usage.outputTokens }
        override suspend fun chat(messages: List<app.epicreplay.core.models.ChatMessage>, schema: JsonObject?, tools: List<app.epicreplay.core.models.ToolDef>?) =
            timed("chat ${messages.size} messages", { inner.chat(messages, schema, tools) }) { it.usage.inputTokens to it.usage.outputTokens }
    }

    private fun status(key: String): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1

    @Test
    fun everyStarterClipToThePlayCardOnTheOnDeviceModel() {
        val gemma = graph.profiles.profiles["gemma-4-e2b-device"] as? app.epicreplay.core.models.OnDeviceProfile
        assumeTrue("Gemma is not offered or not on the phone", gemma != null && graph.modelDownloader().installed(gemma))
        val clips = File(ctx.filesDir, "eval/clips"); val labels = File(ctx.filesDir, "eval/labels")
        assumeTrue("no starter clips pushed", clips.isDirectory && labels.isDirectory)
        val source = File(ctx.filesDir, "runs").listFiles()?.filter { File(it, "user_context.json").isFile }?.maxByOrNull { it.name }
        assumeTrue("no run on the phone to take the consent record from", source != null)
        val args = InstrumentationRegistry.getArguments()
        val repeats = args.getString("repeats")?.toInt() ?: 2
        val only = args.getString("only")
        val out = File(ctx.filesDir, "t427_eval.jsonl")
        val fpsTolS = 0.25 // EPIC_KEYFRAME_TOL_S of the PC
        for (labelFile in labels.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val label = RunFiles.read(labelFile)!!
            val clipName = label.getValue("clip").jsonPrimitive.content
            if (only != null && only != clipName) continue
            val clip = File(clips, clipName).takeIf { it.isFile } ?: continue
            for (rep in 0 until repeats) {
                val d = File(ctx.filesDir, "t427_runs/eval").apply { deleteRecursively(); mkdirs() }
                clip.copyTo(File(d, "upload.mp4")); File(source!!, "user_context.json").copyTo(File(d, "user_context.json"))
                File(ctx.filesDir, "t427_live.txt").delete()
                val calls = ArrayList<Triple<Double, Boolean, Pair<Long, Long>>>()
                val launcher = RunLauncher(graph, ctx, ProviderMode.LIVE, File(ctx.cacheDir, "t427_rec"), app.epicreplay.core.setup.ModelsUsed(gemma!!, gemma), { p, r -> Counting(graph.backendFor(p, r), calls).also { it.live = File(ctx.filesDir, "t427_live.txt") } })
                val t0 = System.nanoTime()
                // a watchdog: when a run takes over 100 s the stacks of every thread are written (names and frames only), so a hang can be read
                val dog = Thread {
                    try { Thread.sleep(100_000); File(ctx.filesDir, "t427_stacks.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n") { (t, st) -> "\"${t.name}\" ${t.state}\n" + st.take(14).joinToString("\n") { "    at $it" } }) } catch (e: InterruptedException) { }
                }.apply { isDaemon = true; start() }
                val status = try { runBlocking { launcher.flow(d).run(FlowOrder.held(d)) } } finally { dog.interrupt() }
                val total = (System.nanoTime() - t0) / 1e9
                val rss = status("VmRSS"); val hwm = status("VmHWM")
                val row = buildJsonObject {
                    put("clip", clipName); put("repeat", rep); put("status", status); put("total_s", Math.round(total * 10) / 10.0)
                    val manifest = RunFiles.read(File(d, "manifest.json"))
                    val stages = manifest?.get("stages")?.jsonArray?.map { it.jsonObject }.orEmpty().associateBy { it.getValue("stage").jsonPrimitive.content }
                    put("s2_s", stages["S2"]?.get("duration_s")?.jsonPrimitive?.double ?: -1.0); put("s2_status", stages["S2"]?.get("status")?.jsonPrimitive?.content ?: "")
                    val trace = RunFiles.read(File(d, "spotter_trace.json"))
                    val sheets = trace?.get("sheets")?.jsonArray?.size ?: 0
                    val leftOut = trace?.get("sheets")?.jsonArray?.count { (it.jsonObject["left_out"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull } != null } ?: 0
                    val events = trace?.get("events_call") as? JsonObject
                    val eventsAnswered = events?.get("answer")?.let { it !is JsonNull } == true
                    val expected = sheets + (if (events != null) 1 else 0)
                    put("sheets", sheets); put("sheets_left_out", leftOut); put("events_answered", eventsAnswered)
                    put("calls", calls.size); put("calls_answered", calls.count { it.second }); put("retries", Math.max(0, calls.size - expected))
                    put("call_s", JsonArray(calls.map { JsonPrimitive(Math.round(it.first * 10) / 10.0) }))
                    put("tokens_in", calls.sumOf { it.third.first }); put("tokens_out", calls.sumOf { it.third.second })
                    val ps = RunFiles.read(File(d, "play_sheet.json")); val meta = RunFiles.read(File(d, "clip_meta.json"))
                    val fps = meta?.get("fps")?.jsonPrimitive?.double ?: 30.0
                    val climax = ps?.get("climax") as? JsonObject
                    if (climax != null) {
                        val frame = climax.getValue("frame").jsonPrimitive.int
                        val tol = Math.round(fpsTolS * fps).toInt()
                        val strict = Math.round(label.getValue("climax_t").jsonPrimitive.double * fps).toInt()
                        val any = listOf(strict) + (label["other_climax_t"]?.jsonArray?.map { Math.round(it.jsonPrimitive.double * fps).toInt() } ?: emptyList())
                        val errAny = any.map { frame - it }.minByOrNull { Math.abs(it) }!!
                        val cands = climax["candidates"]?.jsonArray?.map { it.jsonObject.getValue("frame").jsonPrimitive.int } ?: emptyList()
                        put("method", climax.getValue("method").jsonPrimitive.content); put("frame", frame); put("err_strict", frame - strict); put("err_any", errAny); put("tol_frames", tol)
                        put("ok_strict", Math.abs(frame - strict) <= tol); put("ok_any", Math.abs(errAny) <= tol)
                        put("recall_strict", cands.any { Math.abs(it - strict) <= tol }); put("recall_any", cands.any { c -> any.any { Math.abs(c - it) <= tol } })
                    }
                    put("rss_kb", rss); put("hwm_kb", hwm)
                    put("native_pss_kb", android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.nativePss)
                }
                out.appendText(row.toString() + "\n")
                runBlocking { graph.releaseModels() } // as the app does when the replay waits at the card: the next run loads the model again
            }
        }
    }
}
