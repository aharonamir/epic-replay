package app.epicreplay.core.text

/**
 * The few rules of Python's `str` the shared word-list and word-budget rules lean on, written out so the Kotlin port
 * reads text the way the Python code does (conformance areas `lexicon` and `units`).
 */
object PyText {
    /** The characters Python's `str.isspace`, `str.split()` and the regex `\s` treat as white space. */
    fun isSpace(c: Char): Boolean = when (c.code) {
        in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** `\s` as a character class that means the same in `java.util.regex`. */
    const val SPACE_CLASS = "[\\t\\n\\u000B\\f\\r\\u001C-\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

    /** `s.split()` of Python: the pieces between runs of white space, none empty. */
    fun splitWords(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (isSpace(c)) { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } } else cur.append(c)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** `s[:n]` of Python: the first [n] code points. */
    fun take(s: String, n: Int): String = if (length(s) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    /** `len(s)` of Python: code points, not UTF-16 units. */
    fun length(s: String): Int = s.codePointCount(0, s.length)

    /** Python's string order: by code point (Kotlin's own order is by UTF-16 unit, which differs above the surrogates). */
    val CODE_POINT_ORDER: Comparator<String> = Comparator { a, b ->
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

    /** The characters `re.escape` puts a backslash before (Python 3.7 and later). */
    private const val ESCAPED = "()[]{}?*+-|^$\\.&~# \t\n\u000B\u000C\r"

    /**
     * A term as the word-list matching reads it: lower-cased, regex-escaped, each space meaning one or more white
     * space characters. [python] is the pattern as Python builds it (its length orders the alternatives); [java] is
     * the same pattern for `java.util.regex`.
     */
    class TermPattern(val python: String, val java: String)

    fun termPattern(term: String): TermPattern {
        val py = StringBuilder()
        val jv = StringBuilder()
        for (c in term.lowercase(java.util.Locale.ROOT)) {
            when {
                c == ' ' -> { py.append("\\s+"); jv.append(SPACE_CLASS).append('+') }
                c in ESCAPED -> {
                    py.append('\\').append(c)
                    if (c.code in 0x09..0x0D) jv.append(c) else jv.append('\\').append(c)
                }
                else -> { py.append(c); jv.append(c) }
            }
        }
        return TermPattern(py.toString(), jv.toString())
    }
}
