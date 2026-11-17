package app.epicreplay.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * JSON text as Python writes it, because requests and recording keys are compared byte for byte with
 * the PC's (`shared/contracts/model-backend.md`, "Recording key"). Numbers keep the text they were
 * read with or made from (`45.0` stays `45.0`).
 */
object PythonJson {
    /** `json.dumps(x, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`. */
    fun canonical(e: JsonElement): String = StringBuilder().also { write(e, it, sort = true, compact = true, ascii = false) }.toString()

    /** `json.dumps(x)`: key order kept, separators `", "` and `": "`, non-ASCII escaped. */
    fun dumps(e: JsonElement): String = StringBuilder().also { write(e, it, sort = false, compact = false, ascii = true) }.toString()

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Python compares str by code point; Java's String order is by UTF-16 unit, which differs above U+FFFF. */
    private val byCodePoint = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }

    private fun write(e: JsonElement, out: StringBuilder, sort: Boolean, compact: Boolean, ascii: Boolean) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) string(e.content, out, ascii) else out.append(e.content)
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, x ->
                    if (i > 0) out.append(if (compact) "," else ", ")
                    write(x, out, sort, compact, ascii)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                val keys = if (sort) e.keys.sortedWith(byCodePoint) else e.keys.toList()
                keys.forEachIndexed { i, k ->
                    if (i > 0) out.append(if (compact) "," else ", ")
                    string(k, out, ascii)
                    out.append(if (compact) ":" else ": ")
                    write(e.getValue(k), out, sort, compact, ascii)
                }
                out.append('}')
            }
        }
    }

    private fun string(s: String, out: StringBuilder, ascii: Boolean) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                ascii && (c > '~') -> out.append("\\u%04x".format(c.code)) // a surrogate pair becomes two escapes, as in Python
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
