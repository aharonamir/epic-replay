package app.epicreplay.core.checks

import app.epicreplay.core.text.EnglishLexicon
import app.epicreplay.core.text.MandarinLexicon
import app.epicreplay.core.text.PyText
import app.epicreplay.core.text.SpokenUnits
import java.math.BigDecimal
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `pc/src/epic_replay/stages/s6_checks.py` (rules 1, 4, 5 and 8): the same rules, in the same order, with Python's text
 * behaviour where the Python depends on it (`\d` is any decimal digit, `str.isupper`, `rstrip`, a failure's detail shows a
 * number the way Python writes it).
 */
class Checks(private val english: EnglishLexicon, private val mandarin: MandarinLexicon, private val gb2312: Set<Int>, private val traditionalInGb2312: Set<Int>) {
    private class Fail(val rule: Int, val kind: String, val detail: String)

    // --- numbers and names

    private fun lower(s: String) = s.lowercase(Locale.ROOT)
    private fun words(s: String) = WORD.findAll(s).map { it.value }.toList()
    private fun cleanNumber(raw: String): String = raw.replace(",", ".").trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun numbersIn(text: String): Set<String> {
        val found = DIGITS.findAll(text).map { cleanNumber(it.value) }.toMutableSet()
        for (w in words(lower(text))) NUMBER_WORDS[w]?.let { found += it.toString() }
        return found
    }

    private fun unknownNames(text: String, known: String): List<String> {
        val knownWords = words(known).toSet()
        val out = ArrayList<String>()
        for (m in WORD.findAll(text)) {
            val w = m.value
            val bare = lower(w).removeSuffix("'s")
            if (!w[0].isUpperCase() || bare in knownWords) continue
            val lead = text.substring(0, m.range.first).trimEnd { it in " \"'([" }
            val initial = lead.isEmpty() || lead.last() in ".!?:…"
            val shouted = w.length > 1 && w.none { it.isLowerCase() } // str.isupper(): no lower-case letter, at least one capital
            if (bare in NAMES || !(initial || shouted)) out += w
        }
        return out
    }

    private fun unknownNumbers(text: String, known: String): List<String> {
        val allowed = numbersIn(known)
        val claimed = DIGITS.findAll(text).map { cleanNumber(it.value) }.toMutableSet()
        for (w in words(lower(text))) if (w in NUMBER_WORDS && w !in NOT_COUNTED) claimed += NUMBER_WORDS.getValue(w).toString()
        return (claimed - allowed).sortedWith(PyText.CODE_POINT_ORDER)
    }


    private fun nameFail(n: String) = Fail(4, "invented_name", "'$n' is not in the play sheet or the user context")
    private fun numberFail(n: String) = Fail(4, "invented_number", "the number $n is not in the play sheet or the user context")
    private fun bannedFail(t: String) = Fail(8, "banned_term", "'$t' is a banned term")

    private fun checkText(text: String, known: String, extra: List<String>): List<Fail> =
        unknownNames(text, known).map(::nameFail) + unknownNumbers(text, known).map(::numberFail) + english.findBanned(text, extra).map(::bannedFail)

    // --- Mandarin

    private fun isCjk(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF
    private fun cjk(text: String) = text.codePoints().toArray().filter(::isCjk)

    private fun notSimplified(text: String, typed: List<String>): List<Int> {
        var t = text
        for (name in typed) t = t.replace(name, "")
        return cjk(t).filter { !(it in gb2312 && it !in traditionalInGb2312) }
    }

    private fun checkTextZh(text: String, known: String, extra: List<String>, typed: List<String>): List<Fail> {
        val out = ArrayList<Fail>()
        if (cjk(text).isEmpty()) out += Fail(4, "wrong_language", "this line has no Chinese in it")
        if (notSimplified(text, typed).isNotEmpty()) out += Fail(4, "wrong_language", "write in Simplified Chinese")
        val knownWords = words(known).toSet()
        val names = words(text).filter { lower(it) !in knownWords } + mandarin.famousNames(text).filter { lower(it) !in known }
        out += names.map(::nameFail)
        if (DIGITS.containsMatchIn(text)) out += Fail(4, "invented_number", "write numbers in Chinese numerals, not digits")
        val allowed = numbersIn(known) + mandarin.numbersIn(known)
        out += (mandarin.numbersIn(text).toSet() - allowed).sortedWith(PyText.CODE_POINT_ORDER).map(::numberFail)
        val latin = extra.filter { t -> t.all { it.code < 128 } }
        val chinese = extra.filter { t -> !t.all { it.code < 128 } }
        out += (english.findBanned(text, latin) + mandarin.findBanned(text, chinese)).map(::bannedFail)
        return out
    }

    /** `checks.text`: for each text, its failures. */
    fun checkTexts(language: String, texts: List<String>, known: String, extra: List<String>): JsonArray =
        JsonArray(texts.map { t -> JsonArray((if (language == "zh") checkTextZh(t, known, extra, emptyList()) else checkText(t, known, extra)).map { it.json() }) })

    private fun Fail.json(where: String? = null) = buildJsonObject {
        if (where != null) put("where", where)
        put("rule", rule); put("kind", kind); put("detail", detail)
    }

    // --- the script

    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toBigDecimalOrNull()?.signum() != 0
    }

    /** Python's `str(x)` of a JSON value. */
    private fun pyStr(e: JsonElement?): String = when (e) {
        null, is JsonNull -> "None"
        is JsonPrimitive -> if (e.isString) e.content else if (e.content == "true") "True" else if (e.content == "false") "False" else pyNumber(e.content)
        else -> e.toString()
    }

    private fun corpus(ps: JsonObject, uc: JsonObject): String {
        val parts = ArrayList<JsonElement?>()
        parts += ((ps["activity"]?.takeIf(::truthy) as? JsonObject)?.get("value"))
        parts += ps["setting"]
        parts += ((ps["outcome_summary"]?.takeIf(::truthy) as? JsonObject)?.get("value"))
        for (a in (ps["actors"] as? JsonArray).orEmpty()) { val o = a.jsonObject; parts += o["descriptor"]; parts += o["role"]; parts += o["user_name"] }
        for (e in (ps["events"] as? JsonArray).orEmpty()) { val o = e.jsonObject; parts += o["action"]; parts += o["object"]; parts += o["outcome"] }
        for (k in listOf("player_name", "team_home", "team_away", "score", "activity", "description")) parts += uc[k]
        return lower(parts.filter(::truthy).joinToString(" ") { pyStr(it) })
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()

    private fun checkRefs(script: JsonObject, ps: JsonObject): List<JsonObject> {
        val ids = (ps["events"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        val out = ArrayList<JsonObject>()
        for (ln in (script["lines"] as? JsonArray).orEmpty()) for (ref in (ln.jsonObject["event_refs"] as? JsonArray).orEmpty()) {
            val r = ref.jsonPrimitive.content
            if (r !in ids) out += Fail(1, "missing_reference", "event $r is not in the play sheet").json(ln.jsonObject.getValue("id").jsonPrimitive.content)
        }
        return out
    }

    private fun checkBudget(script: JsonObject, pack: JsonObject): List<JsonObject> {
        val voice = pack.getValue("voice").jsonObject
        val wps = voice.getValue("words_per_second"); val startup = voice.getValue("startup_s")
        val out = ArrayList<JsonObject>()
        for ((key, want) in listOf("words_per_second" to wps, "startup_s" to startup)) {
            val got = script[key]
            if (num(got) == null || num(got) != num(want)) out += Fail(5, "over_budget", "$key is ${pyStr(got)}, the style pack's is ${pyStr(want)}").json("script")
        }
        val wpsD = num(wps)!!; val startupD = num(startup)!!
        val zh = (script["language"] as? JsonPrimitive)?.content == "zh"
        for (l in (script["lines"] as? JsonArray).orEmpty()) {
            val ln = l.jsonObject
            val id = ln.getValue("id").jsonPrimitive.content
            var budget = Math.floor((num(ln["max_duration_s"])!! - startupD) * wpsD + EPS).toLong()
            if (ln.getValue("role").jsonPrimitive.content in KEY_ROLES) budget = maxOf(1L, budget)
            val text = ln.getValue("text").jsonPrimitive.content
            val n = if (zh) SpokenUnits.of(text, "zh") else SPOKEN.findAll(text).count()
            val maxWords = ln.getValue("max_words")
            val mw = num(maxWords)!!
            if (mw != budget.toDouble()) out += Fail(5, "over_budget", "max_words is ${pyStr(maxWords)}, the duration allows $budget").json(id)
            // min(max_words, budget): the first argument unless the second is smaller
            val limit: String = if (budget.toDouble() < mw) budget.toString() else pyStr(maxWords)
            val limitD = if (budget.toDouble() < mw) budget.toDouble() else mw
            if (n > limitD) out += Fail(5, "over_budget", "more than $limit word${if (limit != "1") "s" else ""} ($n)").json(id)
        }
        return out
    }

    /** `checks.check`: every failure as {where, rule, kind, detail}; empty when the script passes. */
    fun check(script: JsonObject, ps: JsonObject, uc: JsonObject, pack: JsonObject): JsonArray {
        val known = corpus(ps, uc)
        val extra = ((pack.getValue("script").jsonObject["banned_terms"] as? JsonArray).orEmpty()).map { it.jsonPrimitive.content }
        val typed = listOf("player_name", "team_home", "team_away").filter { truthy(uc[it]) }.map { pyStr(uc[it]) }
        val zh = (script["language"] as? JsonPrimitive)?.content == "zh"
        fun read(text: String) = if (zh) checkTextZh(text, known, extra, typed) else checkText(text, known, extra)
        val out = ArrayList<JsonObject>()
        for (ln in (script["lines"] as? JsonArray).orEmpty()) {
            val o = ln.jsonObject
            read(o.getValue("text").jsonPrimitive.content).forEach { out += it.json(o.getValue("id").jsonPrimitive.content) }
        }
        for (field in listOf("headline", "lower_third")) if (truthy(script[field])) read(script.getValue(field).jsonPrimitive.content).forEach { out += it.json(field) }
        return JsonArray(out + checkRefs(script, ps) + checkBudget(script, pack))
    }

    companion object {
        private const val EPS = 1e-6
        private val KEY_ROLES = setOf("climax", "replay_climax")
        private val WORD = Regex("[A-Za-z][A-Za-z'\\-]*")
        private val SPOKEN = Regex("[A-Za-z0-9][A-Za-z0-9'\\-]*")
        private val DIGITS = Regex("\\p{Nd}+(?:[.,:]\\p{Nd}+)*")
        private val NOT_COUNTED = setOf("zero", "one")
        private val NUMBER_WORDS: Map<String, Int> = (
            "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty"
                .split(" ").withIndex().associate { it.value to it.index } +
                mapOf("thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100, "thousand" to 1000, "million" to 1000000)
            )
        private val NAMES: Set<String> = """
            aaron adam ahmed alex ali andre andrea andy ben carlos chris daniel david diego eden ethan
            harry jack james jamie jesus joao john jordan jose josh juan kevin leo liam lionel lucas
            luis luka marco mario mark matt max michael mo mohamed neymar noah oliver paul pedro rafa
            ronaldo sam sergio thomas tom zinedine kylian erling cristiano messi pele maradona zidane
            beckham rooney kane salah mbappe haaland
            london paris madrid barcelona manchester liverpool milan rome munich berlin lisbon porto
            wembley anfield brazil argentina england spain france germany italy portugal
            arsenal chelsea juventus bayern ajax celtic rangers
        """.trim().split(Regex("\\s+")).toSet()

        /** How Python writes a JSON number: an integer as its digits, a float by `repr` (shortest round trip; exponent form below 1e-4 or from 1e16). */
        fun pyNumber(text: String): String {
            if (Regex("-?\\d+").matches(text)) return java.math.BigInteger(text).toString()
            val d = text.toDouble()
            if (d.isNaN() || d.isInfinite()) return if (d.isNaN()) "nan" else if (d > 0) "inf" else "-inf"
            if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
            val abs = Math.abs(d)
            val shortest = BigDecimal(java.lang.Double.toString(d)).stripTrailingZeros() // the JDK writes the shortest digits that round-trip
            if (abs >= 1e-4 && abs < 1e16) {
                val s = shortest.toPlainString()
                return if ('.' in s) s else "$s.0"
            }
            val digits = shortest.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val exp = shortest.precision() - shortest.scale() - 1
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            return (if (d < 0) "-" else "") + mantissa + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        }

        fun fromShared(shared: java.io.File): Checks {
            val en = EnglishLexicon(java.io.File(shared, "lexicons/en.json").readText())
            val zhText = java.io.File(shared, "lexicons/zh.json").readText()
            val zhData = Json.parseToJsonElement(zhText).jsonObject
            fun chars(key: String) = zhData.getValue(key).jsonObject.getValue("characters").jsonPrimitive.content.codePoints().toArray().toSet()
            return Checks(en, MandarinLexicon(zhText), chars("gb2312"), chars("traditional_in_gb2312"))
        }
    }
}
