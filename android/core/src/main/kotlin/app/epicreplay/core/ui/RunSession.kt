package app.epicreplay.core.ui

import app.epicreplay.core.card.PlayCard
import app.epicreplay.core.run.FlowOrder
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.run.RunFlow
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the screens show of the run in hand: the progress state, the play sheet and the clip's facts when the run waits at the card, and whether the flow is running now. */
class RunView(val dir: File, val state: RunState, val playSheet: JsonObject?, val clipMeta: JsonObject?, val working: Boolean, val stoppedNote: String? = null) {
    /** The run stopped because the voice has no key: the user can add one and go on from there. */
    val needsVoiceKey: Boolean get() = stoppedNote?.contains("key is not set") == true

    val runId: String get() = dir.name
    val atCard: Boolean get() = state.status == "waiting_play_card" && playSheet != null && clipMeta != null
}

/**
 * The run in hand on the phone (spec 004 US4, T425): makes a run folder from a clip and the start screen's choices, runs the flow in [scope], shows its manifest as it changes,
 * stops at the play card, applies the user's answer and goes on, and picks a run up again after the app was closed (the manifest says where it was). One flow runs at a time.
 * [flowFor] makes the flow of a run folder; [words] are the page's words; [typical] the median seconds of each stage on this phone.
 */
class RunSession(private val runsDir: File, private val words: PageWords, private val scope: CoroutineScope, private val flowFor: (File) -> RunFlow, private val typical: () -> Map<String, Double> = { emptyMap() }, private val pollMs: Long = 300) {
    private val _view = MutableStateFlow<RunView?>(null)
    val view: StateFlow<RunView?> = _view
    private var job: Job? = null
    private val jobs = ArrayList<Job>() // every flow started and not yet ended, cancelled ones still unwinding included: a new flow waits for all of them

    private fun newId(): String = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()).let { "r_${it}_" + ByteArray(3).also(SecureRandom()::nextBytes).joinToString("") { b -> "%02x".format(b) } }

    /** A new run from [upload] (its extension kept, as the PC's `upload.<ext>`) and the user context; the manifest exists from this moment. */
    fun create(upload: File, userContext: JsonObject): File {
        val dir = File(runsDir, newId()).apply { mkdirs() }
        upload.copyTo(File(dir, "upload." + upload.extension.lowercase().ifEmpty { "mp4" }), overwrite = true)
        RunFiles.write(File(dir, "user_context.json"), userContext)
        flowFor(dir).save()
        refresh(dir)
        return dir
    }

    /** What the run folder shows now. */
    fun refresh(dir: File) {
        val manifest = RunFiles.read(File(dir, RunFlow.MANIFEST)) ?: return
        val st = RunState.of(manifest, typical(), words, File(dir, "clip_meta.json").exists(), File(dir, "final.mp4").exists())
        _view.value = RunView(dir, st, RunFiles.read(File(dir, "play_sheet.json")).takeIf { st.status == "waiting_play_card" }, RunFiles.read(File(dir, "clip_meta.json")), job?.isActive == true,
            manifest.getValue("stages").jsonArray.map { it.jsonObject }.firstOrNull { it.getValue("status").jsonPrimitive.content in setOf("failed", "stopped") }?.get("note")?.jsonPrimitive?.content)
    }

    /** Runs (or goes on with) the flow of [dir] and keeps the view up to date until it ends. [seeded] are stages whose outputs were put there on purpose (a fixture). */
    fun start(dir: File, seeded: Set<String> = emptySet()) {
        synchronized(jobs) {
            jobs.removeAll { it.isCompleted } // a cancelled job is not active but is still unwinding until it is completed
            val before = jobs.toList()
            val j = scope.launch {
                before.forEach { it.join() } // one flow at a time: a start while another ends (the card's answer just after the flow returned) goes on when it has
                val flow = flowFor(dir)
                val poll = launch { while (isActive) { refresh(dir); delay(pollMs) } }
                try { flow.run(seeded) } catch (e: CancellationException) { throw e } finally { poll.cancel() }
            }
            j.invokeOnCompletion { refresh(dir) } // after the flow's end, with the job no longer active: the screen stops showing it as working
            jobs += j; job = j
        }
        refresh(dir)
    }

    /** The user closed the screen or the app is going: the flow stops where it is; the run folder keeps what was done, and [start] goes on from there. */
    fun pause() { synchronized(jobs) { jobs.forEach { it.cancel() }; job = null } }

    /** The play card's answer: the key moment and the edits go into the play sheet and the context, and the run goes on. Throws [IllegalArgumentException] with the reason if the answer is refused. */
    fun answer(dir: File, input: CardAnswerInput) {
        val ps = RunFiles.read(File(dir, "play_sheet.json")) ?: error("no play sheet")
        val uc = RunFiles.read(File(dir, "user_context.json")) ?: error("no user context")
        val meta = RunFiles.read(File(dir, "clip_meta.json")) ?: error("no clip meta")
        val r = PlayCard.answer(ps, uc, meta, input.frame, input.edits, JsonObject(emptyMap()), input.generic)
        RunFiles.write(File(dir, "play_sheet.json"), r.playSheet)
        RunFiles.write(File(dir, "user_context.json"), r.userContext)
        start(dir)
    }

    /** "Is everyone in it fine?" answered yes: the sheet is no longer unchecked. */
    fun markFine(dir: File) {
        val ps = RunFiles.read(File(dir, "play_sheet.json")) ?: return
        RunFiles.write(File(dir, "play_sheet.json"), JsonObject(LinkedHashMap(ps).also { it["sensitive"] = JsonObject(linkedMapOf("flag" to kotlinx.serialization.json.JsonPrimitive(false), "reason" to kotlinx.serialization.json.JsonNull, "checked" to kotlinx.serialization.json.JsonPrimitive(true))) }))
        refresh(dir)
    }

    /** "Stop here" at the card, or "No, stop here" to the question about the people in the clip: the run ends `stopped` with [note]. */
    fun stopAtCard(dir: File, note: String) { flowFor(dir).stopAt(FlowOrder.CHECKPOINT_A, note); refresh(dir) }

    /** Every run that is not finished (running, waiting at the card, or broken and not dismissed), newest first. */
    fun openRuns(): List<File> = runsDir.listFiles { f -> f.isDirectory && File(f, RunFlow.MANIFEST).isFile && !File(f, DISMISSED).exists() }.orEmpty().sortedByDescending { it.name }
        .filter { RunFiles.read(File(it, RunFlow.MANIFEST))?.getValue("status")?.jsonPrimitive?.content in setOf("running", "waiting_play_card", "failed") }

    /** The newest run that is not finished, for the app to pick up when it is opened again: a broken one shows its message and can be tried again. */
    fun latestOpen(): File? = openRuns().firstOrNull()

    /** The run in hand was deleted: nothing is shown of it any more. */
    fun forget(dir: File) { if (_view.value?.dir == dir) { pause(); _view.value = null } }

    /** Whether the flow of [dir] is working now, so a delete waits. */
    fun isWorking(dir: File): Boolean = _view.value?.let { it.dir == dir && (it.working || synchronized(jobs) { jobs.any { j -> !j.isCompleted } }) } == true // a cancelled flow still unwinding counts

    /** The user is done with a run that broke (they chose another clip): the app does not open it again. The folder and its files stay. */
    fun dismiss(dir: File) { File(dir, DISMISSED).writeText("") }

    companion object {
        const val DISMISSED = ".dismissed"
        const val USER_STOP = "you said someone in it may not be fine"
        const val REJECTED = "stopped at the play card"

        /** The median duration of each stage over the runs in [runsDir], for the time left (`web.app.typical_s`). */
        fun typicalOf(runsDir: File): Map<String, Double> {
            val seen = HashMap<String, MutableList<Double>>()
            for (m in runsDir.listFiles { f -> f.isDirectory }.orEmpty()) {
                val stages = RunFiles.read(File(m, RunFlow.MANIFEST))?.get("stages")?.jsonArray ?: continue
                for (s in stages.map { it.jsonObject }) {
                    val stage = s.getValue("stage").jsonPrimitive.content
                    if (s.getValue("status").jsonPrimitive.content in setOf("ok", "degraded") && stage != FlowOrder.CHECKPOINT_A) seen.getOrPut(stage) { ArrayList() } += s.getValue("duration_s").jsonPrimitive.content.toDouble()
                }
            }
            return seen.mapValues { (_, v) -> v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 } }
        }
    }
}
