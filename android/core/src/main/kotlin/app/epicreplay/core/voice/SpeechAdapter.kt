package app.epicreplay.core.voice

import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.audio.Wav
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.budget.PaidResult
import app.epicreplay.core.budget.paidCall
import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.Recorder
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One take: 48 kHz mono samples, the character timings when they were asked for, and what it cost. */
class Take(val pcm: Pcm, val alignment: JsonObject?, val usd: Double, val cacheHit: Boolean, val provider: String = "elevenlabs")

/** MP3 bytes to the 48 kHz mono samples every take is kept as (the phone's decoder; FFmpeg on the PC). */
fun interface AudioConverter {
    fun toWav48k(mp3: ByteArray): Pcm
}

/** Where a take comes from; [SpeechAdapter] is the live and recorded one. */
interface Speech {
    suspend fun synthesise(text: String, voiceId: String, delivery: String, language: String, timings: Boolean): Take
}

/** A failure of the provider: its status and the first words of what it said (never the key). */
class SpeechError(message: String) : Exception(message)

/**
 * The speech adapter of the PC (`providers/speech.py`): one take for one request, recorded and replayed under the PC's key (the JSON next to a `<key>.wav`),
 * and when it is live an ElevenLabs call that reserves its estimate in the ledger before it is sent and settles it after (a timeout counts as spent), so
 * model and voice calls share the per-run and daily caps. [key] is asked for at the moment of the call.
 */
class SpeechAdapter(
    private val transport: HttpTransport,
    private val key: () -> String?,
    private val model: String,
    private val converter: AudioConverter,
    private val ledger: Ledger? = null,
    private val recorder: Recorder? = null,
    private val runId: String = "run",
    private val stage: String = "S7",
) : Speech {
    private fun sidecar(k: String): File = recorder!!.path("speech", k).let { File(it.parentFile, it.nameWithoutExtension + ".wav") }

    private suspend fun live(text: String, voiceId: String, delivery: String, language: String, timings: Boolean): Pair<JsonObject, Pcm> {
        val apiKey = key() ?: throw SpeechError("ELEVENLABS_API_KEY is not set")
        val req = ElevenLabs.request(text, voiceId, delivery, model, apiKey, timings, language)
        val body = req.getValue("body").jsonObject.toString().toByteArray(Charsets.UTF_8)
        val headers = req.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val r = transport.send(HttpRequest("POST", req.getValue("url").jsonPrimitive.content, headers, body, JsonPrimitive(ElevenLabs.TIMEOUT_S)), "elevenlabs")
        if (r.status != 200) throw SpeechError("ElevenLabs ${r.status}: ${String(r.body, Charsets.UTF_8).take(200).replace(apiKey, "***")}")
        var alignment: JsonObject? = null
        var mp3 = r.body
        if (timings) {
            val answer = Json.parseToJsonElement(String(r.body, Charsets.UTF_8)).jsonObject
            mp3 = Base64.getDecoder().decode(answer.getValue("audio_base64").jsonPrimitive.content)
            alignment = ElevenLabs.alignment(answer)
        }
        val pcm = converter.toWav48k(mp3)
        val sent = ElevenLabs.chars(text, delivery)
        val payload = buildJsonObject {
            put("duration_s", Math.round(pcm.durationS * 1000) / 1000.0); put("chars", sent); put("model", model)
            put("usage_usd", app.epicreplay.core.PyMath.round(sent * ElevenLabs.USD_PER_CHAR, 6))
            alignment?.let { put("alignment", it) }
        }
        return payload to pcm
    }

    override suspend fun synthesise(text: String, voiceId: String, delivery: String, language: String, timings: Boolean): Take {
        val k = ElevenLabs.recordingKey(text, voiceId, delivery, language)
        var fresh: Pcm? = null
        val run = suspend {
            val (payload, hit) = recorder?.fetch("speech", k) {
                val (p, pcm) = live(text, voiceId, delivery, language, timings)
                fresh = pcm
                if (recorder.mode == ProviderMode.RECORD) sidecar(k).also { it.parentFile.mkdirs() }.writeBytes(Wav.write(pcm))
                p
            } ?: live(text, voiceId, delivery, language, timings).let { (p, pcm) -> fresh = pcm; p to false }
            PaidResult(payload to hit, if (hit) 0.0 else payload["usage_usd"]?.jsonPrimitive?.double ?: 0.0, hit)
        }
        val known = recorder?.has("speech", k) == true
        // a live paid request always goes through the ledger: without one, only a recording already on disk may be read
        if (ledger == null && !known) throw SpeechError("a live voice call needs the ledger that holds the caps")
        val (payload, hit) = if (ledger == null) run().value else ledger.paidCall(runId, stage, "elevenlabs", model, if (known) 0.0 else ElevenLabs.estimateUsd(text, delivery), false) { run() }
        val pcm = if (hit) Wav.read(sidecar(k).readBytes()) else fresh!!
        val alignment = payload["alignment"] as? JsonObject
        return Take(pcm, alignment, if (hit) 0.0 else payload["usage_usd"]?.jsonPrimitive?.double ?: 0.0, hit)
    }
}
