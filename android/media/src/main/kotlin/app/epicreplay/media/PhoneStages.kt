package app.epicreplay.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.epicreplay.core.PyMath
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.commentator.Commentator
import app.epicreplay.core.commentator.CommentatorRun
import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.mix.MixRules
import app.epicreplay.core.run.FlowStages
import app.epicreplay.core.run.RunContext
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.run.StageResult
import app.epicreplay.core.run.StageRunner
import app.epicreplay.core.setup.ModelsUsed
import app.epicreplay.core.spotter.Spotter
import app.epicreplay.core.spotter.SpotterRun
import app.epicreplay.core.style.StylePacks
import app.epicreplay.core.visuals.VisualsPlan
import app.epicreplay.core.voice.Speech
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** What the stages of a run on the phone need, given by whoever starts it: the shared data, and a way to the chosen models and voice (null: none is chosen, and the stage that needs it fails with that reason). */
class PhoneEnv(
    val context: Context,
    val styles: StylePacks,
    val checks: Checks,
    val commentator: Commentator,
    val scriptSchema: String,
    val mixRules: MixRules,
    val spotter: Spotter,
    val fonts: OverlayFonts,
    val models: ModelsUsed?,
    val visionModel: () -> String,
    val spotterRun: (File) -> FlowStages.SpotterHandle?,
    val commentatorLoop: (File) -> CommentatorRun?,
    val speech: () -> Speech?,
    val minOutputS: Double = 20.0,
    val snapS: Double = 0.25,
)

/** The stages of the flow with the phone's media (spec 004 FR-019): S0, S1, S9 and S10 here, the others from [FlowStages]. */
@UnstableApi
object PhoneStages {
    private fun read(ctx: RunContext, name: String): JsonObject = RunFiles.read(File(ctx.dir, name)) ?: throw IllegalStateException("no usable $name in the run folder")

    /** S0: the upload checked and copied as the working copy (`ClipIntake`). */
    fun intake() = StageRunner { ctx ->
        val upload = ctx.dir.listFiles { f -> f.isFile && f.name.startsWith("upload.") }?.firstOrNull() ?: return@StageRunner StageResult("failed", "no upload in the run folder")
        val r = ClipIntake.run(upload, upload.name, ctx.dir)
        StageResult(r.status, r.note)
    }

    /** S1: the signal scan (`SignalDecoder.scan`) into `signals.json`. */
    fun signals() = StageRunner { ctx ->
        val work = File(ctx.dir, "work.mp4")
        if (!work.exists()) return@StageRunner StageResult("failed", "no work.mp4 in the run folder")
        val s = SignalDecoder.scan(work.path, top = 3)
        RunFiles.write(File(ctx.dir, "signals.json"), FlowStages.signalsJson(work.name, s))
        if (s.hasSound) StageResult("ok") else StageResult("degraded", "no sound: motion only")
    }

    /** S9: one file for each segment of the visuals plan, made by [SegmentExporter], and `visuals.json`. */
    fun visuals(context: Context) = StageRunner { ctx ->
        val sb = read(ctx, "storyboard.json"); val ps = read(ctx, "play_sheet.json"); val meta = read(ctx, "clip_meta.json")
        val srcFrames = Math.round(meta.getValue("duration_s").jsonPrimitive.double * 30.0).toInt() // the clip at 30 frames a second (the phone reads it by time)
        val plan = VisualsPlan.plan(sb, ps["focus_box"], srcFrames).map { it.jsonObject }
        val exporter = SegmentExporter(context, File(ctx.dir, "work.mp4"))
        val dir = File(ctx.dir, "segments").apply { mkdirs() }
        val width = sb.getValue("width").jsonPrimitive.int; val height = sb.getValue("height").jsonPrimitive.int
        val fps = sb.getValue("fps").jsonPrimitive.double
        try {
            for (e in plan) exporter.export(e, width, height, File(dir, e.getValue("id").jsonPrimitive.content + ".mp4"))
        } catch (e: SegmentExportException) { return@StageRunner StageResult("failed", (e.message ?: "export failed").take(500)) }
        val segs = sb.getValue("segments").jsonArray.map { it.jsonObject }
        RunFiles.write(File(ctx.dir, "visuals.json"), buildJsonObject {
            put("segments", JsonArray(plan.mapIndexed { i, e ->
                buildJsonObject {
                    put("id", e.getValue("id")); put("file", "segments/${e.getValue("id").jsonPrimitive.content}.mp4"); put("method", e["method"] ?: JsonNull)
                    put("frames", e.getValue("frames")); put("duration_s", PyMath.round(e.getValue("frames").jsonPrimitive.int / fps, 3))
                    put("hold_s", segs[i]["hold_s"] ?: JsonPrimitive(0.0)); put("cost_usd", 0.0)
                }
            }))
            put("overlays", JsonArray(emptyList()))
        })
        StageResult("ok")
    }

    /** S10: the video and `render.json` (with the models each role ran on, FR-017). */
    fun compose(stage: ComposeStage, pack: (JsonObject) -> JsonObject, models: () -> JsonObject?) = StageRunner { ctx ->
        val uc = read(ctx, "user_context.json")
        val out = stage.run(ctx.dir, read(ctx, "storyboard.json"), read(ctx, "script.json"), read(ctx, "voice.json"), pack(uc)) { ctx.emit(buildJsonObject { put("stage", "S10"); put("status", "export"); put("note", it) }) }
        val doc = out.doc ?: return@StageRunner StageResult(out.status, out.note)
        val withModels = JsonObject(doc + (models()?.let { mapOf("models" to it) } ?: emptyMap()))
        RunFiles.write(File(ctx.dir, "render.json"), withModels)
        StageResult(out.status, out.note)
    }

    /** Every stage of the flow, by name. */
    fun all(env: PhoneEnv, mixAudio: (File) -> app.epicreplay.core.mix.MixAudio): Map<String, StageRunner> = mapOf(
        "S0" to intake(),
        "S1" to signals(),
        "S2" to FlowStages.analysis(env.spotterRun, env.visionModel, env.snapS),
        "S3" to FlowStages.keyMoment(env.spotter, env.visionModel, env.snapS),
        "S4" to FlowStages.storyboard(env.minOutputS),
        "S9" to visuals(env.context),
        "S5" to FlowStages.script(env.styles, env.commentator, env.commentatorLoop),
        "S6" to FlowStages.checks(env.styles, env.checks, env.scriptSchema),
        "S7" to FlowStages.voice(env.speech),
        "S8" to FlowStages.mix(env.styles, env.mixRules, mixAudio),
        "S10" to compose(ComposeStage(Composer(env.context), env.fonts), { FlowStages.pack(env.styles, it) }) { env.models?.toJson() },
    )

    /** The manifest's `stack` (as the PC's): the provider mode and the models each role ran on, recorded when the run starts so that a later change of choice does not alter it (FR-006). */
    fun stack(models: ModelsUsed?, mode: String): JsonObject = buildJsonObject {
        put("mode", mode)
        put("vision", models?.vision?.id ?: "none"); put("text", models?.text?.id ?: "none")
        put("models", models?.toJson() ?: JsonObject(emptyMap()))
    }
}
