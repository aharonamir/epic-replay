package app.epicreplay.core.models

import app.epicreplay.core.secrets.ModelChoiceStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/** T407: only an id of a loaded profile that lists the role can be chosen (T406 checks only that an id is a safe name). */
class ModelChoiceTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val schema = File(shared, "contracts/model_profile.schema.json")
    private lateinit var tmp: File

    @BeforeTest fun setUp() { tmp = Files.createTempDirectory("choice2").toFile() }
    @AfterTest fun tearDown() { tmp.deleteRecursively() }

    private val default = Json.parseToJsonElement(File(shared, "models/deepseek-flash.json").readText()).jsonObject
    private val textOnly = JsonObjectOf(default, mapOf(
        "id" to JsonPrimitive("text-only"), "model" to JsonPrimitive("text-only-model"), "roles" to JsonArray(listOf(JsonPrimitive("text"))),
        "timeout_s" to buildJsonObject { put("text", JsonPrimitive(60)) },
    ))

    private fun loaded() = ProfileLoader(schema.readText(), Platform.ANDROID).load(
        listOf(ProfileFile("deepseek-flash.json", File(shared, "models/deepseek-flash.json").readText()), ProfileFile("text-only.json", textOnly.toString())),
    )

    private fun choice(store: ModelChoiceStore = ModelChoiceStore(File(tmp, "c.json"))) = ModelChoice(store, loaded())

    @Test
    fun aLoadedProfileThatListsTheRoleCanBeChosen() {
        val c = choice()
        c.choose(Role.VISION, "deepseek-flash"); c.choose(Role.TEXT, "text-only")
        assertEquals("deepseek-flash", c.chosen(Role.VISION)?.id)
        assertEquals("text-only", c.chosen(Role.TEXT)?.id)
        c.clear(Role.TEXT)
        assertNull(c.chosen(Role.TEXT))
    }

    @Test
    fun anUnknownProfileIsRefusedAndNamesTheOnesThereAre() {
        val e = assertFailsWith<ProfileChoiceException> { choice().choose(Role.TEXT, "gemma-4-e2b-local") } // loaded only on the PC
        assertTrue("no usable model profile gemma-4-e2b-local" in e.message!! && "deepseek-flash" in e.message!!)
    }

    @Test
    fun aProfileMustListTheRoleItIsChosenFor() {
        val e = assertFailsWith<ProfileChoiceException> { choice().choose(Role.VISION, "text-only") }
        assertTrue("not for the vision role" in e.message!!)
        assertNull(choice().chosen(Role.VISION)) // nothing was stored
    }

    @Test
    fun aStoredChoiceWhoseProfileIsGoneOrNoLongerQualifiesReadsAsNoChoice() {
        val store = ModelChoiceStore(File(tmp, "c.json"))
        store.set(Role.VISION, "removed-profile")
        store.set(Role.TEXT, "text-only")
        assertNull(ModelChoice(store, loaded()).chosen(Role.VISION))
        store.set(Role.VISION, "text-only") // stored by an older build: the profile does not list the role
        assertNull(ModelChoice(store, loaded()).chosen(Role.VISION))
        assertEquals("text-only", ModelChoice(store, loaded()).chosen(Role.TEXT)?.id)
    }
}

private fun JsonObjectOf(base: kotlinx.serialization.json.JsonObject, more: Map<String, kotlinx.serialization.json.JsonElement>) =
    kotlinx.serialization.json.JsonObject(base + more)
