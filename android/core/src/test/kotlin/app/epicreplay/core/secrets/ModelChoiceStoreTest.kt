package app.epicreplay.core.secrets

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModelChoiceStoreTest {
    private lateinit var dir: File
    @BeforeTest fun setUp() { dir = Files.createTempDirectory("choice").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() = ModelChoiceStore(File(dir, "settings/model_choice.json"))

    @Test
    fun noChoiceIsTheDefault() {
        assertNull(store().get(Role.VISION))
        assertNull(store().get(Role.TEXT))
    }

    @Test
    fun eachRoleIsChosenOnItsOwnAndSurvivesANewStoreObject() {
        store().set(Role.VISION, "gemma-4-e2b-device")
        store().set(Role.TEXT, "deepseek-flash")
        assertEquals("gemma-4-e2b-device", store().get(Role.VISION))
        assertEquals("deepseek-flash", store().get(Role.TEXT))
        store().set(Role.VISION, null)
        assertNull(store().get(Role.VISION))
        assertEquals("deepseek-flash", store().get(Role.TEXT))
    }

    @Test
    fun aDamagedFileOrAnOddValueIsNoChoice() {
        val f = File(dir, "settings/model_choice.json").apply { parentFile.mkdirs() }
        f.writeText("{not json")
        assertNull(store().get(Role.TEXT))
        f.writeText("""{"vision": "../../etc/passwd", "text": 5}""")
        assertNull(store().get(Role.VISION))
        assertNull(store().get(Role.TEXT))
    }

    @Test
    fun aProfileIdMustLookLikeOne() {
        assertFailsWith<IllegalArgumentException> { store().set(Role.TEXT, "Not An Id") }
    }
}
