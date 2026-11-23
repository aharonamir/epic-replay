package app.epicreplay

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.commentator.Commentator
import app.epicreplay.core.commentator.CommentatorRun
import app.epicreplay.core.commentator.CommentatorWords
import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.mix.MixRules
import app.epicreplay.core.models.ModelChoice
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.run.FlowStages
import app.epicreplay.core.run.RunFiles
import app.epicreplay.core.run.RunFlow
import app.epicreplay.core.run.RunStack
import app.epicreplay.core.setup.ModelsUsed
import app.epicreplay.core.spotter.Spotter
import app.epicreplay.core.spotter.SpotterRun
import app.epicreplay.core.spotter.SpotterWords
import app.epicreplay.core.spotter.VisionAdapter
import app.epicreplay.core.style.PackFile
import app.epicreplay.core.style.StyleLoader
import app.epicreplay.core.text.EnglishLexicon
import app.epicreplay.core.voice.ElevenLabs
import app.epicreplay.core.voice.Speech
import app.epicreplay.core.voice.SpeechAdapter
import app.epicreplay.media.AndroidAudioConverter
import app.epicreplay.media.AndroidMixAudio
import app.epicreplay.media.ClipSheetRenderer
import app.epicreplay.media.OverlayFonts
import app.epicreplay.media.PhoneEnv
import app.epicreplay.media.PhoneStages
import java.io.File
import kotlinx.serialization.json.JsonObject

/**
 * Makes the flow of one run from the app's parts (the choice of models, the keys, the ledger, the shared data): the stages of [PhoneStages] over the backends chosen
 * for the vision and text roles, the ElevenLabs voice with the user's key, and the manifest's record of which models the run started with (FR-006, FR-017).
 * [mode] is the provider mode: `LIVE` calls the providers, `RECORD` also keeps the answers, `REPLAY` answers only from recordings (no network).
 */
@UnstableApi
class RunLauncher(private val graph: AppGraph, private val context: Context, private val mode: ProviderMode = ProviderMode.LIVE, private val recordings: File? = null, private val chosen: ModelsUsed? = null,
    /** The backend for a profile (the app's own by default; a test gives scripted ones). */
    private val backendFor: (Profile, Role) -> app.epicreplay.core.models.ModelBackend = graph::backendFor,
    /** The voice (ElevenLabs with the user's key by default; a test gives a fake). */
    private val speechFor: ((File) -> Speech)? = null,
    /** The ledger of the caps (the app's own by default). */
    private val ledger: app.epicreplay.core.budget.Ledger = graph.ledger,
) {
    private val assets = context.assets
    private fun text(path: String) = assets.open(path).bufferedReader().use { it.readText() }
    private val shared: File by lazy { SharedFiles.extract(assets, File(context.filesDir, "shared"), context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode) }
    private val styles by lazy {
        val files = assets.list("styles")!!.filter { it.endsWith(".json") && it != "voices.json" }.map { PackFile(it, text("styles/$it")) }
        StyleLoader(text("contracts/style_pack.schema.json"), kotlinx.serialization.json.Json.parseToJsonElement(text("styles/voices.json")) as JsonObject, { true }).load(files)
    }
    private val recorder get() = if (recordings != null) app.epicreplay.core.record.Recorder(mode, recordings) else graph.recorder(mode)
    private val choice get() = ModelChoice(graph.modelChoice, { graph.profiles })

    /** The models the next run would use ([chosen] when given: a test), or null when a role has none chosen. */
    fun models(): ModelsUsed? = chosen ?: choice.let { c -> c.chosen(Role.VISION)?.let { v -> c.chosen(Role.TEXT)?.let { t -> ModelsUsed(v, t) } } }

    private fun modelName(p: Profile) = when (p) { is ServerProfile -> p.model; else -> p.id }

    /** Whether a user context's consent covers the models the run goes to. */
    fun consentCovers(uc: JsonObject, models: ModelsUsed?): Boolean = models != null && ConsentCopy(text("copy/consent.json")).covers((uc["consent"] as? JsonObject) ?: JsonObject(emptyMap()), models.vision.location, models.text.location)

    /** The flow of the run in [dir]: over the models the run started with when it has a manifest (FR-006), else over the models chosen now. */
    fun flow(dir: File, apiKey: () -> app.epicreplay.core.secrets.Secret? = { graph.secrets.get(app.epicreplay.core.secrets.KeyKind.ELEVENLABS) }): RunFlow {
        val root = shared
        val saved = RunFiles.read(File(dir, RunFlow.MANIFEST))
        val models = chosen ?: (if (saved != null) RunStack.saved(saved, graph.profiles.profiles) ?: throw IllegalStateException("the models this run started with are no longer offered") else models())
        val words = CommentatorWords.load(root)
        val checks = Checks.fromShared(root)
        val scriptSchema = File(root, "contracts/script.schema.json").readText()
        val commentator = Commentator(words, checks, scriptSchema)
        val spotter = Spotter(EnglishLexicon(File(root, "lexicons/en.json").readText()), File(root, "contracts/play_sheet.schema.json").readText())
        val spotterWords = SpotterWords.load(root)
        val rules = MixRules.parse(File(root, "mix/rules.json").readText())
        val env = PhoneEnv(
            context = context, styles = styles, checks = checks, commentator = commentator, scriptSchema = scriptSchema, mixRules = rules, spotter = spotter,
            fonts = OverlayFonts.load(assets), models = models,
            visionModel = { models?.vision?.let(::modelName) ?: "none" },
            spotterRun = { run ->
                val v = models?.vision ?: return@PhoneEnv null
                val estimate = (v as? ServerProfile)?.callCeilingUsd ?: 0.0
                val meta = app.epicreplay.core.run.RunFiles.read(File(run, "clip_meta.json"))!!
                val renderer = ClipSheetRenderer(File(run, "work.mp4").path, (meta["fps"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 30.0)
                FlowStages.SpotterHandle(SpotterRun(spotter, VisionAdapter(backendFor(v, Role.VISION), modelName(v), spotterWords.retryLine, estimate, ledger, recorder, run.name), spotterWords, renderer), renderer)
            },
            commentatorLoop = { run -> models?.text?.let { t -> CommentatorRun(commentator, backendFor(t, Role.TEXT), words, modelName(t), ledger, recorder, run.name) } },
            speech = { speechFor?.invoke(dir) ?: voice(dir, apiKey) },
        )
        return RunFlow(
            dir, PhoneStages.all(env) { run -> AndroidMixAudio(assets, run, rules.sampleRate) }, { uc -> consentCovers(uc, models) }, PhoneStages.stack(models, mode.name.lowercase()),
            spentUsd = { ledger.runTotal(dir.name) },
        )
    }

    private fun voice(dir: File, apiKey: () -> app.epicreplay.core.secrets.Secret?): Speech = SpeechAdapter(
        app.epicreplay.core.net.JavaHttpTransport(), apiKey, ELEVEN_MODEL, AndroidAudioConverter(context.cacheDir), ledger, recorder, dir.name,
    )

    companion object { const val ELEVEN_MODEL = "eleven_v3" }
}
