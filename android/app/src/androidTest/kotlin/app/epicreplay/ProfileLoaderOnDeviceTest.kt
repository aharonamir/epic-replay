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

    @Test
    fun theIfThenAndFalseSchemaRulesHoldOnTheDevice() {
        val schema = text("contracts/model_profile.schema.json")
        val onDevice = """{"id":"phone-model","label":"P","kind":"on_device","location":"device","roles":["vision"],"engine":"llama.cpp",
          "files":[{"name":"w.gguf","url":"https://example.org/w.gguf","sha256":"${"0".repeat(64)}","bytes":10,"role":"weights"}],
          "context_tokens":4096,"min_free_ram_mb":3000,"min_free_storage_mb":4000,"license":"x","price_in_per_m":0,"price_out_per_m":0,
          "supports_tools":false,"status":"experimental","notes":"n"}"""
        val loader = ProfileLoader(schema, Platform.ANDROID)
        assertTrue(loader.load(listOf(ProfileFile("phone-model.json", onDevice))).profiles.containsKey("phone-model"))
        // a server field on an on_device profile (a `false` schema under `then`), and a wrong location (a `const`)
        val withServer = onDevice.replace("\"engine\"", "\"api_base\":\"http://x/v1\",\"engine\"")
        assertTrue(loader.load(listOf(ProfileFile("phone-model.json", withServer))).refused().isNotEmpty())
        val wrongPlace = onDevice.replace("\"location\":\"device\"", "\"location\":\"cloud\"")
        assertTrue(loader.load(listOf(ProfileFile("phone-model.json", wrongPlace))).refused().isNotEmpty())
        val nbsp = text("models/deepseek-flash.json").replace("https://api.deepseek.com", "https://api.deep\u00a0seek.com")
        assertTrue(loader.load(listOf(ProfileFile("deepseek-flash.json", nbsp))).refused().isNotEmpty())
    }
}
