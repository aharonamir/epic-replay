package app.epicreplay.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The first Kotlin test: the shared consent wording (T404) read in place from `shared/copy/`. */
class SharedConsentTest {
    private val shared = SharedDir(File(System.getProperty("shared.dir")))

    @Test
    fun theConsentFileHasTheVersionAndAWordingForEveryPlace() {
        val json = Json.parseToJsonElement(shared.file("copy/consent.json").readText()).jsonObject
        assertEquals("4", json.getValue("text_version").jsonPrimitive.content)
        val lines = json.getValue("lines").jsonObject
        assertEquals(setOf("cloud", "local", "network", "device"), lines.keys)
        assertTrue(lines.getValue("device").jsonPrimitive.content.contains("never leave the phone"))
    }
}
