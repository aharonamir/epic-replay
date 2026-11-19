package app.epicreplay.core.text

/** How many units of the word budget a text holds (`styles.slots.spoken_units`): a CJK character in `zh`, else a white space token. */
object SpokenUnits {
    private fun isCjk(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF

    fun of(text: String, language: String = "en"): Int {
        if (language == "zh") {
            var n = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (isCjk(cp)) n++
                i += Character.charCount(cp)
            }
            return n
        }
        var tokens = 0
        var inToken = false
        for (c in text) {
            if (PyText.isSpace(c)) inToken = false
            else if (!inToken) { inToken = true; tokens++ }
        }
        return tokens
    }
}
