package app.epicreplay

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.run.FlowOrder
import app.epicreplay.core.run.RunFiles
import app.epicreplay.media.ClipProbe
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T424 on the phone: the sample run's fixture runs to a video with the recorded voice and no network, and a run cut off between two stages is finished by the next start.
 * Two of the tests are the two halves of "kill the app, open it again": `startThenDie` ends the process in the middle of the run (the instrumentation shares the app's
 * process), `resumeAfterDeath` finishes the run from its folder.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class RunOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val testAssets = inst.context.assets
    private val graph = AppGraph(ctx)
    private val recordings = File(ctx.cacheDir, "t424_recordings")
    private val runs = File(ctx.filesDir, "t424_runs")

    private fun copyAsset(path: String, to: File) { to.parentFile!!.mkdirs(); testAssets.open(path).use { i -> to.outputStream().use { i.copyTo(it) } } }

    /** The run folder of the fixture, as the PC's `seed` makes one: the sample run's files and a working copy of the right size and length. */
    private fun fixtureRun(name: String): File {
        val d = File(runs, name).apply { deleteRecursively(); mkdirs() }
        for (f in listOf("clip_meta.json", "play_sheet.json", "storyboard.json", "script.json", "user_context.json")) copyAsset("fixtures/sample_run/$f", File(d, f))
        copyAsset("fixtures/clip/work_15s_1080p.mp4", File(d, "work.mp4"))
        recordings.deleteRecursively()
        for (n in testAssets.list("recordings/speech")!!) copyAsset("recordings/speech/$n", File(recordings, "speech/$n"))
        return d
    }

    private val cloud = graph.profiles.profiles.getValue("deepseek-flash").let { app.epicreplay.core.setup.ModelsUsed(it, it) }
    private fun launcher() = RunLauncher(graph, ctx, ProviderMode.REPLAY, recordings, cloud)
    private fun manifest(d: File): JsonObject = RunFiles.read(File(d, "manifest.json"))!!
    private fun statuses(d: File): Map<String, String> {
        val stages = RunFiles.read(File(d, "manifest.json"))?.get("stages")?.jsonArray ?: return emptyMap()
        return stages.associate { it.jsonObject.getValue("stage").jsonPrimitive.content to it.jsonObject.getValue("status").jsonPrimitive.content }
    }

    @Test
    fun theFixtureRunsToAVideoWithTheRecordedVoiceAndNoNetwork() {
        val d = fixtureRun("fixture")
        val t0 = System.nanoTime()
        val status = runBlocking { launcher().flow(d).run(FlowOrder.held(d)) }
        val s = statuses(d)
        File(ctx.filesDir, "t424_metrics.txt").appendText("fixture run: $status in ${(System.nanoTime() - t0) / 1_000_000_000} s; ${manifest(d).getValue("stages")}\n")
        assertEquals("done: $s", "done", status)
        assertEquals(listOf("seeded", "seeded", "seeded", "seeded", "seeded", "seeded", "seeded"), listOf("S0", "S1", "S2", "S3", "A", "S4", "S5").map { s.getValue(it) })
        for (st in listOf("S9", "S6", "S7", "S8", "S10")) assertTrue("$st: ${s[st]}", s[st] in setOf("ok", "degraded"))
        val render = RunFiles.read(File(d, "render.json"))!!
        assertEquals(28.9, render.getValue("duration_s").jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(867, ClipProbe.readable(File(d, "final.mp4").path)!!.videoSamplesUs.size)
        assertEquals("canvas", render.getValue("graphics").jsonPrimitive.content)
        assertEquals("deepseek-flash", render.getValue("models").jsonObject.getValue("vision").jsonObject.getValue("profile").jsonPrimitive.content)
        assertEquals("deepseek-flash", manifest(d).getValue("stack").jsonObject.getValue("models").jsonObject.getValue("text").jsonObject.getValue("profile").jsonPrimitive.content)
        assertEquals(0.0, manifest(d).getValue("total_usd").jsonPrimitive.content.toDouble(), 0.0) // recorded takes: nothing was spent
        assertTrue(File(d, "events.jsonl").readText().contains("\"S10\""))
    }

    @Test
    fun aRunCutOffBetweenTwoStagesIsFinishedByTheNextStart() {
        val d = fixtureRun("cut")
        val l = launcher()
        // the first start stops after S6, as if the app were closed: the flow is cancelled when S7 begins
        val job = GlobalScope.launch { l.flow(d).run(FlowOrder.held(d)) }
        val sawS6 = { statuses(d).containsKey("S6") }
        val until = System.currentTimeMillis() + 120_000
        while (!sawS6() && System.currentTimeMillis() < until) Thread.sleep(20)
        job.cancel(); runBlocking { job.join() }
        val after = statuses(d)
        assertTrue("S6 was recorded before the cut: $after", after.containsKey("S6"))
        assertFalse("not finished: $after", manifest(d).getValue("status").jsonPrimitive.content == "done")
        val status = runBlocking { launcher().flow(d).run() }
        assertEquals("done: ${statuses(d)}", "done", status)
        assertTrue(File(d, "final.mp4").isFile)
    }


    /** First half of the kill test: starts the run and ends the process after S6 is recorded (this test is expected to be reported as a crash). */
    @Test
    fun startThenDie() {
        val d = fixtureRun("killed")
        val job = GlobalScope.launch { launcher().flow(d).run(FlowOrder.held(d)) }
        val until = System.currentTimeMillis() + 120_000
        while (!statuses(d).containsKey("S6") && System.currentTimeMillis() < until) Thread.sleep(10)
        android.os.Process.killProcess(android.os.Process.myPid())
        job.toString()
    }

    /** Second half: the next start of the app finds the run folder as the kill left it and finishes the run. */
    @Test
    fun resumeAfterDeath() {
        val d = File(runs, "killed")
        assertTrue("run startThenDie first", File(d, "manifest.json").isFile)
        File(recordings, "speech").mkdirs()
        if (testAssets.list("recordings/speech")!!.isNotEmpty() && !File(recordings, "speech").list().orEmpty().any()) for (n in testAssets.list("recordings/speech")!!) copyAsset("recordings/speech/$n", File(recordings, "speech/$n"))
        val before = statuses(d)
        assertFalse("the kill came before the end: $before", manifest(d).getValue("status").jsonPrimitive.content == "done")
        val status = runBlocking { launcher().flow(d).run() }
        File(ctx.filesDir, "t424_metrics.txt").appendText("resume after death: before ${before.keys}, now $status ${statuses(d)}\n")
        assertEquals("done: ${statuses(d)}", "done", status)
        assertEquals(867, ClipProbe.readable(File(d, "final.mp4").path)!!.videoSamplesUs.size)
    }
}
