package app.epicreplay.core.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The price of a character of speech is one number in `shared/voice/pricing.json`, read by the PC app; the constant here must be that number (constitution XI: shared data lives once). */
class ElevenLabsPricingTest {
    @Test
    fun theConstantIsTheSharedNumber() {
        val shared = Json.parseToJsonElement(File(System.getProperty("shared.dir"), "voice/pricing.json").readText()).jsonObject.getValue("elevenlabs").jsonObject.getValue("usd_per_1000_chars").jsonPrimitive.double
        assertEquals(shared / 1000, ElevenLabs.USD_PER_CHAR, 0.0)
    }
}
