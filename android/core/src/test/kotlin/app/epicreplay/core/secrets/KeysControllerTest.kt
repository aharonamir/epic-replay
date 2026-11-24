package app.epicreplay.core.secrets

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysControllerTest {
    private lateinit var dir: File
    private lateinit var c: KeysController
    private lateinit var store: SecretStore

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("keys").toFile()
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store = SecretStore(File(dir, "secrets"), AesGcmCipher { k })
        c = KeysController(store)
    }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun theScreenStartsWithBothKeysAbsent() {
        assertEquals(listOf(KeyStatus.ABSENT, KeyStatus.ABSENT, KeyStatus.ABSENT), c.rows().map { it.status }) // the provider, the user's own server and the voice
        assertTrue(c.rows().all { it.masked == null })
    }

    @Test
    fun savingShowsAMaskedKeyAndReplacingChangesIt() {
        assertNull(c.save(KeyKind.MODEL_PROVIDER, "  sk-abcdefghijklmnop1111\n"))
        val row = c.rows().first { it.kind == KeyKind.MODEL_PROVIDER }
        assertEquals(KeyStatus.PRESENT, row.status)
        assertEquals("••••••••1111", row.masked)
        assertNull(c.save(KeyKind.MODEL_PROVIDER, "sk-abcdefghijklmnop2222"))
        assertEquals("••••••••2222", c.rows().first { it.kind == KeyKind.MODEL_PROVIDER }.masked)
    }

    @Test
    fun deleteRemovesTheKey() {
        c.save(KeyKind.ELEVENLABS, "xi-abcdefghijklmnop")
        assertNull(c.delete(KeyKind.ELEVENLABS))
        assertEquals(KeyStatus.ABSENT, c.rows().first { it.kind == KeyKind.ELEVENLABS }.status)
    }

    @Test
    fun badInputIsRefusedByReasonAndNeverEchoed() {
        val cases = mapOf("" to "first", "abc" to "short", "has a space in it 12345" to "spaces", "x".repeat(600) to "long")
        for ((typed, _) in cases) {
            val reason = c.save(KeyKind.MODEL_PROVIDER, typed)
            assertTrue(reason != null)
            assertFalse(typed.length > 3 && reason!!.contains(typed))
        }
        assertEquals(KeyStatus.ABSENT, c.rows().first { it.kind == KeyKind.MODEL_PROVIDER }.status)
    }
}
