package app.epicreplay.core.commentator

import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.conformance.ConformanceRunner
import app.epicreplay.core.conformance.TextFunctions
import app.epicreplay.core.models.CloudBackend
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.Testing
import app.epicreplay.core.net.JavaHttpTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import app.epicreplay.core.secrets.Secret
import app.epicreplay.core.storyboard.Storyboard
import app.epicreplay.core.style.Slots
import app.epicreplay.core.style.StyleLoader
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * T419's measurement on the cloud profile (a paid run; off unless `-Dmeasure.cloud=true`): the Kotlin loop against the one structured draft on the
 * PC's sample and on a second clip built from the Spotter's real recorded answers. The key is read from `pc/.env` and never printed. Replies are
 * recorded in `build/measure-recordings`, so a second run costs nothing. The summary goes to `build/commentator-measure.json`.
 */
class CommentatorMeasureTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val repo = shared.parentFile

    private fun key(): String? {
        val env = File(repo, "pc/.env")
        if (!env.isFile) return null
        val vars = env.readLines().filter { "=" in it && !it.trimStart().startsWith("#") }.associate { it.substringBefore("=").trim() to it.substringAfter("=").substringBefore("#").trim() }
        return vars["EPIC_AGENT_API_KEY"]?.ifEmpty { null } ?: vars["DEEPSEEK_API_KEY"]?.ifEmpty { null }
    }

    @Test
    fun theLoopAgainstTheStructuredDraftOnTheCloudProfile(): Unit {
        assumeTrue(System.getProperty("measure.cloud") == "true", "a paid measurement: run with -Dmeasure.cloud=true")
        val secret = Secret(key() ?: error("no DeepSeek key in pc/.env"))
        val profile = Testing.profile()
        val backend = CloudBackend(profile, Role.TEXT, JavaHttpTransport(), { secret })
        val words = CommentatorWords.load(shared)
        val commentator = Commentator(words, Checks.fromShared(shared), File(shared, "contracts/script.schema.json").readText())
        val packs = StyleLoader.loadShared(shared)
        val pack = packs.resolved("football_commentator", "en")
        val runner = ConformanceRunner(shared, TextFunctions.table(shared))
        val uc = Json.parseToJsonElement(File(shared, "fixtures/sample_run/user_context.json").readText()).jsonObject
        val meta = Json.parseToJsonElement(File(shared, "fixtures/sample_run/clip_meta.json").readText()).jsonObject

        fun fromSpotter(case: String): JsonObject {
            val dir = File(shared, "conformance/spotter/$case")
            val want = runner.resolve(runner.load(dir).getValue("expected").jsonObject, dir).jsonObject
            return want.getValue("play_sheet").jsonObject
        }
        val inputs = linkedMapOf<String, JsonObject>(
            "sample_run" to Json.parseToJsonElement(File(shared, "fixtures/sample_run/play_sheet.json").readText()).jsonObject,
            "clip_2" to fromSpotter("play_sheet_real_2"),
        )
        val results = ArrayList<JsonObject>()
        val dir = File("build/measure-recordings").apply { mkdirs() }
        val ledger = Ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.25, dayUsd = 20.0, preCheckpointUsd = 0.30), today = { "measure" }, now = { "t" })
        for ((name, ps) in inputs) {
            val sb = Storyboard.build(ps, meta, 20.0)
            val slots = Slots.build(sb, pack, ps.getValue("climax").jsonObject["event_id"]?.jsonPrimitive?.content).map { it.jsonObject }
            for (mode in listOf(CommentatorMode.TOOLS, CommentatorMode.STRUCTURED)) {
                val before = ledger.runTotal("run")
                val t0 = System.nanoTime()
                val r = runBlocking { CommentatorRun(commentator, backend, words, "deepseek-flash", ledger, Recorder(ProviderMode.RECORD, dir), "run", mode).write(slots, ps, uc, sb, pack) }
                val lines = r.outcome.script.getValue("lines").jsonArray.map { it.jsonObject }
                val generic = lines.count { it.getValue("generic").jsonPrimitive.content == "true" }
                results += buildJsonObject {
                    put("input", name); put("mode", mode.name); put("status", r.outcome.status); put("note", r.outcome.note); put("turns", r.turns)
                    put("calls", JsonArray(r.calls)); put("lines", lines.size); put("lines_with_no_event", generic)
                    put("written_by_code", r.outcome.note.substringAfter("generic lines for ", "").let { if (it.isEmpty() || it == "none") 0 else it.split(", ").size })
                    put("seconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0); put("usd", Math.round((ledger.runTotal("run") - before) * 1e6) / 1e6)
                    put("texts", JsonArray(lines.map { it.getValue("text") }))
                }
            }
        }
        File("build/commentator-measure.json").writeText(JsonArray(results).toString())
        for (r in results) println("MEASURE ${r["input"]!!.jsonPrimitive.content} ${r["mode"]!!.jsonPrimitive.content}: ${r["status"]!!.jsonPrimitive.content}, turns ${r["turns"]}, code-written ${r["written_by_code"]}, ${r["seconds"]} s, ${r["usd"]} USD")
        println("MEASURE total ${ledger.runTotal("run")} USD")
    }
}
