package app.epicreplay.core.run

import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.conformance.ConformanceRunner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The `flow.run` conformance function (`conformance_flow.py`): [RunFlow] driven with scripted stages over rounds of starts, for the cases of the `flow` area. */
object FlowHarness {
    /** The process is gone: not an error of the stage, so [RunFlow] does not catch it. */
    private class Killed : Error()

    /** Named as the PC's exception, since a failed stage's note carries the class name. */
    private class RuntimeError(message: String) : Exception(message)

    fun run(a: JsonObject, shared: File): JsonObject {
        val uc = a.getValue("user_context"); val ps = a.getValue("play_sheet").takeIf { it !is JsonNull }
        val seedHeld = a.getValue("seed_held").jsonPrimitive.booleanOrNull == true
        val copy = ConsentCopy(File(shared, "copy/consent.json").readText())
        val dir = Files.createTempDirectory("flow").toFile().resolve("run").apply { mkdirs() }
        try {
            File(dir, "user_context.json").writeText(uc.toString())
            var sheet: JsonElement? = ps
            for (n in a.getValue("present").jsonArray.map { it.jsonPrimitive.content }) File(dir, n).writeText(if (n == "play_sheet.json" && sheet != null) sheet.toString() else "{}")
            val seeded = if (seedHeld) FlowOrder.held(dir) else emptySet()
            val out = ArrayList<JsonObject>()
            var seenEvents = 0
            for ((i, r) in a.getValue("rounds").jsonArray.withIndex()) {
                val round = r.jsonObject
                val script = (round["script"] as? JsonObject) ?: JsonObject(emptyMap())
                round["set_play_sheet"]?.let { patch ->
                    val merged = ConformanceRunner.mergePatch(RunFiles.read(File(dir, "play_sheet.json")), patch)
                    File(dir, "play_sheet.json").writeText(merged.toString())
                }
                round["rerun"]?.let { RunFlow.rerun(dir, it.jsonPrimitive.content) }
                val ran = ArrayList<String>()
                val stages = FlowOrder.ORDER.filter { it.name != FlowOrder.CHECKPOINT_A }.associate { def ->
                    def.name to StageRunner {
                        ran += def.name
                        val s = (script[def.name] as? JsonObject) ?: JsonObject(emptyMap())
                        val crash = s["crash"]?.jsonPrimitive?.content
                        if (crash == "before") throw Killed()
                        for (o in def.outputs) File(dir, o).writeText(if (o == "play_sheet.json" && sheet != null) sheet!!.toString() else "{}")
                        if (crash == "after") throw Killed()
                        s["raises"]?.let { throw RuntimeError(it.jsonPrimitive.content) }
                        StageResult(s["status"]?.jsonPrimitive?.content ?: "ok", s["note"]?.jsonPrimitive?.content ?: "")
                    }
                }
                val flow = RunFlow(dir, stages, { u -> copy.covers((u["consent"] as? JsonObject) ?: JsonObject(emptyMap()), "cloud", "cloud") }, JsonObject(emptyMap()), now = { "t" })
                val status = try { runBlocking { flow.run(if (i == 0) seeded else emptySet()) } } catch (e: Killed) { "killed" }
                val m = RunFiles.read(File(dir, RunFlow.MANIFEST))!!
                val events = File(dir, RunFlow.EVENTS).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.map { Json.parseToJsonElement(it).jsonObject } ?: emptyList()
                out += buildJsonObject {
                    put("status", if (status == "killed") "killed" else m.getValue("status").jsonPrimitive.content)
                    put("ran", JsonArray(ran.map { JsonPrimitive(it) }))
                    put("stages", JsonArray((m.getValue("stages") as JsonArray).map { s -> val o = s.jsonObject; JsonArray(listOf(o.getValue("stage"), o.getValue("status"), o.getValue("note"))) }))
                    put("events", JsonArray(events.drop(seenEvents).map { e -> JsonArray(listOf(e.getValue("stage"), e.getValue("status"))) }))
                    put("files", JsonArray((dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.startsWith(".") && it.name !in setOf("manifest.json", "events.jsonl", "cost.json") }.map { it.name }.sorted().map { JsonPrimitive(it) }))
                }
                seenEvents = events.size
            }
            return buildJsonObject { put("rounds", JsonArray(out)) }
        } finally { dir.parentFile.deleteRecursively() }
    }
}
