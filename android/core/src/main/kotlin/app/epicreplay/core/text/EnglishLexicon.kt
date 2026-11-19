package app.epicreplay.core.text

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The English banned and descriptor lists (`shared/lexicons/en.json`), matched as `shared/lexicons/README.md` says:
 * whole word, case-insensitive, longest first, no ASCII letter or digit beside a match. A port of `guards/lexicon.py`
 * (conformance area `lexicon`).
 */
class EnglishLexicon(json: String) {
    private val data = Json.parseToJsonElement(json).jsonObject
    private val terms: Map<String, List<String>> =
        data.getValue("categories").jsonObject.mapValues { (_, v) -> v.jsonObject.getValue("terms").jsonArray.map { it.jsonPrimitive.content } }

    private fun listOf(key: String) = data.getValue(key).jsonArray.flatMap { terms.getValue(it.jsonPrimitive.content) }

    val globalBanned: List<String> = listOf("global_banned")
    val descriptorBanned: List<String> = listOf("descriptor_banned")

    private val global = pattern(globalBanned)
    private val descriptor = pattern(descriptorBanned)

    /** The banned terms in [text]: the global list, then a style pack's own [extra] terms. */
    fun findBanned(text: String, extra: List<String> = emptyList()): List<String> {
        val found = global.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toMutableList()
        if (extra.isNotEmpty()) found += pattern(extra).findAll(text).map { it.value.lowercase(Locale.ROOT) }
        return found
    }

    /** Age words, female terms, appearance and ability words in [text] (the play sheet's descriptor rules). */
    fun findDescriptorBanned(text: String): List<String> = descriptor.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()

    private fun pattern(list: List<String>): Regex {
        // the alternatives are the distinct patterns, longest first; the length is the one of Python's pattern text
        val alts = list.map { PyText.termPattern(it) }
            .distinctBy { it.python }
            .sortedWith(compareByDescending<PyText.TermPattern> { PyText.length(it.python) }.thenBy { it.python })
        return Regex("(?<![A-Za-z0-9])(?:" + alts.joinToString("|") { it.java } + ")(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    }
}
