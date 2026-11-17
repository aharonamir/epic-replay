package app.epicreplay.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** The expected strings are what Python 3.12's `json.dumps` printed for the same values (see each comment). */
class PythonJsonTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test
    fun canonicalSortsKeysWritesNoSpacesAndKeepsNonAscii() {
        // json.dumps({"b": 1, "a": [1.0, 2, "好球！"], "c": None, "d": True}, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        assertEquals("""{"a":[1.0,2,"好球！"],"b":1,"c":null,"d":true}""",
            PythonJson.canonical(parse("""{"b": 1, "a": [1.0, 2, "好球！"], "c": null, "d": true}""")))
    }

    @Test
    fun numbersKeepTheirOwnText() {
        assertEquals("""{"t":45.0,"u":60,"v":1e-06,"w":0.0009}""", PythonJson.canonical(parse("""{"w":0.0009,"v":1e-06,"u":60,"t":45.0}""")))
    }

    @Test
    fun keysAreSortedByCodePointNotByUtf16Unit() {
        // U+1F600 is the pair D83D DE00 in UTF-16, which sorts below U+FF5E in Java; Python sorts by code point, so the emoji comes last
        assertEquals("{\"～\":1,\"😀\":2}", PythonJson.canonical(parse("{\"😀\": 2, \"～\": 1}")))
    }

    @Test
    fun dumpsKeepsOrderUsesPythonSeparatorsAndEscapesNonAscii() {
        // json.dumps({"z": 1, "a": ["\u597d", "\u00e9", "\U0001F600", "\x7f", "a\"b\\c\n"]}) printed the expected text below
        val expected = """{"z": 1, "a": ["\u597d", "\u00e9", "\ud83d\ude00", "\u007f", "a\"b\\c\n"]}"""
        val input = "{\"z\": 1, \"a\": [\"\u597d\", \"\u00e9\", \"\uD83D\uDE00\", \"\u007f\", \"a\\\"b\\\\c\\n\"]}"
        assertEquals(expected, PythonJson.dumps(parse(input)))
    }

    @Test
    fun controlCharactersAreEscapedAsPythonDoes() {
        // json.dumps("\x00\x1f\b\f\t") == '"\\u0000\\u001f\\b\\f\\t"'
        assertEquals("\"\\u0000\\u001f\\b\\f\\t\"", PythonJson.canonical(Json.parseToJsonElement("\"\\u0000\\u001f\\b\\f\\t\"")))
    }

    @Test
    fun theHashIsSha256OfTheUtf8Bytes() {
        // hashlib.sha256("好".encode()).hexdigest()
        assertEquals("f867f34178594f891eb70a4260f62b1d9289cfa60394f9fee1cbbe4a8b4bf436", PythonJson.sha256Hex("好"))
    }
}
