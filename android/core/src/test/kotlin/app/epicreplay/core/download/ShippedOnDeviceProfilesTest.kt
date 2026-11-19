package app.epicreplay.core.download

import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Platform
import app.epicreplay.core.models.ProfileLoader
import app.epicreplay.core.models.Role
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The on-device profiles shipped in `shared/models/` (T410): what the downloader will fetch and verify. */
class ShippedOnDeviceProfilesTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val profiles = ProfileLoader.loadDir(File(shared, "models"), File(shared, "contracts/model_profile.schema.json"), Platform.ANDROID)
        .profiles.values.filterIsInstance<OnDeviceProfile>()

    @Test
    fun theTwoProfilesOfSpecDecisionD2AreShipped() {
        assertEquals(listOf("gemma-4-e2b-device", "qwen3-5-4b-device"), profiles.map { it.id }.sorted())
        assertTrue(profiles.all { it.roles.toSet() == setOf(Role.VISION, Role.TEXT) && it.status == "experimental" && it.license == "Apache-2.0" })
    }

    @Test
    fun everyFileIsPinnedToACommitOnAnAllowedHostWithASizeAndAChecksum() {
        for (p in profiles) {
            assertEquals(setOf("weights", "projector"), p.files.map { it.role }.toSet(), p.id)
            for (f in p.files) {
                assertNull(DownloadPolicy.DEFAULT.refusal(f.url), "${f.url} is not downloadable under the default policy")
                assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(f.url), "${f.url} is not pinned to a commit")
                assertTrue(Regex("[0-9a-f]{64}").matches(f.sha256) && f.bytes > 100_000_000, f.name)
                assertTrue(f.name.endsWith(".gguf") && "/" !in f.name)
            }
        }
    }

    @Test
    fun fileNamesAreUniqueAcrossProfilesBecauseTheyShareOneFolder() {
        val names = profiles.flatMap { p -> p.files.map { it.name } }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun theStorageFloorCoversTheFilesAndTheRamFloorIsAtLeastTheFilesInMemory() {
        for (p in profiles) {
            val mb = p.files.sumOf { it.bytes } / 1_000_000
            assertTrue(p.minFreeStorageMb >= mb, "${p.id}: ${p.minFreeStorageMb} MB is below the files' $mb MB")
            assertTrue(p.minFreeRamMb >= mb, "${p.id}: the model needs at least its own files in memory")
        }
    }
}
