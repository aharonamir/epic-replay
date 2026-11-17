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

    @Test
    fun numbersAreWrittenAsPythonWritesTheValuePythonReads() {
        // each pair is json.loads(text) written by json.dumps, from Python 3.12
        val table = mapOf(
            "1E5" to "100000.0", "-0" to "0", "0.10" to "0.1", "1.0e-6" to "1e-06", "12345678901234567890" to "12345678901234567890",
            "1e22" to "1e+22", "1.5E+16" to "1.5e+16", "100000.0" to "100000.0", "0.0001" to "0.0001", "0.00001" to "1e-05",
            "1e-5" to "1e-05", "123456789012345678" to "123456789012345678", "-1.5" to "-1.5", "2.50" to "2.5", "1e16" to "1e+16",
            "9999999999999998" to "9999999999999998", "1.7976931348623157e308" to "1.7976931348623157e+308", "5e-324" to "5e-324",
            "0.1" to "0.1", "3.0" to "3.0", "-0.0" to "-0.0", "1e15" to "1000000000000000.0", "123456.789e3" to "123456789.0",
            "0.000123" to "0.000123", "1e-4" to "0.0001", "1.0" to "1.0", "0" to "0", "7" to "7", "1e0" to "1.0", "0.5e1" to "5.0", "1e400" to "Infinity", "-1e400" to "-Infinity", "1e-400" to "0.0",
        )
        for ((text, want) in table) assertEquals(want, PythonJson.number(text), "number $text")
        assertEquals("""{"a":[100000.0,0,1e-06]}""", PythonJson.canonical(parse("""{"a": [1E5, -0, 1.0e-6]}""")))
    }

    @Test
    fun theIndentedDumpIsWhatPythonWritesForARecording() {
        // json.dumps({"a": [1, {"b": None, "c": "好"}], "d": {}, "e": [], "f": 'x"y'}, indent=2, ensure_ascii=False)
        val expected = "{\n  \"a\": [\n    1,\n    {\n      \"b\": null,\n      \"c\": \"好\"\n    }\n  ],\n  \"d\": {},\n  \"e\": [],\n  \"f\": \"x\\\"y\"\n}"
        assertEquals(expected, PythonJson.dumpsIndented(parse("""{"a": [1, {"b": null, "c": "好"}], "d": {}, "e": [], "f": "x\"y"}""")))
    }
}
