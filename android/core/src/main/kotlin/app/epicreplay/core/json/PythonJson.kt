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

    /** `json.dumps(x, indent=2, ensure_ascii=False)`: how a recording is written. Key order kept. */
    fun dumpsIndented(e: JsonElement, indent: Int = 2): String = StringBuilder().also { writeIndented(e, it, indent, 0) }.toString()

    private fun writeIndented(e: JsonElement, out: StringBuilder, indent: Int, level: Int) {
        val pad = " ".repeat(indent * (level + 1))
        val close = " ".repeat(indent * level)
        when {
            e is JsonObject && e.isNotEmpty() -> {
                out.append("{\n")
                e.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(",\n")
                    out.append(pad); string(k, out, false); out.append(": "); writeIndented(v, out, indent, level + 1)
                }
                out.append("\n").append(close).append('}')
            }
            e is JsonArray && e.isNotEmpty() -> {
                out.append("[\n")
                e.forEachIndexed { i, v ->
                    if (i > 0) out.append(",\n")
                    out.append(pad); writeIndented(v, out, indent, level + 1)
                }
                out.append("\n").append(close).append(']')
            }
            else -> write(e, out, sort = false, compact = false, ascii = false) // scalars and empty {} and []
        }
    }

    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    /** A JSON number as Python's `json.dumps` writes the value Python's `json.loads` reads: `1E5` is `100000.0`, `-0` is `0`, `0.10` is `0.1`, `1e-5` is `1e-05`. */
    fun number(text: String): String {
        if (text == "true" || text == "false" || text == "null") return text // literals ride in the same primitive type
        if (INTEGER.matches(text)) return java.math.BigInteger(text).toString()
        val d = text.toDouble()
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity" // Python reads 1e400 as inf and writes it so
        if (d.isNaN()) return "NaN"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        return (if (d < 0) "-" else "") + pythonRepr(Math.abs(d))
    }

    /** repr(float): the shortest digits that read back as the same double, laid out as Python does. */
    private fun pythonRepr(d: Double): String {
        val exact = java.math.BigDecimal(d)
        var digits = ""
        var decpt = 0
        for (p in 1..17) {
            val r = exact.round(java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN))
            if (r.toDouble() == d) {
                val unscaled = r.unscaledValue().toString().trimEnd('0').ifEmpty { "0" }
                digits = unscaled
                decpt = r.precision() - r.scale() // the point sits after this many digits of the unrounded-to-p value
                break
            }
        }
        return if (decpt <= -4 || decpt > 16) {
            val exp = decpt - 1
            val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            mant + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        } else if (decpt <= 0) {
            "0." + "0".repeat(-decpt) + digits
        } else if (decpt >= digits.length) {
            digits + "0".repeat(decpt - digits.length) + ".0"
        } else {
            digits.substring(0, decpt) + "." + digits.substring(decpt)
        }
    }

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
            is JsonPrimitive -> if (e.isString) string(e.content, out, ascii) else out.append(number(e.content))
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
