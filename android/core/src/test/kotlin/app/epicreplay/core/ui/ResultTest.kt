package app.epicreplay.core.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The result screen's facts (what the models were, what the fallback wrote: spec 004 FR-017, FR-018) and the past runs with their deletion (FR-023). */
class ResultTest {
    private val words = PageWords.load(File(System.getProperty("shared.dir")))
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun theModelsAReadFromTheManifestAndSayWhereEachRan() {
        val m = obj("""{"stack":{"models":{"vision":{"profile":"gemma","label":"Gemma on the phone","location":"local","model":"gemma.task"},"text":{"profile":"deepseek-flash","label":"DeepSeek Flash","location":"cloud","model":"deepseek-v4-flash"}}}}""")
        val rows = ResultModel.modelRows(m)
        assertEquals(listOf("Looking at the clip" to "Gemma on the phone, gemma.task, on this phone", "Writing the script" to "DeepSeek Flash, deepseek-v4-flash, at a cloud provider"), rows.map { it.name to it.value })
        assertEquals(emptyList(), ResultModel.modelRows(obj("""{"stack":{"mode":"fixture"}}""")), "a run from before the models were recorded names none")
    }

    @Test
    fun theFallbackIsNamedByItsCountAndRolesAndByTheStagesThatLeftCallsOut() {
        val script = obj("""{"lines":[{"id":"l1","role":"opening","text":"a","generic":false},{"id":"l2","role":"build_up","text":"b","generic":true},{"id":"l3","role":"climax","text":"c","generic":true}]}""")
        val panel = ResultModel.scriptPanel(script, null, null, null, words)
        val manifest = obj("""{"stages":[{"stage":"S2","status":"degraded"},{"stage":"S3","status":"ok"},{"stage":"S5","status":"degraded"}]}""")
        val note = ResultModel.fallbackNote(manifest, panel, words).lines
        assertEquals("2 of 3 lines were written by code, not by the model: build-up, key moment.", note[0])
        assertEquals(3, note.size, "one for the lines and one for each step whose stage was degraded")
        assertTrue(note.drop(1).all { "some calls left out" in it })
        assertEquals(emptyList(), ResultModel.fallbackNote(obj("""{"stages":[{"stage":"S5","status":"ok"}]}"""), ResultModel.scriptPanel(obj("""{"lines":[{"id":"l1","role":"opening","text":"a"}]}"""), null, null, null, words), words).lines)
    }

    private fun run(root: File, id: String, parent: String? = null, status: String = "done", video: Boolean = true, created: String = "2026-11-24T10:00:00Z", headline: String? = "Top corner") {
        val d = File(root, id).apply { mkdirs() }
        File(d, "manifest.json").writeText("""{"run_id":"$id","created_at":"$created","status":"$status","style":"football_commentator","language":"en","total_usd":0.0412,"stages":[]}""")
        File(d, "user_context.json").writeText(if (parent == null) "{}" else """{"parent_run_id":"$parent"}""")
        if (headline != null) File(d, "script.json").writeText("""{"headline":"$headline","lines":[]}""")
        File(d, "render.json").writeText("""{"duration_s":29.4}""")
        File(d, "upload.mp4").writeText("clip"); File(d, "work.mp4").writeText("work")
        if (video) File(d, "final.mp4").writeText("video")
    }

    @Test
    fun thePastListHasFinishedVideosNewestFirstAndLeavesOutTheRest() {
        val root = Files.createTempDirectory("runs").toFile()
        run(root, "r_a", created = "2026-11-20T10:00:00Z"); run(root, "r_b", created = "2026-11-24T10:00:00Z", headline = null)
        run(root, "r_running", status = "running", video = false); run(root, "r_novideo", video = false)
        File(root, ".deleting-r_x").mkdirs(); File(root, "r_broken").apply { mkdirs(); File(this, "manifest.json").writeText("not json") }
        val items = PastRuns.list(root, "Your replay")
        assertEquals(listOf("r_b", "r_a"), items.map { it.runId })
        assertEquals("Your replay", items[0].headline); assertEquals("Top corner", items[1].headline)
        assertEquals("2026-11-24", items[0].date); assertEquals(29.4, items[0].durationS); assertEquals(0.0412, items[0].costUsd)
    }

    @Test
    fun deletingARunRemovesItsFolderAndEveryVersionOfTheSameClip() {
        val root = Files.createTempDirectory("runs").toFile()
        run(root, "r_first"); run(root, "r_second", parent = "r_first"); run(root, "r_third", parent = "r_first"); run(root, "r_other")
        assertEquals(3, PastRuns.list(root, "x").first { it.runId == "r_third" }.versions)
        assertEquals(3, PastRuns.delete(root, "r_third") { false }, "asked through a later version, the whole play goes")
        assertEquals(listOf("r_other"), root.list()!!.sorted(), "nothing of the clip is left in storage: no folder, no upload, no working copy, no renamed leftover")
        assertTrue(File(root, "r_other/upload.mp4").isFile, "another clip is untouched")
    }

    @Test
    fun aDeleteIsRefusedWhileAStageWorksAndForAnythingThatIsNotARun() {
        val root = Files.createTempDirectory("runs").toFile()
        run(root, "r_first"); run(root, "r_second", parent = "r_first")
        assertFailsWith<DeleteRefused> { PastRuns.delete(root, "r_first") { it.name == "r_second" } }
        assertEquals(listOf("r_first", "r_second"), root.list()!!.sorted(), "refused means nothing moved")
        for (bad in listOf("", ".", "..", "../runs", "a/b", ".deleting-r_first", "nope")) assertFailsWith<DeleteRefused>(bad) { PastRuns.delete(root, bad) { false } }
        assertEquals(2, root.list()!!.size)
    }

    @Test
    fun whatAStoppedDeleteLeftBehindIsSweptAtTheNextStart() {
        val root = Files.createTempDirectory("runs").toFile()
        run(root, "r_a"); File(root, ".deleting-r_b/work").mkdirs(); File(root, ".deleting-r_b/work/x").writeText("x")
        PastRuns.sweep(root)
        assertEquals(listOf("r_a"), root.list()!!.toList())
        assertFalse(File(root, ".deleting-r_b").exists())
    }
}
