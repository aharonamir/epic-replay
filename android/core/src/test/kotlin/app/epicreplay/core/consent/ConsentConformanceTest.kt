package app.epicreplay.core.consent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The shared conformance area `consent` (spec 004, T411), run on the Kotlin port of `guards/consent.py`: the words for
 * each of the 16 pairs of places, and whether a stored consent covers a run. The expected outputs were written by the
 * Python code and are never changed to make this pass.
 */
class ConsentConformanceTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val copy = ConsentCopy(File(shared, "copy/consent.json").readText())
    private val cases = File(shared, "conformance/consent").listFiles { f -> File(f, "case.json").isFile }!!.sortedBy { it.name }

    private fun load(dir: File) = Json.parseToJsonElement(File(dir, "case.json").readText()).jsonObject
    private fun input(case: JsonObject, name: String) = case.getValue("inputs").jsonObject.getValue(name).jsonObject.getValue("value")

    @Test
    fun everyBlocksCaseGivesTheWordsPythonGives() {
        val blocks = cases.filter { load(it).getValue("function").jsonPrimitive.content == "consent.blocks" }
        assertEquals(16, blocks.size)
        for (dir in blocks) {
            val c = load(dir)
            val got = copy.blocks(input(c, "vision_location").jsonPrimitive.content, input(c, "vision_label").jsonPrimitive.content,
                input(c, "text_location").jsonPrimitive.content, input(c, "text_label").jsonPrimitive.content)
            val want = c.getValue("expected").jsonObject.getValue("value").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(want, got, dir.name)
        }
    }

    @Test
    fun everyCoversCaseGivesTheAnswersPythonGives() {
        val covers = cases.filter { load(it).getValue("function").jsonPrimitive.content == "consent.covers" }
        assertEquals(2, covers.size)
        var n = 0
        for (dir in covers) {
            val c = load(dir)
            val checks = input(c, "checks").jsonArray
            val want = c.getValue("expected").let { e ->
                (e.jsonObject["value"] ?: Json.parseToJsonElement(File(dir, e.jsonObject.getValue("file").jsonPrimitive.content).readText())).jsonArray
            }
            assertEquals(checks.size, want.size)
            for ((i, check) in checks.withIndex()) {
                val o = check.jsonObject
                val got = copy.covers(o.getValue("record").jsonObject, o.getValue("vision_location").jsonPrimitive.content, o.getValue("text_location").jsonPrimitive.content)
                assertEquals(want[i].jsonPrimitive.boolean, got, "${dir.name}[$i] $o")
                n++
            }
        }
        assertTrue(n >= 264, "$n checks")
    }

    @Test
    fun theVersionAndTheConsentTextAreTheSharedOnes() {
        assertEquals("4", copy.textVersion)
        assertTrue(copy.consentText.startsWith("I filmed this or have permission to use it."))
    }

    @Test
    fun aRecordIsWrittenInThePcsShape() {
        val r = ConsentRecord("2026-11-20T09:00:00Z", "4", "device", "cloud").toJson()
        assertEquals(setOf("confirmed", "at", "text_version", "vision_location", "text_location"), r.keys)
        assertTrue(copy.covers(r, "device", "cloud")); assertTrue(copy.covers(r, "device", "device"))
        assertTrue(!copy.covers(r, "cloud", "cloud") && !copy.covers(r, "network", "cloud"))
    }

    @Test
    fun aDamagedConsentFileIsNoConsent() {
        val f = File.createTempFile("consent", ".json")
        try {
            f.writeText("{not json")
            assertEquals(null, ConsentStore(f).load())
            ConsentStore(f).save(ConsentRecord("x", "4", "device", "device"))
            assertEquals("device", ConsentStore(f).load()!!.getValue("vision_location").jsonPrimitive.content)
            ConsentStore(f).clear(); assertEquals(null, ConsentStore(f).load())
        } finally { f.delete() }
    }
}
