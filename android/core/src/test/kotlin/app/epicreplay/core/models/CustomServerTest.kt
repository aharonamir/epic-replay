package app.epicreplay.core.models

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 004 FR-CUSTOM-1 and 2: a server of the user's own is checked as typed and becomes a profile of the shared schema. */
class CustomServerTest {
    private val shared = File(System.getProperty("shared.dir"))

    @Test
    fun anAddressMustBeHttpsAndTheModelOneWordAndAPriceANumber() {
        fun p(base: String = "https://api.example.com/v1", model: String = "gpt-x", pin: String = "0.5", pout: String = "1.5", label: String = "My server") = CustomServers.problem(label, base, model, pin, pout)
        assertNull(p()); assertNull(p(pin = "", pout = " ")); assertNull(p(model = "org/model-7b:q4"))
        assertTrue(p(base = "http://api.example.com/v1")!!.contains("https://")); assertTrue(p(base = "https://")!!.contains("not a server address"))
        assertTrue(p(base = "https://a b.com")!!.contains("not a server address")); assertTrue(p(base = "https://x.com/v1?key=1")!!.contains("not a server address"))
        assertTrue(p(model = "two words")!!.contains("model name")); assertTrue(p(model = "")!!.contains("model name"))
        assertTrue(p(pin = "-1")!!.contains("price")); assertTrue(p(pout = "cheap")!!.contains("price")); assertTrue(p(label = "x".repeat(41))!!.contains("40"))
    }

    @Test
    fun theServerIsTrimmedAndTheProfileItMakesFitsTheSharedSchemaAndIsOfferedForBothRoles() {
        val server = assertNotNull(CustomServers.of("  ", " https://api.example.com/v1/ ", " my-model ", "0.4", "1.6", true))
        assertEquals(CustomServer("My server", "https://api.example.com/v1", "my-model", 0.4, 1.6, true), server)
        val schema = File(shared, "contracts/model_profile.schema.json").readText()
        val loaded = ProfileLoader(schema, Platform.ANDROID).load(listOf(ProfileFile(CustomServers.FILE, CustomServers.profileJson(server).toString())))
        assertEquals(emptyList(), loaded.problems)
        val p = assertIs<ServerProfile>(loaded.profiles.getValue(CustomServers.ID))
        assertEquals(listOf(Role.VISION, Role.TEXT), p.roles); assertEquals("cloud", p.location); assertEquals("https://api.example.com/v1", p.apiBase); assertEquals("my-model", p.model)
        assertEquals(0.0104, p.callCeilingUsd, 1e-9) // 20,000 tokens in and 1,500 out at 0.4 and 1.6 per million: the way deepseek-flash's 0.008 comes from 0.30 and 1.20
        assertTrue(p.supportsTools && p.apiKeyEnv != null) // a key is sent
    }

    @Test
    fun theStoreKeepsTheServerAndTheProfileFileTheLoaderReads() {
        val f = File(Files.createTempDirectory("custom").toFile(), "settings/custom-cloud.json")
        val store = CustomServerStore(f)
        assertNull(store.load()); assertNull(store.profileFile())
        val server = CustomServer("Mine", "https://x.example/v1", "m1", 0.0, 0.0, false)
        store.save(server)
        assertEquals(server, store.load()); assertEquals(CustomServers.FILE, store.profileFile()!!.name)
        store.delete(); assertNull(store.load())
    }
}
