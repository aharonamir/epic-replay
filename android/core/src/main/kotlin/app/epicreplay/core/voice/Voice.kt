package app.epicreplay.core.voice

import app.epicreplay.core.PyMath
import app.epicreplay.core.text.PyText
import java.util.regex.Pattern

/** One script line as placement reads it. */
class Line(val id: String, val role: String, val maxDurationS: Double, val timelineT: Double)

/** What was measured on one take: `leadS` cut from the front by the trim; for a key line the first strong onset and the hit word's start (already moved to the nearest rise) in the raw take. */
class Measure(val leadS: Double, val onsetS: Double?, val wordAtS: Double?)

/** One line's record in `voice.json`, completed by [Voice.place]. */
class Rec(
    val id: String, val role: String, val file: String, val rawDurationS: Double,
    var speedFactor: Double, var durationS: Double, var dropped: String? = null,
) {
    var overS: Double? = null
    var hitOffsetS: Double? = null
    var hitSource: String? = null
    var targetS: Double? = null
    var timelineStart: Double = 0.0
    var timelineEnd: Double = 0.0
}

/** The placement rules of S7 (`pc/src/epic_replay/stages/s7_voice.py`): where the words land, and where each take goes on the output timeline. */
object Voice {
    const val MAX_SPEEDUP = 1.12
    const val GAP_S = 0.1
    const val PAD_S = 0.02 // the audio library's end pad (media/audio.py)
    private val KEY_ROLES = setOf("climax", "replay_climax")

    /** The text as the voice provider is sent it: each delivery tag in brackets, then the text. */
    fun tagged(text: String, delivery: String): String {
        val tags = delivery.split(",").map { it.trim { c -> PyText.isSpace(c) } }.filter { it.isNotEmpty() }
        return tags.joinToString("") { "[$it] " } + text
    }

    private fun cps(s: String) = s.codePointCount(0, s.length)
    private fun cpIndex(s: String, utf16: Int) = s.codePointCount(0, utf16)
    private const val W = "[\\p{L}\\p{N}_]" // Python's \w for a str: letters, digits and the underscore

    /** Where `word` starts in the raw take, from the provider's character timings; null when there are none, they are not over the text as sent, the word is not in the line, or it is the line's first word. */
    fun wordStart(chars: String?, starts: List<Double>?, delivery: String, text: String, word: String, language: String): Double? {
        if (chars == null || starts == null) return null
        val sent = tagged(text, delivery)
        if (chars != sent || starts.size != cps(sent)) return null
        if (language == "zh") {
            val at = if (word.isNotEmpty()) text.indexOf(word) else -1
            if (at < 0 || text.substring(0, at).codePoints().noneMatch { isCjk(it) }) return null
            return starts[cps(sent) - cps(text) + cpIndex(text, at)]
        }
        val m = Pattern.compile("(?<!$W|')${Pattern.quote(word)}(?!$W|')", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(text)
        if (!m.find()) return null
        if (!Pattern.compile(W).matcher(text.substring(0, m.start())).find()) return null
        return starts[cps(sent) - cps(text) + cpIndex(text, m.start())]
    }

    private fun isCjk(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF

    /** How much faster a take of [rawS] must play to fit [limitS]: 1.0 if it fits, else at most 12 percent. */
    fun fitFactor(rawS: Double, limitS: Double): Double = if (rawS <= limitS) 1.0 else minOf(MAX_SPEEDUP, rawS / maxOf(limitS - 0.01, 1e-3))

    /** The conformance cases' model of fitting a take: (speed factor, duration); it lasts raw/factor, to the millisecond, and one that overshoots the limit by under the pad is cut at the limit. */
    fun modelledFit(rawS: Double, limitS: Double): Pair<Double, Double> {
        val factor = fitFactor(rawS, limitS)
        var dur = PyMath.round(rawS / factor, 3)
        if (factor > 1.0 && limitS < dur && dur <= limitS + PAD_S) dur = PyMath.round(limitS, 3)
        return PyMath.round(factor, 4) to dur
    }

    /** Completes [recs] in place: the key lines land their hit word on the marker plus the reaction [delay], the others start at their anchor; a line over its slot, or in a key line's way, is sped up by [refit] or dropped. */
    fun place(lines: List<Line>, recs: List<Rec>, measures: List<Measure>, delay: Double, end: Double, refit: (Rec, Double) -> Pair<Double, Double>) {
        for (i in lines.indices) {
            val ln = lines[i]; val rec = recs[i]; val m = measures[i]
            val key = ln.role in KEY_ROLES
            if (rec.durationS > ln.maxDurationS + 1e-3) {
                if (key) rec.overS = PyMath.round(rec.durationS - ln.maxDurationS, 3)
                else rec.dropped = "${PyMath.fixed2(rec.durationS)} s does not fit ${PyMath.fixed2(ln.maxDurationS)} s at 12 percent faster"
            }
            if (key) {
                rec.hitOffsetS = PyMath.round(m.onsetS!!, 3); rec.hitSource = "onset"
                val at = m.wordAtS
                if (at != null && 0 <= at - m.leadS && at - m.leadS < rec.rawDurationS) {
                    rec.hitOffsetS = PyMath.round((at - m.leadS) / rec.speedFactor, 3); rec.hitSource = "timing"
                }
                rec.targetS = PyMath.round(ln.timelineT + delay, 3)
                rec.timelineStart = PyMath.round(rec.targetS!! - rec.hitOffsetS!!, 3)
            } else rec.timelineStart = ln.timelineT
        }
        val kept = recs.filter { it.dropped == null }
        for ((i, r) in kept.withIndex()) {
            if (r.role in KEY_ROLES) continue
            val nxt = kept.drop(i + 1).firstOrNull { it.dropped == null }
            val prv = kept.take(i).asReversed().firstOrNull { it.dropped == null }
            if (prv != null && prv.role in KEY_ROLES) {
                if (r.timelineStart < prv.timelineStart + prv.durationS + GAP_S - 1e-6) { r.dropped = "would start under the key line before it"; continue }
            }
            if (nxt != null && nxt.role in KEY_ROLES) {
                val room = nxt.timelineStart - GAP_S - r.timelineStart
                if (r.durationS > room + 1e-6) {
                    if (r.rawDurationS > room * MAX_SPEEDUP + 1e-6) r.dropped = "does not end 0.1 s before the key line"
                    else { val f = refit(r, room); r.speedFactor = f.first; r.durationS = f.second }
                }
            }
        }
        for (r in recs) {
            if (r.dropped == null && r.role !in KEY_ROLES && r.timelineStart + r.durationS > end + 1e-6) r.dropped = "would run past the end of the video"
            r.timelineEnd = PyMath.round(r.timelineStart + r.durationS, 3)
        }
    }
}
