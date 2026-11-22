package app.epicreplay.core.run

import app.epicreplay.core.PyMath
import app.epicreplay.core.io.Durable
import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.mix.Mix
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a stage gave: its status (`ok`, `degraded`, `waiting`, `stopped` or `failed`, as the PC's) and a note for the person. */
class StageResult(val status: String, val note: String = "") {
    init { require(status in STATUSES) { "unknown stage status $status" } }

    companion object { val STATUSES = setOf("ok", "degraded", "waiting", "stopped", "failed") }
}

/** One stage of the flow: its name, the files that show it has been done and the folder a re-run removes (`flow/runner.py ORDER` and `FOLDERS`). */
class StageDef(val name: String, val outputs: List<String>, val folder: String? = null)

/** The place of a stage run: the run folder and the way to tell the events file about something. */
class RunContext(val dir: File, val emit: (JsonObject) -> Unit)

fun interface StageRunner {
    suspend fun run(ctx: RunContext): StageResult
}

/** The order of the flow and what its stages leave behind (`pc/src/epic_replay/flow/runner.py`). S9 comes before S5: it needs only the storyboard. */
object FlowOrder {
    const val CHECKPOINT_A = "A"
    val ORDER = listOf(
        StageDef("S0", listOf("clip_meta.json", "work.mp4")),
        StageDef("S1", listOf("signals.json")),
        StageDef("S2", listOf("spotter_trace.json")),
        StageDef("S3", listOf("play_sheet.json")),
        StageDef(CHECKPOINT_A, emptyList()),
        StageDef("S4", listOf("storyboard.json")),
        StageDef("S9", listOf("visuals.json"), folder = "segments"),
        StageDef("S5", listOf("script.json")),
        StageDef("S6", listOf("checks.json")),
        StageDef("S7", listOf("voice.json"), folder = "voice"),
        StageDef("S8", listOf("mix.json", "mix.wav")),
        StageDef("S10", listOf("render.json", "final.mp4"), folder = "overlays"),
    )
    val AFTER_CARD: Set<String> = ORDER.dropWhile { it.name != CHECKPOINT_A }.drop(1).map { it.name }.toSet()
    val GO_ON = setOf("ok", "degraded")
    val DONE = setOf("ok", "degraded", "seeded")
    val ENDED = setOf("done", "failed", "stopped")

    /** The stages whose outputs the folder holds, and the card when the play sheet is confirmed (`held`). */
    fun held(dir: File): Set<String> {
        val present = dir.list()?.toSet() ?: emptySet()
        val seeded = ORDER.filter { it.outputs.isNotEmpty() && it.outputs.all { o -> o in present } }.map { it.name }.toMutableSet()
        if ("S3" in seeded && Mix.truthy(RunFiles.read(File(dir, "play_sheet.json"))?.get("confirmed_by_user"))) seeded += CHECKPOINT_A
        if ("S3" in seeded) { seeded += "S1"; seeded += "S2" }
        return seeded
    }
}

/** The models a run started with, read back from its manifest (spec 004 FR-006: a run already started keeps the profiles it started with, whatever the choice is now). */
object RunStack {
    /** The vision and text profiles named in the manifest's `stack.models`, or null when there is none or a profile is no longer offered (the caller then says so rather than use another). */
    fun saved(manifest: JsonObject?, profiles: Map<String, app.epicreplay.core.models.Profile>): app.epicreplay.core.setup.ModelsUsed? {
        val models = (manifest?.get("stack") as? JsonObject)?.get("models") as? JsonObject ?: return null
        fun profile(role: String) = ((models[role] as? JsonObject)?.get("profile") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { profiles[it] }
        val vision = profile("vision") ?: return null; val text = profile("text") ?: return null
        return app.epicreplay.core.setup.ModelsUsed(vision, text)
    }
}

object RunFiles {
    fun read(f: File): JsonObject? = try { Json.parseToJsonElement(f.readText()).jsonObject } catch (e: Exception) { null }

    /** A JSON file written whole, to a neighbour file renamed into place, as `write_json` of the PC: a reader never sees it half written, and a kill leaves the old one or the new. */
    fun write(f: File, data: JsonObject) = Durable.writeAtomic(f, (PythonJson.dumpsIndentedAscii(data, 2) + "\n").toByteArray(Charsets.UTF_8))
}

/**
 * The flow on the phone (`flow/runner.py Run`): the stages in order, each recorded in `manifest.json` (status, start, duration, cost, note) and told in `events.jsonl`;
 * what happens on a start with some stages already done is the PC's: a stage that is done or seeded is not run again, one that was tried and failed, stopped or is
 * waiting runs again, one never tried whose outputs are all there is recorded as seeded (so a kill after a stage wrote its outputs and before it was recorded costs
 * nothing). Consent is checked before S2, the first stage that sends anything (FR-003), and the sensitivity of the clip before every stage after the card (FR-009).
 * Checkpoint A has no prompt: with the play sheet not confirmed the run ends `waiting_play_card`, and the next start (after the screen wrote the answer) goes on.
 * [consentOk] says whether the user context's consent covers the run's models. A cancelled coroutine is not a failure: nothing is recorded for the stage it was in.
 */
class RunFlow(
    private val dir: File,
    private val stages: Map<String, StageRunner>,
    private val consentOk: (JsonObject) -> Boolean,
    private val stack: JsonObject,
    private val spentUsd: () -> Double = { 0.0 },
    private val now: () -> String = { DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()) },
    private val say: (String) -> Unit = {},
) {
    private val manifest: LinkedHashMap<String, JsonElement> = LinkedHashMap(RunFiles.read(File(dir, MANIFEST)) ?: buildJsonObject {
        put("run_id", dir.name); put("created_at", now()); put("status", "running"); put("style", JsonNull); put("parent_run_id", JsonNull); put("demo", false)
        put("stack", stack); put("stages", JsonArray(emptyList()))
    })
    private val ctx = RunContext(dir, ::emit)

    private val records: List<JsonObject> get() = (manifest.getValue("stages") as JsonArray).map { it.jsonObject }
    private fun statusOf(stage: String): String? = records.lastOrNull { it.getValue("stage").jsonPrimitive.content == stage }?.getValue("status")?.jsonPrimitive?.content

    fun emit(event: JsonObject) {
        val line = buildJsonObject { put("run_id", dir.name); put("at", now()); for ((k, v) in event) put(k, v) }
        File(dir, EVENTS).appendText(PythonJson.dumps(line) + "\n")
    }

    private fun save() = RunFiles.write(File(dir, MANIFEST), JsonObject(manifest))

    private fun record(stage: String, status: String, t0: Long, note: String = "", cost: Double = 0.0) {
        val rec = buildJsonObject {
            put("stage", stage); put("status", status); put("started_at", now()); put("duration_s", PyMath.round((System.nanoTime() - t0) / 1e9, 2))
            put("cost_usd", PyMath.round(cost, 6)); put("note", note)
        }
        manifest["stages"] = JsonArray(records + rec)
        save()
    }

    /** Why the clip may not be narrated, or null: the play sheet is flagged sensitive, or the model could not check it and nobody answered for it (FR-009). */
    fun notCleared(): String? {
        val ps = RunFiles.read(File(dir, "play_sheet.json")) ?: return "sensitive: there is no play sheet to check"
        val sens = (ps["sensitive"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: JsonObject(emptyMap())
        if (Mix.truthy(sens["flag"])) return "sensitive: " + (if (Mix.truthy(sens["reason"])) sens.getValue("reason").jsonPrimitive.content else "the clip may show someone hurt or upset")
        if (sens["checked"]?.let { it is JsonPrimitive && !it.isString && it.content == "false" } == true) return "sensitive: nobody checked that everyone in the clip is fine"
        return null
    }

    private fun playCard(): StageResult {
        val ps = RunFiles.read(File(dir, "play_sheet.json")) ?: return StageResult("failed", "no play sheet at the play card")
        if (Mix.truthy(ps["confirmed_by_user"])) return StageResult("ok", "key moment frame ${ps.getValue("climax").jsonObject.getValue("frame").jsonPrimitive.content}")
        return StageResult("waiting", "waiting for the play card")
    }

    /** Runs the flow from the first stage not done; returns the manifest's status. [seeded] are stages whose outputs were put there on purpose (a fixture). */
    suspend fun run(seeded: Set<String> = emptySet()): String {
        val uc = RunFiles.read(File(dir, "user_context.json")) ?: JsonObject(emptyMap())
        manifest["style"] = uc["style"] ?: JsonNull
        manifest["language"] = uc["language"] ?: JsonPrimitive("en")
        manifest["parent_run_id"] = uc["parent_run_id"] ?: JsonNull
        manifest["status"] = JsonPrimitive("running")
        val tried = records.associate { it.getValue("stage").jsonPrimitive.content to it.getValue("status").jsonPrimitive.content }
        val done = tried.filterValues { it in FlowOrder.DONE }.keys
        for (def in FlowOrder.ORDER) {
            val stage = def.name
            val t0 = System.nanoTime()
            if (stage in FlowOrder.AFTER_CARD) notCleared()?.let { return finish("stopped", stage, t0, it) }
            if (stage in done) continue
            if (stage in seeded) { record(stage, "seeded", t0); continue }
            if (def.outputs.isNotEmpty() && stage !in tried && def.outputs.all { File(dir, it).exists() }) { record(stage, "seeded", t0, "its output was already there"); continue }
            if (stage == "S2" && !consentOk(uc)) return finish("stopped", stage, t0, "consent is required before S2")
            val spent = spentUsd()
            emit(buildJsonObject { put("stage", stage); put("status", "started") })
            val res = try {
                if (stage == FlowOrder.CHECKPOINT_A) playCard() else (stages[stage] ?: return finish("failed", stage, t0, "no runner for $stage")).run(ctx)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { StageResult("failed", "${e.javaClass.simpleName}: ${e.message}".take(500)) }
            val cost = spentUsd() - spent
            emit(buildJsonObject {
                put("stage", stage); put("status", if (res.status == "ok") "finished" else res.status); put("note", res.note.ifEmpty { null }); put("cost_usd", PyMath.round(cost, 6))
            })
            record(stage, res.status, t0, res.note, cost)
            say("%-4s %-9s %6.1f s  %.4f USD  %s".format(stage, res.status, (System.nanoTime() - t0) / 1e9, cost, res.note))
            if (res.status !in FlowOrder.GO_ON) return finish(res.status, stage, t0, res.note)
        }
        return finish("done", null, System.nanoTime(), "")
    }

    private fun finish(status: String, stage: String?, t0: Long, note: String): String {
        manifest["status"] = JsonPrimitive(if (status == "waiting") "waiting_play_card" else status)
        manifest["total_usd"] = JsonPrimitive(spentUsd())
        if (stage != null && status == "stopped" && records.none { it.getValue("stage").jsonPrimitive.content == stage }) record(stage, status, t0, note)
        save()
        return manifest.getValue("status").jsonPrimitive.content
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val EVENTS = "events.jsonl"

        /** Makes an ended run start again at [stage]: deletes the outputs of it and of the stages after it, and takes them off the manifest (`rerun`, FR-033). */
        fun rerun(dir: File, stage: String) {
            val names = FlowOrder.ORDER.map { it.name }
            require(stage in names && stage != FlowOrder.CHECKPOINT_A) { "no stage $stage to re-run; one of ${names.filter { it != FlowOrder.CHECKPOINT_A }.joinToString(", ")}" }
            val m = RunFiles.read(File(dir, MANIFEST)) ?: throw IllegalArgumentException("no manifest in ${dir.name}")
            val status = m.getValue("status").jsonPrimitive.content
            require(status in FlowOrder.ENDED) { "the run is $status; only an ended run can be re-run" }
            val later = names.drop(names.indexOf(stage)).toSet()
            for (def in FlowOrder.ORDER) {
                if (def.name !in later) continue
                for (name in def.outputs + listOfNotNull(def.folder)) File(dir, name).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
            }
            val kept = (m.getValue("stages") as JsonArray).filter { it.jsonObject.getValue("stage").jsonPrimitive.content !in later }
            RunFiles.write(File(dir, MANIFEST), JsonObject(LinkedHashMap(m).also { it["stages"] = JsonArray(kept) }))
        }
    }
}
