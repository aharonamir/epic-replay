package app.epicreplay.core.checks

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a failure's detail shows a number from the script: as Python's `repr` writes it (values checked in the PC venv). */
class ChecksTest {
    @Test
    fun aJsonNumberIsWrittenTheWayPythonWritesIt(): Unit {
        val want = mapOf(
            "3" to "3", "-7" to "-7", "2.6" to "2.6", "2.60" to "2.6", "0.9" to "0.9", "0.90000001" to "0.90000001", "3.0" to "3.0", "1e2" to "100.0",
            "0.0001" to "0.0001", "0.00001" to "1e-05", "1.5e-7" to "1.5e-07", "1e16" to "1e+16", "123456789012345.6" to "123456789012345.6", "1.25e20" to "1.25e+20",
            "-0.0" to "-0.0", "12345678901234567890" to "12345678901234567890",
        )
        for ((json, py) in want) assertEquals(py, Checks.pyNumber(json), json)
    }
}
