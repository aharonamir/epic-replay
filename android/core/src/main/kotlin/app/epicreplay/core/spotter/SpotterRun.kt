package app.epicreplay.core.spotter

import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.ModelBackend
import app.epicreplay.core.models.ModelError
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the Spotter asks the vision model, word for word (`shared/copy/spotter.json`; the PC's recordings are keyed on the same words). */
class SpotterWords(private val data: JsonObject) {
    fun overviewQuestion(where: String): String = data.getValue("overview_q").jsonPrimitive.content.replace("{where}", where)
    val overviewSchema: JsonObject get() = data.getValue("overview_schema").jsonObject
    val eventsQuestion: String get() = data.getValue("events_q").jsonPrimitive.content
    val eventsSchema: JsonObject get() = data.getValue("events_schema").jsonObject

    companion object {
        fun load(shared: File) = SpotterWords(Json.parseToJsonElement(File(shared, "copy/spotter.json").readText()).jsonObject)
    }
}

/** One drawn contact sheet: the JPEG and what each cell is (`cell` 1 to 9, the source `frame`, its time `t`). */
class RenderedSheet(val jpeg: ByteArray, val cells: List<Cell>) {
    class Cell(val cell: Int, val frame: Int, val t: Double)
}

/** Draws a contact sheet of the given source frames (`media/` on the phone: the pixels are not the core's). */
fun interface SheetRenderer {
    fun render(frames: List<Int>): RenderedSheet
}

/** What S2 and S3 gave: the status as the PC's stages name it, a note, the trace (`spotter_trace.json`) and the play sheet when one was made. */
class SpotterOutcome(val status: String, val note: String, val trace: JsonObject, val playSheet: JsonObject?, val errors: List<String> = emptyList(), val cleared: List<String> = emptyList())

/**
 * S2 and S3 over a [ModelBackend] (plan 4.3 and 4.4): the five overview sheets and the events call sent together, each failure leaving
 * only its own call out, the limits in the code and not in the prompt, then the play sheet. [Spotter] holds the plain rules.
 */
class SpotterRun(private val spotter: Spotter, private val backend: ModelBackend, private val words: SpotterWords, private val renderer: SheetRenderer) {
    private val sheetCalls = AtomicInteger()
    private val eventsCalls = AtomicInteger()

    private fun refused(counter: AtomicInteger, limit: Int, kind: String): String? =
        if (counter.incrementAndGet() > limit) "at most $limit $kind call(s) a run" else null

    private fun leftOut(e: ModelError) = "${e.javaClass.simpleName}: ${e.message}".take(300)

    private fun cellsJson(r: RenderedSheet) = JsonArray(r.cells.map { c -> buildJsonObject { put("cell", c.cell); put("frame", c.frame); put("t", c.t) } })

    private suspend fun lookSheet(entry: JsonObject, drawn: RenderedSheet): JsonObject {
        val kind = entry.getValue("kind"); val name = entry.getValue("name")
        refused(sheetCalls, MAX_SHEET_CALLS, "sheet")?.let { return buildJsonObject { put("kind", kind); put("name", name); put("refused", it) } }
        return try {
            val r = backend.look(listOf(LabeledImage(null, drawn.jpeg)), words.overviewQuestion(entry.getValue("where").jsonPrimitive.content), words.overviewSchema, "high")
            buildJsonObject {
                put("kind", kind); put("name", name); put("cells", cellsJson(drawn)); put("answer", r.data ?: JsonNull)
                put("cost_usd", r.usd); put("latency_s", r.latencyS)
            }
        } catch (e: ModelError) {
            buildJsonObject { put("kind", kind); put("name", name); put("cells", cellsJson(drawn)); put("answer", JsonNull); put("left_out", leftOut(e)) }
        }
    }

    private suspend fun lookEvents(entries: List<JsonObject>, drawn: List<RenderedSheet>): JsonObject {
        refused(eventsCalls, MAX_EVENTS_CALLS, "events")?.let { return buildJsonObject { put("refused", it) } }
        val grids = JsonArray(entries.map { it.getValue("name") })
        return try {
            val r = backend.look(drawn.mapIndexed { k, d -> LabeledImage("Grid ${k + 1}", d.jpeg) }, words.eventsQuestion, words.eventsSchema, "high")
            val data = r.data
            buildJsonObject {
                put("grids", grids); put("answer", data ?: JsonNull)
                put("events", if (data == null) JsonNull else JsonArray(spotter.mapEvents(data, drawn.map { d -> d.cells.map { c -> buildJsonObject { put("t", c.t) } } })))
                put("cost_usd", r.usd); put("latency_s", r.latencyS)
            }
        } catch (e: ModelError) {
            buildJsonObject { put("grids", grids); put("answer", JsonNull); put("events", JsonNull); put("left_out", leftOut(e)) }
        }
    }

    /** S2, then S3. [info] is the Spotter's `clip_info` (duration, fps, frames, orientation, the S1 candidates, what the user typed). */
    suspend fun run(runId: String, info: JsonObject, signals: JsonObject, clipMeta: JsonObject, snapS: Double, model: String): SpotterOutcome {
        val plan = spotter.planOverview(info).map { it.jsonObject }
        val drawn = plan.map { renderer.render(it.getValue("frames").jsonArray.map { f -> f.jsonPrimitive.content.toInt() }) } // written first: no two calls draw one image
        val uniform = plan.indices.filter { plan[it].getValue("kind").jsonPrimitive.content == "uniform" }
        val (looks, events) = coroutineScope {
            val ev = async { lookEvents(uniform.map { plan[it] }, uniform.map { drawn[it] }) }
            val sheets = plan.indices.map { async { lookSheet(plan[it], drawn[it]) } }.awaitAll()
            sheets to ev.await()
        }
        val answered = looks.filter { (it["answer"] as? JsonObject)?.isNotEmpty() == true }
        val trace = buildJsonObject { put("clip_info", info); put("sheets", JsonArray(looks)); put("events_call", events) }
        val candidates = info.getValue("candidates").jsonArray
        var note = ""
        val status2: String
        if (answered.isNotEmpty()) {
            status2 = "ok"
            if (answered.size < looks.size) note = "${looks.size - answered.size} sheet(s) left out"
            if (events["events"] == null || events["events"] is JsonNull) note = (if (note.isNotEmpty()) "$note; " else "") + "the events call was left out"
        } else if (candidates.isNotEmpty()) {
            status2 = "degraded"; note = "every sheet failed: the strongest S1 candidate is proposed"
        } else return SpotterOutcome("failed", "every sheet failed and S1 found no candidate", trace, null)

        val made = spotter.make(runId, info, looks.map { it }, events, signals, snapS, model, clipMeta)
        if (!made.accepted) return SpotterOutcome("failed", made.errors.joinToString("; ").take(500), trace, made.playSheet, made.errors, made.cleared)
        val flag = made.playSheet.getValue("sensitive").jsonObject
        if (flag.getValue("flag").jsonPrimitive.content == "true") {
            val reason = (flag["reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty().ifEmpty { Spotter.SENSITIVE_DEFAULT }
            return SpotterOutcome("stopped", "sensitive: $reason", trace, made.playSheet, emptyList(), made.cleared)
        }
        val n = if (made.cleared.isNotEmpty()) "emptied for the user: ${made.cleared.joinToString(", ")}" else ""
        return SpotterOutcome(status2, listOf(note, n).filter { it.isNotEmpty() }.joinToString("; "), trace, made.playSheet, emptyList(), made.cleared)
    }

    companion object {
        const val MAX_SHEET_CALLS = 5
        const val MAX_EVENTS_CALLS = 1
    }
}
