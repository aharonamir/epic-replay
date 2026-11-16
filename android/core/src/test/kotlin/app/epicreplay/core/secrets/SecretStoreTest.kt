package app.epicreplay.core.secrets

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T406: the key store on a fake keystore (an in-memory AES key, the same cipher code the phone runs). */
class SecretStoreTest {
    private lateinit var dir: File
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher { key }
    private val sentinel = "sk-TESTKEY-0123456789abcdefXYZ"

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("secrets").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store(c: SecretCipher = cipher) = SecretStore(File(dir, "secrets"), c)

    @Test
    fun aKeyIsWrittenOnlyAsCiphertextAndReadBackByAnotherStoreObject() {
        store().put(KeyKind.MODEL_PROVIDER, Secret(sentinel))
        val bytes = File(dir, "secrets/model_provider.bin").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("TESTKEY"), "the key is on disk in the clear")
        assertFalse(bytes.toList().windowed(8).any { w -> w == sentinel.toByteArray().take(8) })
        assertEquals(Secret(sentinel), store().get(KeyKind.MODEL_PROVIDER)) // a new store: "after a restart"
        assertEquals(KeyStatus.PRESENT, store().status(KeyKind.MODEL_PROVIDER))
    }

    @Test
    fun theSameKeyEncryptsToDifferentBlobsEveryTime() {
        val a = cipher.encrypt(sentinel.toByteArray())
        val b = cipher.encrypt(sentinel.toByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun replaceAndDeleteWork() {
        val s = store()
        s.put(KeyKind.ELEVENLABS, Secret("first-key-12345678"))
        s.put(KeyKind.ELEVENLABS, Secret("second-key-1234567"))
        assertEquals(Secret("second-key-1234567"), s.get(KeyKind.ELEVENLABS))
        s.delete(KeyKind.ELEVENLABS)
        assertNull(s.get(KeyKind.ELEVENLABS))
        assertEquals(KeyStatus.ABSENT, s.status(KeyKind.ELEVENLABS))
        s.delete(KeyKind.ELEVENLABS) // deleting nothing is fine
    }

    @Test
    fun theTwoKeysAreSeparate() {
        val s = store()
        s.put(KeyKind.MODEL_PROVIDER, Secret("model-key-12345678"))
        assertEquals(KeyStatus.ABSENT, s.status(KeyKind.ELEVENLABS))
    }

    @Test
    fun aKeyThatCannotBeDecryptedReadsAsUnreadableNotAsAKey() {
        store().put(KeyKind.MODEL_PROVIDER, Secret(sentinel))
        val other = AesGcmCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() } // the Keystore key is gone
        assertEquals(KeyStatus.UNREADABLE, store(other).status(KeyKind.MODEL_PROVIDER))
        assertNull(store(other).get(KeyKind.MODEL_PROVIDER))
    }

    @Test
    fun aDamagedOrShortFileIsUnreadable() {
        store().put(KeyKind.MODEL_PROVIDER, Secret(sentinel))
        val f = File(dir, "secrets/model_provider.bin")
        val b = f.readBytes(); b[b.size - 1] = (b[b.size - 1] + 1).toByte(); f.writeBytes(b) // the tag no longer matches
        assertEquals(KeyStatus.UNREADABLE, store().status(KeyKind.MODEL_PROVIDER))
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(KeyStatus.UNREADABLE, store().status(KeyKind.MODEL_PROVIDER))
        f.writeBytes(ByteArray(0))
        assertEquals(KeyStatus.UNREADABLE, store().status(KeyKind.MODEL_PROVIDER))
    }

    @Test
    fun anEmptyKeyIsRefused() {
        assertFailsWith<SecretStoreException> { store().put(KeyKind.MODEL_PROVIDER, Secret("   ")) }
    }

    @Test
    fun aFailingCipherLeavesTheOldKeyAndAMessageWithoutTheKey() {
        val s = store()
        s.put(KeyKind.MODEL_PROVIDER, Secret("old-key-1234567890"))
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw IllegalStateException("boom " + String(plain))
            override fun decrypt(blob: ByteArray): ByteArray = cipher.decrypt(blob)
        }
        val e = assertFailsWith<SecretStoreException> { store(broken).put(KeyKind.MODEL_PROVIDER, Secret(sentinel)) }
        assertFalse(e.message!!.contains("TESTKEY"), "the message holds the key")
        assertFalse(e.cause?.message?.contains("TESTKEY") ?: false, "the cause holds the key")
        assertEquals(Secret("old-key-1234567890"), s.get(KeyKind.MODEL_PROVIDER))
        assertTrue(File(dir, "secrets").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun nothingThatCanBePrintedHoldsTheKey() {
        val s = store()
        s.put(KeyKind.MODEL_PROVIDER, Secret(sentinel))
        val secret = assertNotNull(s.get(KeyKind.MODEL_PROVIDER))
        val rows = KeysController(s).rows()
        val printed = listOf(secret.toString(), "$secret", rows.toString(), s.toString(), secret.masked(),
            KeyKind.entries.toString(), KeysController(s).toString())
        printed.forEach { assertFalse(it.contains("TESTKEY") || it.contains(sentinel), "printed: $it") }
        assertFalse(secret.masked().contains("0123456789"))
        assertTrue(secret.masked().endsWith("cdefXYZ".takeLast(4)) || secret.masked().endsWith("XYZ")) // at most the last four
    }

    @Test
    fun aShortKeyIsMaskedWithoutAnyOfItsCharacters() {
        assertEquals("••••••••", Secret("short-key-12").masked())
    }
}
