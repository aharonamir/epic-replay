package app.epicreplay.core.conformance

import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The function of a case is not in this runner's table (yet): its area is ported by a later task, or it is checked by its own test. */
class NotPorted(val function: String) : Exception("no port of $function")

/** A reference this runner does not read yet (`style`: the pack loader is T414). */
class UnsupportedReference(val kind: String) : Exception("reference $kind is not supported yet")

/**
 * The Kotlin side of the shared conformance cases (spec 002 FR-020 to FR-023; `shared/conformance/README.md`), the same
 * rules as `pc/src/epic_replay/conformance.py`: a case names a function, its inputs and the expected output; the
 * output is compared as JSON, numbers within the case's tolerance and everything else exactly.
 */
class ConformanceRunner(private val shared: File, private val functions: Map<String, (Map<String, JsonElement>) -> JsonElement>) {
    private val root = File(shared, "conformance")

    /** Every case folder, ordered by area and name. */
    fun cases(): List<File> = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        .flatMap { area -> area.listFiles { f -> File(f, "case.json").isFile }.orEmpty().sortedBy { it.name } }

    fun load(caseDir: File): JsonObject = Json.parseToJsonElement(File(caseDir, "case.json").readText()).jsonObject

    fun function(caseDir: File): String = load(caseDir).getValue("function").jsonPrimitive.content

    fun isPorted(caseDir: File): Boolean = function(caseDir) in functions

    /** An input or the expected value: `value`, `file`, `fixture`, with `slice`, `field` and `merge_patch` after a file. */
    fun resolve(ref: JsonObject, caseDir: File): JsonElement {
        if ("value" in ref) return ref.getValue("value")
        if ("style" in ref) throw UnsupportedReference("style")
        val path = if ("file" in ref) File(caseDir, ref.getValue("file").jsonPrimitive.content)
        else File(shared, "fixtures/" + ref.getValue("fixture").jsonPrimitive.content)
        var data: JsonElement = Json.parseToJsonElement(path.readText())
        ref["slice"]?.jsonArray?.let { s ->
            val a = s[0].jsonPrimitive.content.toInt(); val b = s[1].jsonPrimitive.content.toInt()
            require(a >= 0 && b >= 0) { "negative slices are not used by any case" }
            val items = data.jsonArray
            data = JsonArray(items.subList(minOf(a, items.size), minOf(b, items.size)))
        }
        ref["field"]?.let { f -> data = JsonArray(data.jsonArray.map { it.jsonObject.getValue(f.jsonPrimitive.content) }) }
        ref["merge_patch"]?.let { data = mergePatch(data, it) }
        return data
    }

    /** What the Kotlin code gives for the case. */
    fun run(caseDir: File): JsonElement {
        val case = load(caseDir)
        val name = case.getValue("function").jsonPrimitive.content
        val fn = functions[name] ?: throw NotPorted(name)
        val args = case.getValue("inputs").jsonObject.mapValues { (_, v) -> resolve(v.jsonObject, caseDir) }
        return fn(args)
    }

    /** The differences between the output and the expected value, each with its JSON path; empty when they agree. */
    fun check(caseDir: File): List<String> {
        val case = load(caseDir)
        val want = resolve(case.getValue("expected").jsonObject, caseDir)
        val tolerance = case["tolerance_s"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
        return compare(run(caseDir), want, tolerance)
    }

    companion object {
        /** RFC 7386 JSON Merge Patch: objects merge, `null` removes a key, anything else replaces. */
        fun mergePatch(target: JsonElement?, patch: JsonElement): JsonElement {
            if (patch !is JsonObject) return patch
            val out = LinkedHashMap<String, JsonElement>()
            if (target is JsonObject) out.putAll(target)
            for ((k, v) in patch) {
                if (v is JsonNull) out.remove(k) else out[k] = mergePatch(out[k], v)
            }
            return JsonObject(out)
        }

        private fun isNumber(e: JsonElement) = e is JsonPrimitive && e !is JsonNull && !e.isString && e.booleanOrNull == null

        fun compare(got: JsonElement, want: JsonElement, tolerance: Double, where: String = "$"): List<String> {
            val gotBool = (got as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            val wantBool = (want as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            if (gotBool != null || wantBool != null) return if (gotBool == wantBool) emptyList() else listOf("$where: $got != $want")
            if (isNumber(got) && isNumber(want)) {
                val g = BigDecimal(got.jsonPrimitive.content); val w = BigDecimal(want.jsonPrimitive.content)
                val ok = g.compareTo(w) == 0 || Math.abs(g.toDouble() - w.toDouble()) <= tolerance
                return if (ok) emptyList() else listOf("$where: $got != $want (tolerance $tolerance)")
            }
            if (got is JsonObject && want is JsonObject) {
                if (got.keys != want.keys) return listOf("$where: keys ${got.keys.sorted()} != ${want.keys.sorted()}")
                return want.keys.flatMap { compare(got.getValue(it), want.getValue(it), tolerance, "$where.$it") }
            }
            if (got is JsonArray && want is JsonArray) {
                if (got.size != want.size) return listOf("$where: ${got.size} items != ${want.size}")
                return want.indices.flatMap { compare(got[it], want[it], tolerance, "$where[$it]") }
            }
            return if (got == want) emptyList() else listOf("$where: $got != $want")
        }
    }
}
