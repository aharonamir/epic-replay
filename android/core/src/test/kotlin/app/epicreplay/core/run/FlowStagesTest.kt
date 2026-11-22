package app.epicreplay.core.run

import app.epicreplay.core.audio.Wav
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.commentator.Commentator
import app.epicreplay.core.commentator.CommentatorMode
import app.epicreplay.core.commentator.CommentatorRun
import app.epicreplay.core.commentator.CommentatorWords
import app.epicreplay.core.conformance.ConformanceRunner
import app.epicreplay.core.mix.MixRules
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelResult
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Testing
import app.epicreplay.core.models.ToolDef
import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpResponse
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import app.epicreplay.core.style.StyleLoader
import app.epicreplay.core.voice.SpeechAdapter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The stages of the flow on the sample run's files: S4, S5 (the PC's recorded Commentator run, replayed), S6, S7 and S8 through [RunFlow], with no network. */
class FlowStagesTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val styles = StyleLoader.loadShared(shared)
    private val checks = Checks.fromShared(shared)
    private val words = CommentatorWords.load(shared)
    private val commentator = Commentator(words, checks, File(shared, "contracts/script.schema.json").readText())
    private val rules = MixRules.parse(File(shared, "mix/rules.json").readText())
    private val pcRecordings = File(shared.parentFile, "pc/tests/fixtures/recordings")
    private fun json(f: String) = Json.parseToJsonElement(File(shared, f).readText()).jsonObject

    /** A model that is never called: every answer in these tests is a recording. */
    private val silent = object : ModelBackend {
        override val profile: Profile = Testing.profile()
        override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult = error("no network")
        override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult = error("no network")
    }

    private fun folder(files: List<String>, uc: (JsonObject) -> JsonObject = { it }): File {
        val d = Files.createTempDirectory("run").toFile().resolve("r_test").apply { mkdirs() }
        for (f in files) File(d, f).writeText(File(shared, "fixtures/sample_run/$f").readText())
        File(d, "user_context.json").writeText(uc(json("fixtures/sample_run/user_context.json")).toString())
        return d
    }

    private fun flow(d: File, stages: Map<String, StageRunner>) = RunFlow(d, stages, { true }, JsonObject(emptyMap()))

    @Test
    fun theStoryboardAndTheRecordedScriptAndItsChecksAreMadeFromTheFilesOfTheRun() {
        val d = folder(listOf("clip_meta.json", "play_sheet.json"), uc = { JsonObject(it + ("style" to JsonPrimitive("news_reporter"))) })
        val stages = mapOf(
            "S4" to FlowStages.storyboard(20.0),
            "S5" to FlowStages.script(styles, commentator) { _ -> CommentatorRun(commentator, silent, words, "deepseek-flash", null, Recorder(ProviderMode.REPLAY, pcRecordings), mode = CommentatorMode.TOOLS) },
            "S6" to FlowStages.checks(styles, checks, File(shared, "contracts/script.schema.json").readText()),
        )
        val r = runBlocking {
            // the stages in between and after are not under test: they are told to have been done
            flow(d, stages).run(setOf("S0", "S1", "S2", "S3", "A", "S9", "S7", "S8", "S10"))
        }
        assertEquals("done", r)
        val m = RunFiles.read(File(d, "manifest.json"))!!
        val byStage = m.getValue("stages").jsonArray.associate { it.jsonObject.getValue("stage").jsonPrimitive.content to it.jsonObject }
        for (s in listOf("S4", "S5", "S6")) assertEquals("ok", byStage.getValue(s).getValue("status").jsonPrimitive.content, "$s ${byStage[s]}")
        assertEquals(emptyList(), ConformanceRunner.compare(RunFiles.read(File(d, "storyboard.json"))!!, json("fixtures/sample_run/storyboard.json"), 0.001))
        val script = RunFiles.read(File(d, "script.json"))!!
        assertTrue(script.getValue("lines").jsonArray.size >= 6)
        assertEquals(true, RunFiles.read(File(d, "checks.json"))!!.getValue("passed").jsonPrimitive.content.toBoolean())
        assertTrue(File(d, "commentator_trace.json").isFile && File(d, "events.jsonl").readText().contains("\"tool\""))
        assertTrue(m.getValue("style").jsonPrimitive.content == "news_reporter" && m.getValue("total_usd").jsonPrimitive.content.toDouble() == 0.0)
    }

    @Test
    fun theSampleRunGoesThroughTheVoiceAndTheMixToDoneWithTheRecordedTakesAndSurvivesAnInterruptedStart() {
        val d = folder(listOf("clip_meta.json", "play_sheet.json", "storyboard.json", "script.json"))
        File(d, "work.mp4").writeText("not a clip") // stands for the working copy; the media stages are replaced here
        val speech = SpeechAdapter(object : HttpTransport { override suspend fun send(request: HttpRequest, profileId: String): HttpResponse = error("a replay sends nothing") }, { null }, "eleven_v3", { error("replay") }, null, Recorder(ProviderMode.REPLAY, pcRecordings))
        val pack = FlowStages.pack(styles, json("fixtures/sample_run/user_context.json"))
        val audio = { run: File ->
            app.epicreplay.core.mix.MixAudio { file ->
                when {
                    file.startsWith("run/work.mp4") -> listOf(FloatArray(48000 * 15) { 0f }, FloatArray(48000 * 15) { 0f })
                    file.startsWith("run/") -> Wav.readChannels(File(run, file.removePrefix("run/")).readBytes()).first
                    else -> Wav.readChannels(File(shared, file).readBytes()).first
                }
            }
        }
        val fake = StageRunner { ctx -> for (o in listOf("visuals.json")) File(ctx.dir, o).writeText("{}"); StageResult("ok") }
        val compose = StageRunner { ctx -> for (o in listOf("render.json", "final.mp4")) File(ctx.dir, o).writeText("{}"); StageResult("ok") }
        var killAfterVoice = true
        val voice = FlowStages.voice { speech }
        val stages = mapOf(
            "S9" to fake, "S6" to FlowStages.checks(styles, checks, File(shared, "contracts/script.schema.json").readText()),
            "S7" to StageRunner { ctx -> voice.run(ctx).also { if (killAfterVoice) { killAfterVoice = false; throw kotlinx.coroutines.CancellationException("the app was closed") } } },
            "S8" to FlowStages.mix(styles, rules, audio), "S10" to compose,
        )
        val seeded = setOf("S0", "S1", "S2", "S3", "A", "S4", "S5")
        // the first start is cut off after S7 wrote its files and before it was recorded
        runCatching { runBlocking { flow(d, stages).run(seeded) } }
        assertTrue(File(d, "voice.json").isFile && File(d, "voice/l3.wav").isFile, "S7 had written its outputs")
        var m = RunFiles.read(File(d, "manifest.json"))!!
        for (st in listOf("S4", "S9", "S6")) assertTrue(m.getValue("stages").jsonArray.any { it.jsonObject.getValue("stage").jsonPrimitive.content == st }, "$st was recorded before the cut")
        assertTrue(m.getValue("stages").jsonArray.none { it.jsonObject.getValue("stage").jsonPrimitive.content == "S7" }, "S7 was not recorded: the start was cut off first")
        // the next start finds S7 done by its outputs, and goes on to the end
        val r = runBlocking { flow(d, stages).run() }
        assertEquals("done", r)
        m = RunFiles.read(File(d, "manifest.json"))!!
        val byStage = m.getValue("stages").jsonArray.associate { it.jsonObject.getValue("stage").jsonPrimitive.content to it.jsonObject }
        assertEquals("seeded", byStage.getValue("S7").getValue("status").jsonPrimitive.content)
        assertEquals("ok", byStage.getValue("S8").getValue("status").jsonPrimitive.content, byStage["S8"].toString())
        val mix = RunFiles.read(File(d, "mix.json"))!!
        assertEquals(-14.0, mix.getValue("loudness_lufs").jsonPrimitive.content.toDouble(), 0.5)
        assertEquals(emptyList(), ConformanceRunner.compare(RunFiles.read(File(d, "voice.json"))!!.getValue("lines"), json("fixtures/voice/sample_run_voice.json").getValue("lines"), 0.03))
        assertTrue(pack.isNotEmpty())
    }
}
