package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.KeyStatus
import app.epicreplay.core.secrets.Secret
import app.epicreplay.secrets.AndroidKeystoreKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T406 on the phone, with the real Keystore. `writeKey` and `readKey` are run as two separate
 * `am instrument` calls with the app process killed between them, which is the "restart".
 */
@RunWith(AndroidJUnit4::class)
class KeyStoreOnDeviceTest {
    private val sentinel = "sk-DEVICETEST-0123456789abcdef"
    private val graph get() = AppGraph(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun writeKey() {
        graph.secrets.put(KeyKind.MODEL_PROVIDER, Secret(sentinel))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "secrets/model_provider.bin")
        assertTrue(file.isFile)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("DEVICETEST"))
    }

    @Test
    fun readKey() {
        assertEquals(KeyStatus.PRESENT, graph.secrets.status(KeyKind.MODEL_PROVIDER))
        assertEquals(Secret(sentinel), graph.secrets.get(KeyKind.MODEL_PROVIDER))
        assertEquals("••••••••cdef", graph.keys.rows().first { it.kind == KeyKind.MODEL_PROVIDER }.masked)
    }

    @Test
    fun aDeletedKeystoreKeyMakesTheStoredKeyUnreadable() {
        graph.secrets.put(KeyKind.ELEVENLABS, Secret(sentinel))
        AndroidKeystoreKey.delete()
        assertEquals(KeyStatus.UNREADABLE, graph.secrets.status(KeyKind.ELEVENLABS))
        graph.secrets.delete(KeyKind.ELEVENLABS)
        assertEquals(KeyStatus.ABSENT, graph.secrets.status(KeyKind.ELEVENLABS))
    }

    @Test
    fun cleanUp() {
        graph.secrets.delete(KeyKind.MODEL_PROVIDER)
        graph.secrets.delete(KeyKind.ELEVENLABS)
    }
}
