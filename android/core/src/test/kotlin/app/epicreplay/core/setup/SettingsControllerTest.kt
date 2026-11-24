package app.epicreplay.core.setup

import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.consent.ConsentStore
import app.epicreplay.core.download.DownloadPolicy
import app.epicreplay.core.download.ModelDownloader
import app.epicreplay.core.models.CustomServerStore
import app.epicreplay.core.models.CustomServers
import app.epicreplay.core.models.LoadedProfiles
import app.epicreplay.core.models.ModelChoice
import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Platform
import app.epicreplay.core.models.ProfileLoader
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.secrets.AesGcmCipher
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.KeyStatus
import app.epicreplay.core.secrets.ModelChoiceStore
import app.epicreplay.core.secrets.SecretStore
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The settings page's logic (spec 004 FR-SET-1, FR-SET-2, FR-011, FR-CLOUD-1): the models on the phone with their sizes, a delete that clears the choice, and the server form that saves its key. */
class SettingsControllerTest {
    private val shared = File(System.getProperty("shared.dir"))
    private lateinit var tmp: File
    @BeforeTest fun setUp() { tmp = Files.createTempDirectory("settings").toFile() }
    @AfterTest fun tearDown() { tmp.deleteRecursively() }

    private val cloud: ServerProfile = ProfileLoader.loadDir(File(shared, "models"), File(shared, "contracts/model_profile.schema.json"), Platform.ANDROID).profiles.getValue("deepseek-flash") as ServerProfile
    private val phone = OnDeviceProfile(
        id = "tiny-device", label = "Tiny (on this phone)", roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "", engine = "llama.cpp",
        files = listOf(ModelFile("tiny.gguf", "https://huggingface.co/x/resolve/${"a".repeat(40)}/tiny.gguf", "0".repeat(64), 1000L, "weights")), contextTokens = 4096, minFreeRamMb = 1000, minFreeStorageMb = 10, license = "Apache-2.0",
    )
    private var profiles = LoadedProfiles(listOf(cloud, phone).associateBy { it.id }, emptyList())
    private val schema get() = File(shared, "contracts/model_profile.schema.json").readText()
    /** The shipped profiles, the small on-device one and the user's own server if saved: read again when the server is saved, as the app does. */
    private fun reload(store: CustomServerStore) {
        val shipped = File(shared, "models").listFiles { f -> f.name == "deepseek-flash.json" }!!.map { app.epicreplay.core.models.ProfileFile(it.name, it.readText()) }
        val loaded = ProfileLoader(schema, Platform.ANDROID).load(shipped + listOfNotNull(store.profileFile()))
        profiles = LoadedProfiles(loaded.profiles + (phone.id to phone), emptyList())
    }
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun secrets() = SecretStore(File(tmp, "secrets"), AesGcmCipher { aes })
    private val downloader get() = ModelDownloader(File(tmp, "models"), DownloadPolicy.DEFAULT, { 100_000 })
    private val choice by lazy { ModelChoice(ModelChoiceStore(File(tmp, "choice.json"))) { profiles } }
    private var busy = false
    private val settings get() = SettingsController({ profiles }, choice, downloader, { busy })

    @Test
    fun aModelWithFilesIsListedWithItsSizeAndWhoUsesIt_aPartCountsToo() {
        assertEquals(emptyList(), settings.installed().map { it.profile.id })
        File(tmp, "models").mkdirs(); File(tmp, "models/tiny.gguf.part").writeBytes(ByteArray(300))
        choice.choose(Role.TEXT, "tiny-device")
        val m = settings.installed().single()
        assertEquals(300L, m.bytes); assertFalse(m.complete); assertEquals(listOf(Role.TEXT), m.usedBy)
        File(tmp, "models/tiny.gguf").writeBytes(ByteArray(1000))
        assertEquals(1300L, settings.installed().single().bytes, "the finished file and the part are both on disk")
    }

    @Test
    fun deletingRemovesEveryFileAndClearsTheChoiceOfEachRoleThatUsedIt() {
        File(tmp, "models").mkdirs(); listOf("tiny.gguf", "tiny.gguf.part", "tiny.gguf.part.meta", "tiny.gguf.verified").forEach { File(tmp, "models/$it").writeText("x") }
        choice.choose(Role.VISION, "tiny-device"); choice.choose(Role.TEXT, "deepseek-flash")
        runBlocking { settings.delete(phone) }
        assertEquals(emptyList(), File(tmp, "models").list()!!.toList(), "no file of the model is left")
        assertNull(choice.chosen(Role.VISION), "the role that used it asks again"); assertEquals("deepseek-flash", choice.chosen(Role.TEXT)?.id, "another role's choice stays")
        assertEquals(emptyList(), settings.installed())
    }

    @Test
    fun aDeleteIsRefusedWhileADownloadOrARunIsWorkingAndChangesNothing() {
        File(tmp, "models").mkdirs(); File(tmp, "models/tiny.gguf").writeText("x"); choice.choose(Role.VISION, "tiny-device")
        busy = true
        assertFailsWith<ModelDeleteRefused> { runBlocking { settings.delete(phone) } }
        assertTrue(File(tmp, "models/tiny.gguf").isFile); assertEquals("tiny-device", choice.chosen(Role.VISION)?.id)
    }

    private fun setup(): SetupController {
        val store = CustomServerStore(File(tmp, "custom.json"))
        return SetupController({ profiles }, choice, secrets(), downloader, ConsentStore(File(tmp, "consent.json")), ConsentCopy(File(shared, "copy/consent.json").readText()), { "2026-11-24T10:00:00Z" }, store, { reload(store) })
    }
    private val server = CustomServers.of("Mine", "https://api.example.com/v1", "model-x", "0.4", "1.6", true)!!

    @Test
    fun theServerFormSavesItsKeyUnderTheServersOwnKindAndNeverTheProvidersKey() {
        val c = setup()
        assertEquals("Type the API key; it stays on this phone.", c.saveCustomServer(server, Role.TEXT, ""), "a new server with no key is refused, and nothing is saved")
        assertNull(c.customServer())
        assertNotNull(c.saveCustomServer(server, Role.TEXT, "short"), "a key that cannot be a key is refused with the reason")
        assertNull(c.customServer())
        assertNull(c.saveCustomServer(server, Role.TEXT, " sk-custom-0123456789 "))
        assertEquals("custom-cloud", c.chosen(Role.TEXT)?.id)
        assertEquals("sk-custom-0123456789", secrets().get(KeyKind.CUSTOM_SERVER)!!.reveal())
        assertEquals(KeyStatus.ABSENT, secrets().status(KeyKind.MODEL_PROVIDER))
    }

    @Test
    fun anEmptyKeyFieldOnAnEditKeepsTheSavedKey() {
        val c = setup()
        assertNull(c.saveCustomServer(server, Role.TEXT, "sk-custom-0123456789"))
        val edited = CustomServers.of("Mine", "https://api.other.example/v1", "model-y", "", "", false)!!
        assertNull(c.saveCustomServer(edited, Role.TEXT, ""))
        assertEquals("model-y", c.customServer()!!.model)
        assertEquals("sk-custom-0123456789", secrets().get(KeyKind.CUSTOM_SERVER)!!.reveal(), "the key stays")
        assertNull(c.saveCustomServer(edited, Role.VISION, "sk-replacement-9876543210"))
        assertEquals("sk-replacement-9876543210", secrets().get(KeyKind.CUSTOM_SERVER)!!.reveal())
    }
}
