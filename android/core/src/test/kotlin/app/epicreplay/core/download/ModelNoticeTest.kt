package app.epicreplay.core.download

import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelNoticeTest {
    private val profile = OnDeviceProfile(
        id = "p", label = "Gemma 4 E2B (on this phone)", roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "",
        engine = "llama.cpp",
        files = listOf(
            ModelFile("gemma-4-E2B-it-Q4_0.gguf", "https://huggingface.co/ggml-org/x/resolve/abc/gemma-4-E2B-it-Q4_0.gguf?download=true", "0".repeat(64), 2_841_481_184, "weights"),
            ModelFile("mmproj.gguf", "https://huggingface.co/ggml-org/x/resolve/abc/mmproj.gguf", "0".repeat(64), 557_368_096, "projector"),
        ),
        contextTokens = 6144, minFreeRamMb = 4000, minFreeStorageMb = 3800, license = "Apache-2.0",
    )

    @Test
    fun theNoticeShowsTheLicenceTheSourceTheSizesAndTheFloorsFromTheProfile() {
        val text = ModelNotice.of(profile).text()
        assertTrue(text.startsWith("Gemma 4 E2B (on this phone)\nLicence: Apache-2.0\nDownloaded from: huggingface.co\n"), text)
        assertTrue("Size: 3.4 GB in 2 files (gemma-4-E2B-it-Q4_0.gguf, 2.8 GB, mmproj.gguf, 557 MB)" in text, text)
        assertTrue("3800 MB of free storage" in text && "4000 MB of free memory" in text, text)
        assertTrue("deleted in settings" in text)
    }

    @Test
    fun theHostsAreOnlyHostsNeverAFullAddress() {
        val text = ModelNotice.of(profile).text()
        assertTrue("resolve" !in text && "download=true" !in text)
    }

    @Test
    fun aMeteredConnectionGetsAWarningAndWifiDoesNot() {
        assertNull(ModelNotice.connectionAdvice(profile, unmetered = true))
        val advice = ModelNotice.connectionAdvice(profile, unmetered = false)!!
        assertTrue("3.4 GB" in advice && "Wi-Fi" in advice, advice)
    }

    @Test
    fun sizesAreWrittenInDecimalUnits() {
        assertEquals("3.4 GB", ModelNotice.megabytes(3_398_849_280))
        assertEquals("557 MB", ModelNotice.megabytes(557_368_096))
        assertEquals("1 MB", ModelNotice.megabytes(1_000_000))
    }
}
