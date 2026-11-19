package app.epicreplay.core.download

import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Platform
import app.epicreplay.core.models.ProfileLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The real thing, off by default (`./gradlew :core:test -Dnetwork.test=true --tests '*RealDownloadTest*'`): the
 * smallest shipped file (Gemma 4's image projector, 557 MB) from Hugging Face under the default policy, cancelled
 * partway and resumed. It proves the allowed hosts, the redirect, the Range answer and the checksum on the real
 * servers; the failure modes are tested against a local server in ModelDownloaderTest.
 */
class RealDownloadTest {
    private lateinit var dir: File
    @BeforeTest fun setUp() { dir = Files.createTempDirectory("real").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun theProjectorOfGemmaDownloadsResumesAndMatchesItsChecksum() {
        assumeTrue(System.getProperty("network.test") == "true", "set -Dnetwork.test=true to download 557 MB")
        val shared = File(System.getProperty("shared.dir"))
        val full = ProfileLoader.loadDir(File(shared, "models"), File(shared, "contracts/model_profile.schema.json"), Platform.ANDROID)
            .profiles.getValue("gemma-4-e2b-device") as OnDeviceProfile
        val profile = full.copy(files = full.files.filter { it.role == "projector" }) // one file, to keep it short
        val d = ModelDownloader(dir, DownloadPolicy.DEFAULT, { 100_000 })
        val seen = mutableListOf<Progress>()
        runBlocking {
            val job = launch(Dispatchers.IO) { runCatching { d.download(profile) { seen += it } } }
            while (File(dir, "${profile.files[0].name}.part").length() < 30_000_000L) delay(200)
            job.cancel(); job.join()
            val part = File(dir, "${profile.files[0].name}.part")
            assertTrue(part.isFile && part.length() in 1 until profile.files[0].bytes, "no resumable .part: ${part.length()}")
            println("resuming from ${part.length()} bytes")
            d.download(profile) { seen += it }
        }
        assertTrue(d.installed(profile))
        assertEquals(profile.files[0].bytes, File(dir, profile.files[0].name).length())
    }
}
