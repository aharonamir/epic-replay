package app.epicreplay.core.models

import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.budget.Reservation
import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.record.cacheKey
import app.epicreplay.core.secrets.Secret
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The shared conformance cases of spec 004 T408 (`shared/conformance/backend` and `budget`), run on the Kotlin
 * code. The expected outputs were written by the Python code and are never changed to make this pass. (The
 * runner for the other areas arrives with T412 and will take this one's place.)
 */
class BackendConformanceTest {
    private val root = File(Testing.shared, "conformance")

    private fun cases(area: String): List<File> =
        File(root, area).listFiles { f -> File(f, "case.json").isFile }!!.sortedBy { it.name }

    private fun load(dir: File): JsonObject = Json.parseToJsonElement(File(dir, "case.json").readText()).jsonObject

    private fun resolve(ref: JsonObject, dir: File): JsonElement =
        ref["value"] ?: Json.parseToJsonElement(File(dir, ref.getValue("file").jsonPrimitive.content).readText())

    /** Objects by key, lists in order, numbers as numbers (0 equals 0.0), a boolean never a number. */
    private fun differences(got: JsonElement, want: JsonElement, where: String = "$"): List<String> = when {
        want is JsonObject && got is JsonObject ->
            if (got.keys != want.keys) listOf("$where: keys ${got.keys.sorted()} != ${want.keys.sorted()}")
            else want.keys.flatMap { differences(got.getValue(it), want.getValue(it), "$where.$it") }
        want is JsonArray && got is JsonArray ->
            if (got.size != want.size) listOf("$where: ${got.size} items != ${want.size}")
            else want.indices.flatMap { differences(got[it], want[it], "$where[$it]") }
        want is JsonPrimitive && got is JsonPrimitive -> when {
            want is JsonNull || got is JsonNull -> if (want is JsonNull && got is JsonNull) emptyList() else listOf("$where: $got != $want")
            want.booleanOrNull != null || got.booleanOrNull != null -> if (want.booleanOrNull == got.booleanOrNull && !want.isString && !got.isString) emptyList() else listOf("$where: $got != $want")
            !want.isString && !got.isString -> if (want.doubleOrNull == got.doubleOrNull) emptyList() else listOf("$where: $got != $want")
            else -> if (want.isString == got.isString && want.content == got.content) emptyList() else listOf("$where: $got != $want")
        }
        else -> listOf("$where: $got != $want")
    }

    // -- backend.requests

    private fun requests(inputs: JsonObject, dir: File): JsonElement {
        val calls = resolve(inputs.getValue("calls").jsonObject, dir).jsonArray
        val role = Role.of(resolve(inputs.getValue("role").jsonObject, dir).jsonPrimitive.content)!!
        val key = resolve(inputs.getValue("key").jsonObject, dir).jsonPrimitive.contentOrNull
        val profile = Testing.profile(resolve(inputs.getValue("profile_id").jsonObject, dir).jsonPrimitive.content)
        val transport = CapturingTransport()
        val backend = CloudBackend(profile, role, transport, { key?.let { Secret(it) } })
        runBlocking {
            for (c in calls) {
                val call = c.jsonObject
                val schema = call["schema"] as? JsonObject
                if (call.getValue("kind").jsonPrimitive.content == "look") {
                    val images = call.getValue("images").jsonArray.map {
                        val i = it.jsonObject
                        LabeledImage(i["label"]?.jsonPrimitive?.contentOrNull, Base64.getDecoder().decode(i.getValue("base64").jsonPrimitive.content))
                    }
                    backend.look(images, call.getValue("question").jsonPrimitive.content, schema, call.getValue("detail").jsonPrimitive.content)
                } else {
                    val messages = call.getValue("messages").jsonArray.map {
                        val m = it.jsonObject
                        ChatMessage(ChatRole.valueOf(m.getValue("role").jsonPrimitive.content.uppercase()), m.getValue("content").jsonPrimitive.content)
                    }
                    backend.chat(messages, schema, null)
                }
            }
        }
        val records = JsonArray(transport.requests.map { Testing.record(it) })
        return buildJsonObject { put("requests", records); put("sha256", JsonPrimitive(PythonJson.sha256Hex(PythonJson.canonical(records)))) }
    }

    // -- budget.run

    private fun budget(inputs: JsonObject, dir: File): JsonElement {
        val caps = resolve(inputs.getValue("caps").jsonObject, dir).jsonObject
        val ops = resolve(inputs.getValue("ops").jsonObject, dir).jsonArray
        val ledger = Ledger(
            InMemoryLedgerStorage(),
            Caps(caps.getValue("run_usd").jsonPrimitive.content.toDouble(), caps.getValue("day_usd").jsonPrimitive.content.toDouble(), caps.getValue("pre_checkpoint_usd").jsonPrimitive.content.toDouble()),
            today = { "2026-11-17" }, now = { "t" },
        )
        val held = HashMap<String, Reservation>()
        val out = ops.map { o ->
            val op = o.jsonObject
            val id = op.getValue("id").jsonPrimitive.content
            var outcome = "ok"
            var cap: String? = null
            val runId: String
            when (op.getValue("op").jsonPrimitive.content) {
                "reserve" -> {
                    runId = op.getValue("run").jsonPrimitive.content
                    try {
                        held[id] = ledger.reserve(runId, op.getValue("estimate").jsonPrimitive.content.toDouble(), op.getValue("pre_checkpoint").jsonPrimitive.content.toBoolean())
                    } catch (e: CapExceeded) { outcome = "blocked"; cap = e.cap }
                }
                else -> {
                    val r = held.remove(id)!!
                    runId = r.runId
                    when (op.getValue("op").jsonPrimitive.content) {
                        "release" -> ledger.release(r)
                        "settle" -> ledger.settle(r, op.getValue("actual").jsonPrimitive.content.toDouble(), false)
                        "settle_at_estimate" -> ledger.settleAtEstimate(r, "Timeout")
                    }
                }
            }
            buildJsonObject {
                put("op", op.getValue("op")); put("id", JsonPrimitive(id)); put("outcome", JsonPrimitive(outcome))
                put("cap", cap?.let { JsonPrimitive(it) } ?: JsonNull)
                put("run_total", JsonPrimitive(ledger.runTotal(runId))); put("day_total", JsonPrimitive(ledger.dayTotal()))
            }
        }
        return JsonArray(out)
    }

    private fun run(dir: File): JsonElement {
        val case = load(dir)
        val inputs = case.getValue("inputs").jsonObject
        return when (val f = case.getValue("function").jsonPrimitive.content) {
            "backend.requests" -> requests(inputs, dir)
            "backend.recording_key" -> JsonPrimitive(
                cacheKey(
                    resolve(inputs.getValue("adapter").jsonObject, dir).jsonPrimitive.content,
                    resolve(inputs.getValue("model").jsonObject, dir).jsonPrimitive.content,
                    resolve(inputs.getValue("request").jsonObject, dir),
                    resolve(inputs.getValue("files").jsonObject, dir).jsonArray.map { it.jsonPrimitive.content },
                ),
            )
            "budget.run" -> budget(inputs, dir)
            else -> error("unknown function $f")
        }
    }

    private fun check(area: String) {
        val dirs = cases(area)
        assertTrue(dirs.size >= 6, "only ${dirs.size} cases in $area")
        for (dir in dirs) {
            val want = resolve(load(dir).getValue("expected").jsonObject, dir)
            assertEquals(emptyList(), differences(run(dir), want), "$area/${dir.name}")
        }
    }

    @Test fun theBackendCasesPass() = check("backend")

    @Test fun theBudgetCasesPass() = check("budget")

    @Test
    fun theComparisonCanFail() {
        assertTrue(differences(JsonPrimitive("a"), JsonPrimitive("b")).isNotEmpty())
        assertTrue(differences(JsonPrimitive(1), JsonPrimitive(1.5)).isNotEmpty())
        assertTrue(differences(JsonPrimitive(true), JsonPrimitive(1)).isNotEmpty())
        assertEquals(emptyList(), differences(JsonPrimitive(0), JsonPrimitive(0.0)))
    }
}
