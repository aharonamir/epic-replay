package app.epicreplay.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.epicreplay.AppGraph
import app.epicreplay.RunLauncher
import app.epicreplay.core.card.PlayCard
import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.consent.ConsentRecord
import app.epicreplay.core.intake.Intake
import app.epicreplay.core.models.Role
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.style.PackFile
import app.epicreplay.core.style.StyleLoader
import app.epicreplay.core.ui.NarratorCard
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.RunSession
import app.epicreplay.core.ui.RunView
import app.epicreplay.core.ui.StartForm
import app.epicreplay.core.ui.StartInput
import app.epicreplay.media.ClipFrames
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The screens' state: the run in hand (it outlives a turn of the screen), the clip chosen on the start screen, and the words and narrators read from the shared data. */
@UnstableApi
class AppViewModel(private val graph: AppGraph, private val context: Context, launcher: RunLauncher? = null, runsDirOverride: File? = null) : ViewModel() {
    private fun text(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
    val words: PageWords = PageWords.parse(text("copy/page.json"))
    val consentCopy = ConsentCopy(text("copy/consent.json"))
    val styles = run {
        val files = context.assets.list("styles")!!.filter { it.endsWith(".json") && it != "voices.json" }.map { PackFile(it, text("styles/$it")) }
        StyleLoader(text("contracts/style_pack.schema.json"), Json.parseToJsonElement(text("styles/voices.json")) as JsonObject, { true }).load(files)
    }
    val cards: List<NarratorCard> = StartForm.narrators(styles)
    val launcher = launcher ?: RunLauncher(graph, context)
    val runsDir: File = (runsDirOverride ?: File(context.filesDir, "runs")).apply { mkdirs() }
    // the run's work (a copy of the clip, the scan of its frames, the video's export) never runs on the main thread: it would freeze the screen
    private val runScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    val session = RunSession(runsDir, words, runScope, { this.launcher.flow(it) }, { RunSession.typicalOf(runsDir) })
    private val _starting = MutableStateFlow(false)
    /** True from "Make my replay" until the run's folder exists and its manifest can be read. */
    val starting: StateFlow<Boolean> = _starting

    private val _clip = MutableStateFlow<Pair<File, String>?>(null)
    val clip: StateFlow<Pair<File, String>?> = _clip
    private val _clipProblem = MutableStateFlow<String?>(null)
    val clipProblem: StateFlow<String?> = _clipProblem
    private val _onStart = MutableStateFlow(session.latestOpen() == null)
    /** Whether the start screen is shown (else the run in hand). */
    val onStart: StateFlow<Boolean> = _onStart

    init { session.latestOpen()?.let { session.refresh(it); session.start(it) } }

    /** The clip the user picked or filmed: copied to the app's cache and checked as the PC checks an upload's name and size (S0 checks the rest). */
    suspend fun chooseClip(uri: Uri) = withContext(Dispatchers.IO) {
        var name = "clip.mp4"; var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val problem = Intake.checkName(name, size)
        if (problem != null) { _clip.value = null; _clipProblem.value = problem; return@withContext }
        val dest = File(context.cacheDir, "picked/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { parentFile!!.mkdirs() }
        context.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
        _clip.value = dest to name; _clipProblem.value = null
    }

    /** A clip that is already a file (a test's): chosen as a picked one is. */
    fun useClip(file: File, name: String) { _clip.value = file to name; _clipProblem.value = null }

    /** "Make my replay": the run is made from the clip and the choices, with the consent the tick gives, and started. */
    fun make(input: StartInput) {
        val (file, _) = _clip.value ?: return
        val models = launcher.models() ?: return
        val card = cards.first { it.id == input.narrator }
        val record = ConsentRecord(Instant.now().toString().substringBefore('.') + "Z", consentCopy.textVersion, models.vision.location, models.text.location).toJson()
        _onStart.value = false; _starting.value = true // the screen moves on at once; the copy of the clip and the first stages happen off the main thread
        runScope.launch {
            try {
                val dir = session.create(file, StartForm.userContext(input, card, record))
                session.start(dir)
            } finally { _starting.value = false }
        }
    }

    /** From the run in hand back to the start screen (the run, if unfinished, stays in its folder and is picked up when the app is opened). */
    fun another() { session.pause(); _clip.value = null; _clipProblem.value = null; _onStart.value = true }

    /** The line under the consent box: where the frames and the text go, from the models chosen (shared/copy/consent.json). */
    fun providerLine(): String = launcher.models()?.let { m -> consentCopy.blocks(m.vision.location, m.vision.label, m.text.location, m.text.label).joinToString(" ") } ?: ""

    /** The cost of the rest of the run for the card: the Commentator's ceiling plus the voice (`PlayCard.restCostUsd`). */
    fun restCost(view: RunView): Double {
        val uc = RunFiles.read(File(view.dir, "user_context.json")) ?: return 0.0
        val pack = app.epicreplay.core.run.FlowStages.pack(styles, uc)
        return PlayCard.restCostUsd(view.clipMeta!!, pack, launcher.models()?.text)
    }

    /** The card's pictures: frames of the run's working copy by number; closed when the card leaves the screen. */
    class ClipFrameSource(private val path: String) : FrameSource, AutoCloseable {
        private val clip by lazy { ClipFrames(path) } // opened on first use, off the main thread
        override suspend fun frame(n: Int): ImageBitmap? = withContext(Dispatchers.IO) { opened = true; clip.frameAt(n)?.asImageBitmap() }
        private var opened = false
        override fun close() { if (opened) clip.close() }
    }

    fun frames(dir: File): ClipFrameSource = ClipFrameSource(File(dir, "work.mp4").path)

    fun accent(view: RunView?): Accent {
        val id = view?.state?.style ?: cards.first().id
        val pack = styles.packs[id] ?: return DefaultAccent
        val g = pack["graphics"] as? JsonObject
        return accentOf((g?.get("accent") as kotlinx.serialization.json.JsonPrimitive).content, (g["accent_text"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    override fun onCleared() { session.pause(); runScope.cancel() }
}
