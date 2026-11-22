package app.epicreplay.core.run

import app.epicreplay.core.PyMath
import app.epicreplay.core.audio.Wav
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.commentator.Commentator
import app.epicreplay.core.commentator.CommentatorRun
import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.mix.MixAudio
import app.epicreplay.core.mix.MixRules
import app.epicreplay.core.mix.MixStage
import app.epicreplay.core.signals.Signals
import app.epicreplay.core.spotter.Spotter
import app.epicreplay.core.spotter.SpotterRun
import app.epicreplay.core.storyboard.Storyboard
import app.epicreplay.core.style.Slots
import app.epicreplay.core.style.StylePacks
import app.epicreplay.core.text.SpokenUnits
import app.epicreplay.core.voice.Speech
import app.epicreplay.core.voice.VoiceStage
import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.record.ReplayMiss
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The stages of the flow that need no Android class (`pc/src/epic_replay/stages/s*.py`): each reads its inputs from the run folder, calls the port written for it and
 * writes the files the PC's stage writes. What a model stage needs (a Spotter, a Commentator over the chosen backend) comes in as a function, so a run with no model
 * chosen fails with a reason and the stages are tested with scripted models.
 */
object FlowStages {
    private fun read(ctx: RunContext, name: String): JsonObject = RunFiles.read(File(ctx.dir, name)) ?: throw IllegalStateException("no usable $name in the run folder")
    private fun write(ctx: RunContext, name: String, data: JsonObject, ascii: Boolean = true) {
        val text = (if (ascii) PythonJson.dumpsIndentedAscii(data, 2) else PythonJson.dumpsIndented(data, 2)) + "\n"
        app.epicreplay.core.io.Durable.writeAtomic(File(ctx.dir, name), text.toByteArray(Charsets.UTF_8))
    }
    private fun packOf(styles: StylePacks, uc: JsonObject): JsonObject {
        val style = (uc["style"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val language = (uc["language"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return if (style == null) styles.default().let { d -> if (language == null || language == "en") d else styles.resolved(d.getValue("id").jsonPrimitive.content, language) } else styles.resolved(style, language)
    }

    /** `signals.json` of S1 from the scan: the fields the Spotter and the checks read (the PC's file also holds the curves' z-scores, the loud peaks of each candidate and the grouped cuts). */
    fun signalsJson(name: String, s: Signals): JsonObject = buildJsonObject {
        put("clip", name); put("fps", PyMath.round(s.fps, 3)); put("frames", s.frames); put("has_sound", s.hasSound)
        put("cuts", JsonArray(s.cuts.map { JsonPrimitive(it) }))
        put("onsets_t", JsonArray(s.onsetsT.map { JsonPrimitive(PyMath.round(it, 3)) }))
        put("loud_peaks_t", JsonArray(s.loudPeaksT.map { JsonPrimitive(PyMath.round(it, 3)) }))
        put("candidates", JsonArray(s.candidates.map { c ->
            buildJsonObject {
                put("frame", c.frame); put("t", PyMath.round(c.t, 3)); put("score", PyMath.round(c.score, 2)); put("motion_z", PyMath.round(c.motionZ, 2)); put("sound_z", PyMath.round(c.soundZ, 2))
                put("sound_onset_t", c.soundOnsetT?.let { JsonPrimitive(PyMath.round(it, 3)) } ?: JsonNull)
                put("sound_onset_frame", c.soundOnsetT?.let { JsonPrimitive(Math.round(it * s.fps).toInt()) } ?: JsonNull)
            }
        }))
    }

    /** S2 (`s2_analysis.run`): the sheets and the events call over the chosen vision model; [run] is null when no vision model is chosen. */
    fun analysis(run: (File) -> SpotterRun?, model: () -> String, snapS: Double) = StageRunner { ctx ->
        val spotter = run(ctx.dir) ?: return@StageRunner StageResult("failed", "no vision model is chosen")
        val meta = read(ctx, "clip_meta.json"); val signals = read(ctx, "signals.json"); val uc = RunFiles.read(File(ctx.dir, "user_context.json")) ?: JsonObject(emptyMap())
        val w = meta.getValue("width").jsonPrimitive.content.toInt(); val h = meta.getValue("height").jsonPrimitive.content.toInt()
        val info = buildJsonObject {
            put("duration_s", meta.getValue("duration_s")); put("fps", signals.getValue("fps")); put("frames", signals.getValue("frames"))
            put("orientation", if (w > h) "landscape" else if (h > w) "portrait" else "square")
            put("candidates", signals.getValue("candidates"))
            put("user", buildJsonObject { for (k in listOf("activity", "player_name", "description")) put(k, uc[k] ?: JsonNull) })
        }
        val out = spotter.run(ctx.dir.name, info, signals, meta, snapS, model())
        write(ctx, "spotter_trace.json", out.trace, ascii = false)
        val looks = out.trace.getValue("sheets").jsonArray.map { it.jsonObject }
        val answered = looks.filter { (it["answer"] as? JsonObject)?.isNotEmpty() == true }
        if (answered.isNotEmpty()) {
            var note = if (answered.size < looks.size) "${looks.size - answered.size} sheet(s) left out" else ""
            val events = out.trace["events_call"] as? JsonObject
            if (events?.get("events") == null || events["events"] is JsonNull) note = (if (note.isNotEmpty()) "$note; " else "") + "the events call was left out"
            StageResult("ok", note)
        } else if (signals.getValue("candidates").jsonArray.isNotEmpty()) StageResult("degraded", "every sheet failed: the strongest S1 candidate is proposed")
        else StageResult("failed", "every sheet failed and S1 found no candidate")
    }

    /** S3 (`s3_key_moment.run`): the play sheet made from the trace; a flagged clip stops the run here with the reason. */
    fun keyMoment(spotter: Spotter, model: () -> String, snapS: Double) = StageRunner { ctx ->
        val trace = read(ctx, "spotter_trace.json"); val signals = read(ctx, "signals.json"); val meta = read(ctx, "clip_meta.json")
        val made = spotter.make(ctx.dir.name, trace.getValue("clip_info").jsonObject, trace.getValue("sheets").jsonArray.map { it.jsonObject }, trace["events_call"] as? JsonObject, signals, snapS, model(), meta)
        if (!made.accepted) return@StageRunner StageResult("failed", made.errors.joinToString("; ").take(500))
        write(ctx, "play_sheet.json", made.playSheet, ascii = false)
        val flag = made.playSheet.getValue("sensitive").jsonObject
        if (flag.getValue("flag").jsonPrimitive.content == "true") {
            val reason = (flag["reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty().ifEmpty { Spotter.SENSITIVE_DEFAULT }
            return@StageRunner StageResult("stopped", "sensitive: $reason")
        }
        StageResult("ok", if (made.cleared.isNotEmpty()) "emptied for the user: ${made.cleared.joinToString(", ")}" else "")
    }

    /** S4 (`s4_storyboard.run`). */
    fun storyboard(minOutputS: Double) = StageRunner { ctx ->
        write(ctx, "storyboard.json", Storyboard.build(read(ctx, "play_sheet.json"), read(ctx, "clip_meta.json"), minOutputS))
        StageResult("ok")
    }

    /**
     * S5 (`s5_script.run`): the Commentator's script from the slots; with a generic play sheet the code writes it. [commentator] gives the loop over the chosen text model
     * (null when none is chosen). The Mandarin English gloss of the PC (`gloss_en.json`, review text) is not made on the phone.
     */
    fun script(styles: StylePacks, commentator: Commentator, loop: (File) -> CommentatorRun?) = StageRunner { ctx ->
        val ps = read(ctx, "play_sheet.json"); val sb = read(ctx, "storyboard.json"); val uc = read(ctx, "user_context.json")
        val pack = packOf(styles, uc)
        val slots = Slots.build(sb, pack, (ps.getValue("climax").jsonObject["event_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content).map { it.jsonObject }
        val generic = (ps["mode"] as? JsonPrimitive)?.content == "generic"
        val calls: List<JsonObject>
        val usd: Double
        val outcome = try {
            if (generic) { calls = emptyList(); usd = 0.0; commentator.generic(slots, ps, uc, sb, pack) }
            else {
                val r = (loop(ctx.dir) ?: return@StageRunner StageResult("failed", "no text model is chosen")).write(slots, ps, uc, sb, pack)
                calls = r.calls; usd = r.usd; r.outcome
            }
        } catch (e: CapExceeded) { return@StageRunner StageResult("stopped", e.message ?: "a cap was reached") } catch (e: ReplayMiss) { return@StageRunner StageResult("failed", e.message ?: "no recording") }
        for (c in calls) ctx.emit(buildJsonObject { put("stage", "S5"); put("status", "tool"); for ((k, v) in c) put(k, v) })
        write(ctx, "script.json", outcome.script, ascii = false)
        write(ctx, "commentator_trace.json", buildJsonObject {
            put("calls", JsonArray(calls)); put("draft", outcome.draft); put("status", outcome.status); put("note", outcome.note); put("cost_usd", PyMath.round(usd, 6))
        }, ascii = false)
        StageResult(outcome.status, outcome.note)
    }

    /** S6 (`s6_checks.run`): the script against the rules, the failures in `checks.json`; any failure fails the stage. */
    fun checks(styles: StylePacks, checks: Checks, scriptSchema: String) = StageRunner { ctx ->
        val script = read(ctx, "script.json")
        val errs = mutableListOf<ValidationError>().also { JsonSchema.fromDefinition(scriptSchema).validate(script, it::add) }
        if (errs.isNotEmpty()) return@StageRunner StageResult("failed", errs.joinToString("; ") { "${it.objectPath}: ${it.message}" }.take(500))
        val fails = checks.check(script, read(ctx, "play_sheet.json"), read(ctx, "user_context.json").let { it }, packOf(styles, read(ctx, "user_context.json")))
        write(ctx, "checks.json", buildJsonObject {
            put("rules_run", JsonArray(listOf(1, 4, 5, 8).map { JsonPrimitive(it) })); put("passed", fails.isEmpty()); put("failures", fails)
        })
        if (fails.isEmpty()) StageResult("ok") else StageResult("failed", fails.joinToString("; ") { f -> f.jsonObject.let { "${it.getValue("where").jsonPrimitive.content}: ${it.getValue("detail").jsonPrimitive.content}" } }.take(500))
    }

    /** S7 (`s7_voice.run`): the takes by [speech], placed; each take's fitted file in `voice/` and `voice.json`. */
    fun voice(speech: () -> Speech?) = StageRunner { ctx ->
        val s = speech() ?: return@StageRunner StageResult("failed", "no voice is available")
        val out = VoiceStage(s).run(read(ctx, "script.json"), read(ctx, "storyboard.json"))
        val doc = out.doc ?: return@StageRunner StageResult(out.status, out.note)
        val dir = File(ctx.dir, "voice").apply { mkdirs() }
        for ((id, pcm) in out.takes) app.epicreplay.core.io.Durable.writeAtomic(File(dir, "$id.wav"), Wav.write(pcm))
        write(ctx, "voice.json", doc, ascii = false)
        StageResult(out.status, out.note)
    }

    /** S8 (`s8_mix.run`): `mix.wav` and `mix.json`. [audio] gives the sound of a layer file. */
    fun mix(styles: StylePacks, rules: MixRules, audio: (File) -> MixAudio) = StageRunner { ctx ->
        val uc = read(ctx, "user_context.json")
        val out = MixStage.run(read(ctx, "storyboard.json"), read(ctx, "voice.json"), packOf(styles, uc), uc, read(ctx, "clip_meta.json"), rules, audio(ctx.dir))
        val doc = out.doc ?: return@StageRunner StageResult(out.status, out.note)
        app.epicreplay.core.io.Durable.writeAtomic(File(ctx.dir, "mix.wav"), Wav.writeChannels(listOf(out.left!!, out.right!!), rules.sampleRate))
        write(ctx, "mix.json", doc)
        StageResult(out.status, out.note)
    }

    /** The pack a run's user context names (`loader.for_run`). */
    fun pack(styles: StylePacks, uc: JsonObject) = packOf(styles, uc)
}
