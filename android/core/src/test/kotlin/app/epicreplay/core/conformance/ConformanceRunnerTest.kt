package app.epicreplay.core.conformance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** T412: the runner over `shared/conformance/` and the areas it ports (lexicon scan, spoken units, cells). */
class ConformanceRunnerTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val runner = ConformanceRunner(shared, TextFunctions.table(shared))
    private val all = runner.cases()

    /** Functions checked by their own tests (T408, T411) and functions a later task ports (T413 to T419). */
    private val checkedElsewhere = setOf("backend.requests", "backend.recording_key", "budget.run", "consent.blocks", "consent.covers")
    private val laterTasks = setOf("storyboard.build", "slots.build", "checks.check", "checks.text")

    @Test
    fun everyFunctionOfEveryCaseIsPortedHereCheckedElsewhereOrWaitingForItsTask() {
        val names = all.map { runner.function(it) }.toSet()
        val known = TextFunctions.table(shared).keys + checkedElsewhere + laterTasks
        assertEquals(emptySet(), names - known, "a case names a function nobody accounts for")
        assertEquals(emptySet(), (checkedElsewhere + laterTasks) - names, "an accounted-for function has no case any more")
    }

    @Test
    fun everyPortedCasePassesAndEveryAreaIsCovered() {
        val ported = all.filter { runner.isPorted(it) }
        assertEquals(32, ported.size, "the cases ported so far: every lexicon.scan, units, cells.narrow and cells.layout case")
        val failures = ported.mapNotNull { dir -> runner.check(dir).takeIf { it.isNotEmpty() }?.let { "${dir.parentFile.name}/${dir.name}: ${it.take(3)}" } }
        assertEquals(emptyList<String>(), failures)
        val byFunction = ported.groupBy { runner.function(it) }.mapValues { it.value.size }
        assertTrue(byFunction.getValue("lexicon.scan") >= 15 && byFunction.getValue("units.spoken_units") >= 5, "$byFunction")
        assertTrue(byFunction.getValue("cells.narrow") == 5 && byFunction.getValue("cells.layout") == 5, "$byFunction")
    }

    @Test
    fun aCaseWhoseFunctionIsNotPortedIsNamedNotSkipped() {
        val pending = all.first { runner.function(it) == "storyboard.build" }
        assertEquals("storyboard.build", assertFailsWith<NotPorted> { runner.run(pending) }.function)
    }

    private fun copyOf(case: String, edit: (String) -> String): ConformanceRunner {
        val src = all.first { "${it.parentFile.name}/${it.name}" == case }
        val tmp = Files.createTempDirectory("conf").toFile()
        val dir = File(tmp, "conformance/${src.parentFile.name}/${src.name}").also { it.mkdirs() }
        src.copyRecursively(dir, overwrite = true)
        File(dir, "case.json").let { it.writeText(edit(it.readText())) }
        return ConformanceRunner(tmp, TextFunctions.table(shared))
    }

    @Test
    fun aHandEditedExpectedNumberFailsTheRunnerWithItsPath() {
        // one number of the first sheet changed by one
        val edited = copyOf("cells/three_passes") { text ->
            val from = text.indexOf("\"expected\"")
            text.substring(0, from) + text.substring(from).replaceFirst("225,", "226,")
        }
        val diffs = edited.check(edited.cases().single())
        assertEquals(1, diffs.size, diffs.toString())
        assertEquals("$[0][4]: 225 != 226 (tolerance 0.0)", diffs.single())
    }

    @Test
    fun aHandEditedExpectedWordFailsAndAnEditedKeyListFails() {
        val word = copyOf("lexicon/en_found") { it.replace("expected/output.json", "expected/output.json") }
        val dir = word.cases().single()
        val out = File(dir, "expected/output.json")
        out.writeText(out.readText().replaceFirst("\"she\"", "\"he\""))
        assertTrue(word.check(dir).isNotEmpty())
        val keys = copyOf("cells/layout_landscape_1080p") { it.replace("\"rows\": 3", "\"lines\": 3") }
        assertTrue(keys.check(keys.cases().single()).single().startsWith("$: keys"))
    }

    @Test
    fun aNumberWithinTheToleranceHoldsAndOutsideItFailsAndABooleanIsNeverANumber() {
        fun diff(got: String, want: String, tol: Double) =
            ConformanceRunner.compare(Json.parseToJsonElement(got), Json.parseToJsonElement(want), tol)
        assertEquals(emptyList(), diff("""{"t": 1.0004}""", """{"t": 1.0}""", 0.001))
        assertTrue(diff("""{"t": 1.002}""", """{"t": 1.0}""", 0.001).isNotEmpty())
        assertTrue(diff("""{"t": true}""", """{"t": 1}""", 0.5).isNotEmpty())
        assertTrue(diff("""{"t": 1}""", """{"t": true}""", 0.5).isNotEmpty())
        assertTrue(diff("[1, 2]", "[1, 2, 3]", 0.0).single().contains("2 items != 3"))
        assertEquals(emptyList(), diff("""{"a": 1, "b": [2]}""", """{"b": [2], "a": 1}""", 0.0)) // key order does not matter
        assertTrue(diff("\"1\"", "1", 0.0).isNotEmpty()) // a string is not a number
        assertEquals(emptyList(), diff("null", "null", 0.0))
    }

    @Test
    fun theMergePatchFollowsRfc7386() {
        fun patch(target: String, p: String) =
            ConformanceRunner.mergePatch(Json.parseToJsonElement(target), Json.parseToJsonElement(p)).toString()
        assertEquals("""{"a":"z","c":{"d":"e"}}""", patch("""{"a":"b","c":{"d":"e","f":"g"}}""", """{"a":"z","c":{"f":null}}"""))
        assertEquals("""{"a":[1]}""", patch("""{"a":[{"b":"c"}]}""", """{"a":[1]}""")) // lists are replaced
        assertEquals("""{"a":"c"}""", patch("""{"a":"b"}""", """{"a":"c"}"""))
        assertEquals("""["c"]""", patch("""{"a":"foo"}""", """["c"]"""))
        assertEquals("""{"a":"foo"}""", patch("""{"a":"foo"}""", """{"b":null}"""))
        assertEquals("""{"a":{"bb":{}}}""", patch("""{}""", """{"a":{"bb":{"ccc":null}}}"""))
    }

    @Test
    fun aFileReferenceReadsSliceFieldAndFixtureAsThePythonRunnerDoes() {
        val case = all.first { it.name == "zh_sentences_2" }
        val args = runner.load(case).getValue("inputs").jsonObject
        val texts = runner.resolve(args.getValue("texts").jsonObject, case).jsonArray
        assertEquals(25, texts.size)
        val all = runner.resolve(kotlinx.serialization.json.buildJsonObject { put("fixture", JsonPrimitive("zh/zh_sentences.json")); put("field", JsonPrimitive("zh")) }, case).jsonArray
        assertEquals(texts.toList(), all.subList(25, 50).toList())
        assertEquals(JsonArray(emptyList()), runner.resolve(kotlinx.serialization.json.buildJsonObject {
            put("fixture", JsonPrimitive("zh/zh_sentences.json")); put("slice", JsonArray(listOf(JsonPrimitive(5000), JsonPrimitive(6000))))
        }, case)) // a slice past the end is empty, as in Python
        assertFailsWith<UnsupportedReference> {
            runner.resolve(kotlinx.serialization.json.buildJsonObject { put("style", JsonPrimitive("news_reporter")) }, case)
        }
        Unit
    }
}
