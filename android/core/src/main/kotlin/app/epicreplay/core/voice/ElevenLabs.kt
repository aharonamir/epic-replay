package app.epicreplay.core.voice

import app.epicreplay.core.PyMath
import app.epicreplay.core.record.cacheKey
import app.epicreplay.core.text.PyText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The ElevenLabs take (`pc/src/epic_replay/providers/speech.py`, plan 4.8): the request for one take, what it costs by estimate, the character timings
 * kept from the answer, and the key a recording is stored under (the PC's, so a recording made on either platform is read by the other).
 */
object ElevenLabs {
    private const val URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128"
    private const val TIMED_URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/with-timestamps?output_format=mp3_44100_128"
    const val USD_PER_CHAR = 0.10 / 1000
    const val TIMEOUT_S = 60
    const val SAMPLE_RATE = 48000

    /** The HTTP request for one take: [timings] asks for the character timings too (the `with-timestamps` URL). */
    fun request(text: String, voiceId: String, delivery: String, model: String, key: String, timings: Boolean = false, language: String = "en"): JsonObject = buildJsonObject {
        put("method", "POST")
        put("url", (if (timings) TIMED_URL else URL).replace("{voice_id}", voiceId))
        put("headers", buildJsonObject { put("xi-api-key", key); put("Content-Type", "application/json") })
        put("body", buildJsonObject {
            put("text", Voice.tagged(text, delivery)); put("model_id", model)
            if (language != "en") put("language_code", language)
        })
        put("timeout_s", TIMEOUT_S)
    }

    /** The characters sent: the text with its delivery tags, counted in code points. */
    fun chars(text: String, delivery: String): Int = PyText.length(Voice.tagged(text, delivery))

    fun estimateUsd(text: String, delivery: String): Double = PyMath.round(chars(text, delivery) * USD_PER_CHAR, 6)

    /** What is kept of an answer's `alignment`: `{chars, starts}`, or null when it has none. */
    fun alignment(answer: JsonObject): JsonObject? {
        val a = answer["alignment"] as? JsonObject ?: return null
        val chars = a["characters"] as? JsonArray; val starts = a["character_start_times_seconds"] as? JsonArray
        if (chars == null || chars.isEmpty() || starts == null || starts.isEmpty()) return null
        return buildJsonObject { put("chars", chars.joinToString("") { it.jsonPrimitive.content }); put("starts", JsonArray(starts)) }
    }

    fun recordingKey(text: String, voiceId: String, delivery: String, language: String): String =
        cacheKey("speech", "elevenlabs", buildJsonObject { put("text", text); put("voice_id", voiceId); put("delivery", delivery); put("language", language) })
}
