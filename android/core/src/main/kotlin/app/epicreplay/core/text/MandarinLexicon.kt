package app.epicreplay.core.text

import java.math.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Mandarin banned lists, famous names and numerals (`shared/lexicons/zh.json`, rules in `shared/lexicons/README.md`):
 * a port of `guards/lexicon_zh.py` (conformance area `lexicon`).
 */
class MandarinLexicon(json: String) {
    private val data = Json.parseToJsonElement(json).jsonObject

    private fun keys(obj: JsonObject, vararg path: String): List<String> {
        var o = obj
        for (p in path) o = o.getValue(p).jsonObject
        return o.keys.toList()
    }

    private val femalePronoun = data.getValue("female_pronoun").jsonObject.getValue("term").jsonPrimitive.content
    private val famousNames = keys(data, "famous_names", "terms")
    private val allowed = keys(data, "allowed", "terms")
    private val allowedWhen = keys(data, "allowed_when", "patterns").map { Regex(it) }
    private val notNumbers = keys(data, "not_numbers", "terms")
    private val notNumbersWhen = keys(data, "not_numbers_when", "patterns").map { Regex(it) }
    private val numeralRun = Regex(data.getValue("numeral_run").jsonPrimitive.content)
    private val digit: Map<Char, Int> = data.getValue("digits").jsonObject.entries.associate { (k, v) -> k.single() to v.jsonPrimitive.content.toInt() }
    private val unit: Map<Char, Int> = data.getValue("units").jsonObject.entries.associate { (k, v) -> k.single() to v.jsonPrimitive.content.toInt() }

    /** Every banned term, each once, in the order of the categories `banned` names (a term in two categories keeps its first place). */
    private val banned: List<String> = LinkedHashSet<String>().also { set ->
        for (cat in data.getValue("banned").jsonArray) set += keys(data, "categories", cat.jsonPrimitive.content, "terms")
    }.toList()

    private fun masked(text: String, phrases: List<String>, patterns: List<Regex>): String {
        var t = text
        for (p in phrases.sortedByDescending { PyText.length(it) }) t = t.replace(p, "□".repeat(PyText.length(p)))
        for (p in patterns) t = p.replace(t) { m -> "□".repeat(PyText.length(m.value)) }
        return t
    }

    /** The banned terms in [text]: the lists, then a pack's own [extra] terms in Chinese, then 她. */
    fun findBanned(text: String, extra: List<String> = emptyList()): List<String> {
        val m = masked(text, allowed, allowedWhen)
        val found = (banned + extra).filter { it in m }.toMutableList()
        if (femalePronoun in m) found += femalePronoun
        return found
    }

    /** The listed names and places that occur in [text] (an invented name unless the user typed it). */
    fun famousNames(text: String): List<String> {
        val m = masked(text, allowed, allowedWhen)
        return famousNames.filter { it in m }
    }

    private fun value(run: String): BigInteger {
        if (run.all { it in digit }) return BigInteger(run.map { digit.getValue(it) }.joinToString(""))
        var total = BigInteger.ZERO
        var section = 0L
        var cur = 0L
        for (c in run) {
            if (c in digit) cur = digit.getValue(c).toLong()
            else if (c == '万') {
                total = (total + BigInteger.valueOf(section + maxOf(cur, if (section != 0L) 0L else 1L))) * BigInteger.valueOf(10000)
                section = 0; cur = 0
            } else {
                section += maxOf(cur, 1L) * unit.getValue(c)
                cur = 0
            }
        }
        return total + BigInteger.valueOf(section + cur)
    }

    /** The Chinese numerals in [text] as decimal strings, in numeric order (一 alone is not counted). */
    fun numbersIn(text: String): List<String> {
        val m = masked(text, notNumbers, notNumbersWhen)
        return numeralRun.findAll(m).map { it.value }.filter { it != "一" }.map { value(it) }.toSortedSet().map { it.toString() }
    }
}
