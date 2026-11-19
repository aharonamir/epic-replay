package app.epicreplay.core.setup

import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.consent.ConsentStore
import app.epicreplay.core.download.ModelDownloader
import app.epicreplay.core.download.DownloadPolicy
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
import app.epicreplay.core.secrets.ModelChoiceStore
import app.epicreplay.core.secrets.Secret
import app.epicreplay.core.secrets.SecretStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The first-run stories of spec 004 (US1 and US3) against the real controller, with the shipped cloud profile and a small on-device one. */
class SetupControllerTest {
    private val shared = File(System.getProperty("shared.dir"))
    private lateinit var tmp: File
    private val bytes = ByteArray(2_000_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private var clock = "2026-11-20T09:00:00Z"

    @BeforeTest fun setUp() { tmp = Files.createTempDirectory("setup").toFile() }
    @AfterTest fun tearDown() { tmp.deleteRecursively() }

    private val cloud: ServerProfile = ProfileLoader.loadDir(File(shared, "models"), File(shared, "contracts/model_profile.schema.json"), Platform.ANDROID)
        .profiles.getValue("deepseek-flash") as ServerProfile

    private val phone = OnDeviceProfile(
        id = "tiny-device", label = "Tiny (on this phone)", roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "",
        engine = "llama.cpp", files = listOf(ModelFile("tiny.gguf", "https://huggingface.co/x/resolve/${"a".repeat(40)}/tiny.gguf", sha, bytes.size.toLong(), "weights")),
        contextTokens = 4096, minFreeRamMb = 1000, minFreeStorageMb = 10, license = "Apache-2.0",
    )

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun secrets() = SecretStore(File(tmp, "secrets"), AesGcmCipher { key })
    private fun downloader() = ModelDownloader(File(tmp, "models"), DownloadPolicy.DEFAULT, { 100_000 })
    private fun controller(profiles: LoadedProfiles = LoadedProfiles(listOf(cloud, phone).associateBy { it.id }, emptyList())): SetupController {
        val choice = ModelChoice(ModelChoiceStore(File(tmp, "choice.json")), profiles)
        return SetupController(profiles, choice, secrets(), downloader(), ConsentStore(File(tmp, "consent.json")),
            ConsentCopy(File(shared, "copy/consent.json").readText()), { clock })
    }

    private fun install() = runBlocking {
        File(tmp, "models").mkdirs(); File(tmp, "models/tiny.gguf").writeBytes(bytes)
        assertTrue(downloader().verify(phone))
    }

    @Test
    fun aFreshInstallAsksBothQuestionsBeforeAnythingElse() {
        val c = controller()
        assertEquals(Role.VISION, assertIs<SetupStep.NeedsChoice>(c.step()).role)
        c.choose(Role.VISION, "deepseek-flash") // roles are independent: choosing one leaves the other open
        assertEquals(Role.TEXT, assertIs<SetupStep.NeedsChoice>(c.step()).role)
    }

    @Test
    fun eachChoiceSaysInOneLineWhatItMeans() {
        val c = controller()
        assertEquals(listOf("Frames go to DeepSeek V4 Flash; costs money.", "Stays on this phone; free; needs a 2 MB download; slower and less accurate."),
            c.options(Role.VISION).map { it.meaning }.map { it.replace(cloud.label, "DeepSeek V4 Flash").replace("needs a 2 MB", "needs a 2 MB") })
        assertTrue(c.options(Role.TEXT).first { it.profile.id == "deepseek-flash" }.meaning.startsWith("Text goes to "))
        assertTrue(c.options(Role.TEXT).first { it.profile.id == "deepseek-flash" }.meaning.endsWith("; costs money."))
    }

    @Test
    fun theOptionsAreOnlyProfilesThatListTheRoleAndRunOnThePhone() {
        val textOnly = cloud.copy(id = "text-only", model = "other", roles = listOf(Role.TEXT), timeoutS = mapOf(Role.TEXT to 60.0))
        val c = controller(LoadedProfiles(listOf(cloud, textOnly, phone).associateBy { it.id }, emptyList()))
        assertEquals(listOf("deepseek-flash", "tiny-device"), c.options(Role.VISION).map { it.profile.id })
        assertEquals(listOf("deepseek-flash", "text-only", "tiny-device"), c.options(Role.TEXT).map { it.profile.id })
        assertFailsWith<Exception> { c.choose(Role.VISION, "text-only") } // does not list the role
    }

    @Test
    fun cloudWithoutAKeyAsksForTheKeyAndNothingElse() {
        val c = controller(); c.choose(Role.VISION, "deepseek-flash"); c.choose(Role.TEXT, "deepseek-flash")
        assertEquals(KeyKind.MODEL_PROVIDER, assertIs<SetupStep.NeedsKey>(c.step()).key)
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        assertIs<SetupStep.NeedsConsent>(c.step())
    }

    @Test
    fun aKeyThatCannotBeReadAnymoreIsAskedForAgain() {
        val c = controller(); c.choose(Role.VISION, "deepseek-flash"); c.choose(Role.TEXT, "deepseek-flash")
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        File(tmp, "secrets/model_provider.bin").writeBytes(byteArrayOf(1, 2, 3)) // damaged
        assertIs<SetupStep.NeedsKey>(c.step())
    }

    @Test
    fun thePhoneNotDownloadedShowsTheSizeTheFreeStorageNeededAndTheSource() {
        val c = controller(); c.choose(Role.VISION, "tiny-device"); c.choose(Role.TEXT, "tiny-device")
        val step = assertIs<SetupStep.NeedsDownload>(c.step())
        assertEquals("tiny-device", step.profile.id); assertEquals(bytes.size.toLong(), step.remainingBytes)
        val text = step.notice.text()
        assertTrue("Licence: Apache-2.0" in text && "huggingface.co" in text && "2 MB" in text && "10 MB of free storage" in text, text)
        install()
        assertIs<SetupStep.NeedsConsent>(c.step())
    }

    @Test
    fun theRolesOnDifferentPlacesGetTwoBlocksAndTheFramesStayOnThePhone() {
        install()
        val c = controller(); c.choose(Role.VISION, "tiny-device"); c.choose(Role.TEXT, "deepseek-flash")
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        val step = assertIs<SetupStep.NeedsConsent>(c.step())
        assertEquals(2, step.blocks.size)
        assertTrue("on this phone" in step.blocks[0] && "never leave the phone" in step.blocks[0], step.blocks[0])
        assertTrue(step.blocks[1].startsWith("Descriptions of the clip") && "text model provider" in step.blocks[1], step.blocks[1])
        assertTrue(step.consentText.startsWith("I filmed this or have permission to use it."))
    }

    @Test
    fun bothRolesInTheCloudGetTheSharedFilesWordsForThatPlace() {
        val c = controller(); c.choose(Role.VISION, "deepseek-flash"); c.choose(Role.TEXT, "deepseek-flash")
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        val words = ConsentCopy(File(shared, "copy/consent.json").readText()).blocks("cloud", cloud.label, "cloud", cloud.label)
        assertEquals(words, assertIs<SetupStep.NeedsConsent>(c.step()).blocks)
        assertEquals(1, words.size)
    }

    @Test
    fun aConsentGivenForThePhoneIsAskedAgainWhenARoleMovesToTheCloud() {
        install()
        val c = controller(); c.choose(Role.VISION, "tiny-device"); c.choose(Role.TEXT, "tiny-device")
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        c.confirmConsent()
        assertIs<SetupStep.Ready>(c.step())
        c.choose(Role.VISION, "deepseek-flash") // the frames now leave the phone
        assertIs<SetupStep.NeedsConsent>(c.step())
        c.confirmConsent()
        assertIs<SetupStep.Ready>(c.step())
        c.choose(Role.VISION, "tiny-device") // back to the narrower place: the wider consent still covers it
        assertIs<SetupStep.Ready>(c.step())
    }

    @Test
    fun aConsentForTheCloudDoesNotCoverNothingElseAndTheRecordNamesBothPlaces() {
        install()
        val c = controller(); c.choose(Role.VISION, "deepseek-flash"); c.choose(Role.TEXT, "tiny-device")
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234"))
        c.confirmConsent()
        val saved = ConsentStore(File(tmp, "consent.json")).load()!!
        assertEquals("cloud" to "device", saved.getValue("vision_location").jsonPrimitive.content to saved.getValue("text_location").jsonPrimitive.content)
        assertEquals("2026-11-20T09:00:00Z", saved.getValue("at").jsonPrimitive.content)
    }

    @Test
    fun aRunRecordsTheProfilesInThePcsShape() {
        install()
        val c = controller(); c.choose(Role.VISION, "tiny-device"); c.choose(Role.TEXT, "deepseek-flash")
        secrets().put(KeyKind.MODEL_PROVIDER, Secret("sk-abcdefghijklmnop1234")); c.confirmConsent()
        val models = assertIs<SetupStep.Ready>(c.step()).models.toJson()
        assertEquals(setOf("vision", "text"), models.keys)
        val v = models.getValue("vision").jsonObject; val t = models.getValue("text").jsonObject
        assertEquals(setOf("profile", "label", "location", "model"), v.keys)
        assertEquals(listOf("tiny-device", "device", "tiny.gguf"), listOf("profile", "location", "model").map { v.getValue(it).jsonPrimitive.content })
        assertEquals(listOf("deepseek-flash", "cloud", "deepseek-flash"), listOf("profile", "location", "model").map { t.getValue(it).jsonPrimitive.content })
    }
}
