package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.models.Platform
import app.epicreplay.core.models.ProfileFile
import app.epicreplay.core.models.ProfileLoader
import app.epicreplay.core.models.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T407 on the phone: the schema library and the loader run on ART, over the profiles packaged from `shared/models/`. */
@RunWith(AndroidJUnit4::class)
class ProfileLoaderOnDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun text(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    @Test
    fun theShippedProfilesLoadOnAndroidMinusWhatItCannotRun() {
        val files = assets.list("models")!!.filter { it.endsWith(".json") }.map { ProfileFile(it, text("models/$it")) }
        assertTrue("no profile files packaged", files.size >= 2)
        val r = ProfileLoader(text("contracts/model_profile.schema.json"), Platform.ANDROID).load(files)
        assertEquals(emptyList<String>(), r.refused().map { it.file + ": " + it.reason })
        val p = r.profiles["deepseek-flash"] as ServerProfile
        assertEquals(0.0009, p.usd(1000, 500), 0.0)
        assertTrue(r.profiles.keys.none { it.endsWith("-local") })
        assertTrue(r.leftOut().isNotEmpty())
    }

    @Test
    fun aBadProfileIsRefusedOnTheDeviceToo() {
        val bad = text("models/deepseek-flash.json").replace("\"max_parallel\"", "\"surprise\": 1, \"max_parallel\"")
        val r = ProfileLoader(text("contracts/model_profile.schema.json"), Platform.ANDROID).load(listOf(ProfileFile("deepseek-flash.json", bad)))
        assertTrue(r.profiles.isEmpty())
        assertTrue("surprise" in r.refused().single().reason)
    }
}
