package app.epicreplay

import android.content.Context
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.download.DownloadPolicy
import app.epicreplay.core.download.DownloadStatus
import app.epicreplay.core.download.DownloadTracker
import app.epicreplay.core.download.ModelDownloader
import app.epicreplay.core.budget.JsonFileLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.consent.ConsentStore
import app.epicreplay.core.models.CloudBackend
import app.epicreplay.core.models.CustomServerStore
import app.epicreplay.core.models.CustomServers
import app.epicreplay.core.models.LoadedProfiles
import app.epicreplay.core.models.ModelChoice
import app.epicreplay.core.models.Platform
import app.epicreplay.core.models.ProfileFile
import app.epicreplay.core.models.ProfileLoader
import app.epicreplay.core.setup.SetupController
import app.epicreplay.core.models.OnDeviceBackend
import app.epicreplay.core.models.OnDeviceConfig
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.net.JavaHttpTransport
import app.epicreplay.core.net.RedactingTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import app.epicreplay.core.secrets.AesGcmCipher
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.KeysController
import app.epicreplay.core.secrets.ModelChoiceStore
import app.epicreplay.core.secrets.SecretStore
import app.epicreplay.engine.AndroidMemoryProbe
import app.epicreplay.engine.AndroidNetworkInfo
import app.epicreplay.engine.AndroidStorageProbe
import app.epicreplay.engine.NativeEngine
import app.epicreplay.net.BitmapImageResizer
import app.epicreplay.secrets.AndroidKeystoreKey
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate

/** The app's long-lived objects. The key files live in `files/secrets/`, which the backup rules exclude. */
class AppGraph(context: Context) {
    private val root: File = context.filesDir
    val secrets = SecretStore(File(root, SECRETS_DIR), AesGcmCipher { AndroidKeystoreKey.get() })
    val keys = KeysController(secrets)
    val modelChoice = ModelChoiceStore(File(root, "settings/model_choice.json"))

    /** One cost ledger for the whole app: every run and every paid call, model or voice, shares the caps (T408). */
    val ledger = Ledger(
        JsonFileLedgerStorage(File(root, "ledger/ledger.json")),
        Caps(),
        today = { LocalDate.now(ZoneOffset.UTC).toString() },
        now = { Instant.now().toString() },
    )

    /** Recordings of model replies, for repeating a run without a paid call (`record` and `replay` modes). */
    fun recorder(mode: ProviderMode) = Recorder(mode, File(root, "recordings"))

    /** The client for a cloud [profile] in [role]. A failure never carries the key. */
    fun cloudBackend(profile: ServerProfile, role: Role, transport: HttpTransport = JavaHttpTransport()): CloudBackend {
        val key = { secrets.get(SetupController.keyFor(profile)) } // the user's own server has a key of its own: a key typed for one server is never sent to another
        return CloudBackend(profile, role, RedactingTransport(transport) { listOf(key()) }, key, BitmapImageResizer())
    }

    private val appContext = context.applicationContext

    /** The model files of on-device profiles live here (T410 downloads them). */
    val modelDir: File = File(root, "models")

    /** The backend for an on-device [profile] in [role]: the JNI engine, refusing before it loads when memory is short. */
    fun onDeviceBackend(profile: OnDeviceProfile, role: Role, config: OnDeviceConfig = OnDeviceConfig()): OnDeviceBackend =
        OnDeviceBackend(profile, role, modelDir, AndroidMemoryProbe(appContext), { NativeEngine() }, config)

    /** Downloads, resumes and deletes the files of on-device profiles in [modelDir] (HTTPS, allowed hosts, checksum). */
    fun modelDownloader(): ModelDownloader = ModelDownloader(modelDir, DownloadPolicy.DEFAULT, AndroidStorageProbe(modelDir))

    /** The one download in progress, shared by the screen, the notification and the service. */
    val downloads = DownloadTracker { profile, progress -> modelDownloader().download(profile, progress) }

    /** Whether the connection is unmetered, for the download's Wi-Fi hint. */
    fun isUnmetered(): Boolean = AndroidNetworkInfo(appContext).isUnmetered()

    private fun asset(path: String) = appContext.assets.open(path).bufferedReader().use { it.readText() }

    /** The profiles packaged from `shared/models/`, minus what Android cannot run (spec 004 FR-005). */
    val customServers = CustomServerStore(File(root, "settings/${CustomServers.FILE}"))
    private val profilesLock = Any()
    private var loadedProfiles: LoadedProfiles? = null
    private fun loadProfiles(): LoadedProfiles {
        val files = appContext.assets.list("models")!!.filter { it.endsWith(".json") }.map { ProfileFile(it, asset("models/$it")) } + listOfNotNull(customServers.profileFile())
        return ProfileLoader(asset("contracts/model_profile.schema.json"), Platform.ANDROID).load(files)
    }
    /** The shipped profiles and the user's own server (spec 004 FR-CUSTOM-1); read again after the server is saved. */
    val profiles: LoadedProfiles get() = synchronized(profilesLock) { loadedProfiles ?: loadProfiles().also { loadedProfiles = it } }
    fun reloadProfiles() { synchronized(profilesLock) { loadedProfiles = null } }

    /** The first-run and settings logic: the choice for each role, what is missing before a run, the consent. */
    val setup: SetupController by lazy {
        SetupController(
            { profiles }, ModelChoice(modelChoice, { profiles }), secrets, modelDownloader(),
            ConsentStore(File(root, "settings/consent.json")), ConsentCopy(asset("copy/consent.json")),
            { Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString() },
            customServers, ::reloadProfiles,
        )
    }

    /** The backend for the profile chosen for [role], for whatever place it runs. */
    fun backendFor(profile: app.epicreplay.core.models.Profile, role: Role): app.epicreplay.core.models.ModelBackend = when (profile) {
        is ServerProfile -> cloudBackend(profile, role)
        is OnDeviceProfile -> onDeviceBackend(profile, role)
    }

    companion object {
        const val SECRETS_DIR = "secrets"
    }
}
