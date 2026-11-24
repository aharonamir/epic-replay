package app.epicreplay.core.ui

import app.epicreplay.core.PyMath
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.run.RunFlow
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/** One finished video in the list of past replays. */
class PastItem(val runId: String, val createdAt: String, val headline: String, val style: String?, val zh: Boolean, val durationS: Double?, val costUsd: Double, val versions: Int) {
    val date: String get() = createdAt.take(10)
}

/** Why a delete did not happen. */
class DeleteRefused(message: String) : Exception(message)

/**
 * The past replays and their deletion (spec 004 FR-023, `web/views.past` and `web/app.delete_run` of the PC): every finished video in the runs folder, newest first, and a delete that
 * removes the clip and every version made from it: each folder of the play, with the upload, the working copy, the contact sheets, the card's frames and the outputs.
 */
object PastRuns {
    private const val GONE = ".deleting-"

    private fun read(d: File, name: String) = RunFiles.read(File(d, name))
    private fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** The id of the first run of the play [d] tells: the parent its user context names, else its own. */
    fun firstOf(d: File): String = str(read(d, "user_context.json"), "parent_run_id") ?: d.name

    private fun runs(root: File): List<File> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && File(f, RunFlow.MANIFEST).isFile }.orEmpty().sortedBy { it.name }

    /** Every run of the play [d] tells, the first run and its re-tellings, in a stable order (`runner.versions`). */
    fun versions(d: File): List<File> { val want = firstOf(d); return runs(d.parentFile).filter { firstOf(it) == want } }

    /** Finished videos, newest first. A folder on its way out (`.deleting-*`) or one that cannot be read is left out. [untitled] is the headline of a video with none. */
    fun list(root: File, untitled: String): List<PastItem> {
        val items = ArrayList<PastItem>()
        for (d in runs(root)) {
            val m = read(d, RunFlow.MANIFEST) ?: continue
            if (str(m, "status") != "done" || !File(d, "final.mp4").isFile) continue
            val created = str(m, "created_at") ?: continue
            val cost = (m["total_usd"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double ?: 0.0
            items += PastItem(d.name, created, str(read(d, "script.json"), "headline")?.takeIf { it.isNotBlank() } ?: untitled, str(m, "style"), str(m, "language") == "zh",
                (read(d, "render.json")?.get("duration_s") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double, PyMath.round(cost, 4), versions(d).size)
        }
        return items.sortedWith(compareByDescending<PastItem> { it.createdAt }.thenByDescending { it.runId })
    }

    /**
     * Remove the run [id] now, with every version of the same clip, which share its footage. Refused (nothing removed) when the id is not a run folder, or while a stage of any of them
     * is working ([busy] says so). The folders are renamed out of sight in one step and then removed; what a stop leaves is removed by [sweep] at the next start.
     */
    fun delete(root: File, id: String, busy: (File) -> Boolean): Int {
        if (id.isEmpty() || id.startsWith(".") || '/' in id || '\\' in id || id == "..") throw DeleteRefused("no such run")
        val d = File(root, id)
        if (!File(d, RunFlow.MANIFEST).isFile || d.canonicalFile.parentFile != root.canonicalFile) throw DeleteRefused("no such run")
        val family = versions(d)
        if (family.any { busy(it) }) throw DeleteRefused("Wait for this step to finish, then delete.")
        val gone = ArrayList<Pair<File, File>>()
        for (x in family) { // out of sight, all or none: a folder that cannot be moved puts the others back
            val to = File(root, GONE + x.name)
            if (x.renameTo(to)) gone += x to to else { gone.forEach { (from, moved) -> moved.renameTo(from) }; throw DeleteRefused("could not remove ${x.name}") }
        }
        gone.forEach { it.second.deleteRecursively() }
        return family.size
    }

    /** Remove what a delete left behind when the app stopped. */
    fun sweep(root: File) { root.listFiles { f -> f.name.startsWith(GONE) }.orEmpty().forEach { it.deleteRecursively() } }
}
