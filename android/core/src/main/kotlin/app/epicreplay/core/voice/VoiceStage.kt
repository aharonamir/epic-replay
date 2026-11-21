package app.epicreplay.core.voice

import app.epicreplay.core.PyMath
import app.epicreplay.core.audio.AudioAnalysis
import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.audio.Tempo
import app.epicreplay.core.audio.Wav
import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.record.ReplayMiss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/** What S7 gave (the caller, T424's run, writes `voice.json` and each take's `voice/<id>.wav` and emits the stage event, as the PC's runner does): the status as the PC names it, a note, `voice.json`, and the fitted take of each line (48 kHz mono, by line id). */
class VoiceOutcome(val status: String, val note: String, val doc: JsonObject?, val takes: Map<String, Pcm>)

/**
 * S7 on the phone (`pc/src/epic_replay/stages/s7_voice.py`, plan 4.8, FR-022 to FR-025): one take per line, three at a time (the provider's limit), one retry
 * after a second, then in code: the take's silence trimmed, a take over its slot sped up by at most 12 percent or dropped, a key line placed so that its hit
 * word lands on the moment (by the provider's character timings moved to the nearest rise, else by the first onset), the lines around a key line made to
 * keep clear of it ([Voice.place]). A model's or a voice's cap stops the stage ("stopped"); a take that cannot be made after its retry fails it.
 */
class VoiceStage(private val speech: Speech, private val retryDelayMs: Long = 1000, private val concurrent: Int = 3) {
    private val keyRoles = setOf("climax", "replay_climax")

    private class Piece(val raw: Pcm, val trimmed: FloatArray, val leadS: Double, val rawS: Double, var fitted: FloatArray, val take: Take)

    private suspend fun take(line: JsonObject, voiceId: String, language: String): Take {
        val key = line.getValue("role").jsonPrimitive.content in keyRoles && (line["hit_word"] as? JsonPrimitive)?.content?.isNotEmpty() == true
        for (attempt in 1..2) {
            try {
                return speech.synthesise(line.getValue("text").jsonPrimitive.content, voiceId, line.getValue("delivery").jsonPrimitive.content, language, key)
            } catch (e: CancellationException) { throw e } catch (e: CapExceeded) { throw e } catch (e: ReplayMiss) { throw e } catch (e: Exception) {
                if (attempt == 2) throw e
                delay(retryDelayMs)
            }
        }
        throw AssertionError("unreachable")
    }

    /** `fit`: the take sped up to fit [limitS] by at most 12 percent; (speed factor, the take). */
    private fun fit(trimmed: FloatArray, sr: Int, rawS: Double, limitS: Double): Pair<Double, FloatArray> {
        val factor = Voice.fitFactor(rawS, limitS)
        if (factor <= 1.0) return PyMath.round(factor, 4) to trimmed
        var y = Tempo.speedUp(trimmed, sr, factor)
        val len = y.size.toDouble() / sr
        if (limitS < len && len <= limitS + Voice.PAD_S) y = y.copyOf((limitS * sr).toInt()) // the tempo change rounds: cut the end pad
        return PyMath.round(factor, 4) to y
    }

    private fun q(y: FloatArray, sr: Int): FloatArray = Wav.read(Wav.write(Pcm(y, sr))).samples // a take is a 16-bit file: it is measured as that

    suspend fun run(script: JsonObject, storyboard: JsonObject): VoiceOutcome {
        val lines = script.getValue("lines").jsonArray.map { it.jsonObject }
        val voiceId = script.getValue("voice").jsonObject.getValue("voice_id").jsonPrimitive.content
        val language = (script["language"] as? JsonPrimitive)?.content ?: "en"
        val delay = script.getValue("reaction_delay_s").jsonPrimitive.double
        val takes = try {
            coroutineScope { val gate = Semaphore(concurrent); lines.map { l -> async { gate.withPermit { take(l, voiceId, language) } } }.awaitAll() }
        } catch (e: CancellationException) { throw e } catch (e: CapExceeded) { return VoiceOutcome("stopped", e.message ?: "a cap was reached", null, emptyMap()) } catch (e: ReplayMiss) {
            return VoiceOutcome("failed", e.message ?: "no recording", null, emptyMap())
        } catch (e: Exception) { return VoiceOutcome("failed", "The voice could not be made; try again. (${e.javaClass.simpleName}: ${(e.message ?: "").take(200)})", null, emptyMap()) }

        val pieces = ArrayList<Piece>(); val recs = ArrayList<Rec>(); val measures = ArrayList<Measure>(); val coreLines = ArrayList<Line>()
        for ((l, tk) in lines.zip(takes)) {
            val id = l.getValue("id").jsonPrimitive.content; val role = l.getValue("role").jsonPrimitive.content
            val limit = l.getValue("max_duration_s").jsonPrimitive.double
            val sr = tk.pcm.sampleRate
            val (a, b) = AudioAnalysis.trimBounds(tk.pcm.samples, sr)
            val trimmed = tk.pcm.samples.copyOfRange(a, b)
            val rawS = PyMath.round((b - a).toDouble() / sr, 3)
            val (factor, fitted) = fit(trimmed, sr, rawS, limit)
            val dur = PyMath.round(fitted.size.toDouble() / sr, 3)
            val piece = Piece(tk.pcm, trimmed, a.toDouble() / sr, rawS, fitted, tk)
            pieces += piece
            recs += Rec(id, role, "voice/$id.wav", rawS, factor, dur)
            coreLines += Line(id, role, limit, l.getValue("anchor").jsonObject.getValue("timeline_t").jsonPrimitive.double)
            var onset: Double? = null; var wordAt: Double? = null
            if (role in keyRoles) {
                onset = AudioAnalysis.firstOnset(q(fitted, sr), sr)
                val al = tk.alignment
                val at = Voice.wordStart(
                    al?.get("chars")?.jsonPrimitive?.content, (al?.get("starts") as? JsonArray)?.map { it.jsonPrimitive.double },
                    l.getValue("delivery").jsonPrimitive.content, l.getValue("text").jsonPrimitive.content, (l["hit_word"] as? JsonPrimitive)?.content ?: "", language,
                )
                if (at != null) wordAt = AudioAnalysis.snapToRise(q(tk.pcm.samples, sr), sr, at, SNAP_S)
            }
            measures += Measure(piece.leadS, onset, wordAt)
        }
        val byId = pieces.indices.associateBy { recs[it].id }
        Voice.place(coreLines, recs, measures, delay, storyboard.getValue("duration_s").jsonPrimitive.double) { r, room ->
            val p = pieces[byId.getValue(r.id)]
            val (f, y) = fit(p.trimmed, p.raw.sampleRate, p.rawS, room)
            p.fitted = y
            f to PyMath.round(y.size.toDouble() / p.raw.sampleRate, 3)
        }
        val out = ArrayList<JsonObject>()
        for ((i, r) in recs.withIndex()) {
            val tk = pieces[i].take
            out += buildJsonObject {
                put("id", r.id); put("role", r.role); put("file", r.file); put("raw_duration_s", r.rawDurationS); put("speed_factor", r.speedFactor); put("duration_s", r.durationS)
                put("provider", tk.provider); put("voice", voiceId); put("cost_usd", tk.usd); put("cache_hit", tk.cacheHit)
                put("dropped", r.dropped?.let(::JsonPrimitive) ?: JsonNull)
                r.overS?.let { put("over_s", it) }
                r.hitOffsetS?.let { put("hit_offset_s", it); put("hit_source", r.hitSource!!); put("target_s", r.targetS!!) }
                put("timeline_start", r.timelineStart); put("timeline_end", r.timelineEnd)
            }
        }
        val cost = PyMath.round(pieces.sumOf { it.take.usd }, 6)
        val doc = buildJsonObject { put("lines", JsonArray(out)); put("reaction_delay_s", delay); put("cost_usd", cost) }
        val dropped = recs.filter { it.dropped != null }.map { it.id }
        val status = if (recs.any { it.overS != null }) "degraded" else "ok"
        return VoiceOutcome(status, if (dropped.isEmpty()) "" else "dropped ${dropped.joinToString(", ")}", doc, recs.indices.associate { recs[it].id to Pcm(pieces[it].fitted, pieces[it].raw.sampleRate) })
    }

    companion object { const val SNAP_S = 0.15 }
}
