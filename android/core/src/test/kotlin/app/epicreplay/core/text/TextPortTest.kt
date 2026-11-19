package app.epicreplay.core.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edge rules of the text port that the shared cases cannot hold (they are about Kotlin's own differences from Python). */
class TextPortTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val en = EnglishLexicon(File(shared, "lexicons/en.json").readText())
    private val zh = MandarinLexicon(File(shared, "lexicons/zh.json").readText())

    @Test
    fun stringsAreOrderedByCodePointNotByUtf16Unit() {
        // U+FF5E is one unit 0xFF5E; U+20000 is the surrogates 0xD840 0xDC00: Kotlin's compareTo puts the second first, Python puts it last
        val bmp = "～"; val astral = "𠀀"
        assertTrue(bmp.compareTo(astral) > 0) // the UTF-16 order, which would be wrong
        assertTrue(PyText.CODE_POINT_ORDER.compare(bmp, astral) < 0)
        assertEquals(0, PyText.CODE_POINT_ORDER.compare("ab", "ab"))
        assertTrue(PyText.CODE_POINT_ORDER.compare("ab", "abc") < 0)
    }

    @Test
    fun theLengthOfAPatternCountsCodePointsAndEscapesAndSpaces() {
        assertEquals("a\\s+b", PyText.termPattern("A B").python)
        assertEquals(5, PyText.length(PyText.termPattern("A B").python))
        assertEquals("a\\-b\\.", PyText.termPattern("a-b.").python)
        assertEquals(2, PyText.length("𠀀𠀁")) // two code points
    }

    @Test
    fun aPacksOwnTermsFollowTheGlobalOnesAndDuplicatesAreListedTwice() {
        assertEquals(listOf("kid", "kid"), en.findBanned("the kid", listOf("kid")))
        assertEquals(listOf("kid"), en.findBanned("the kid", emptyList()))
        assertEquals(emptyList(), en.findBanned("", emptyList()))
    }

    @Test
    fun aNumeralRunReadsLikePythonForTheEdgeRuns() {
        assertEquals(listOf("0"), zh.numbersIn("零"))
        assertEquals(listOf("10000"), zh.numbersIn("万"))
        assertEquals(listOf("20000"), zh.numbersIn("两万"))
        assertEquals(listOf("100010000"), zh.numbersIn("一万万")) // the documented algorithm's value, as Python gives it
        assertEquals(emptyList(), zh.numbersIn("一"))
        assertEquals(listOf("2026"), zh.numbersIn("二〇二六"))
        assertEquals(listOf("15", "203"), zh.numbersIn("十五和两百零三"))
    }

    @Test
    fun spokenUnitsOfTheEmptyAndTheSpaceOnly() {
        assertEquals(0, SpokenUnits.of("")); assertEquals(0, SpokenUnits.of("    　")); assertEquals(0, SpokenUnits.of("", "zh"))
        assertEquals(3, SpokenUnits.of("好球啊!", "zh"))
    }
}
