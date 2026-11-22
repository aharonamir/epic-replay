package app.epicreplay.core.run

import app.epicreplay.core.models.Testing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** FR-006: a run already started keeps the profiles it started with. */
class RunStackTest {
    private val profile = Testing.profile()
    private fun manifest(vision: String, text: String) = Json.parseToJsonElement("""{"stack":{"models":{"vision":{"profile":"$vision","model":"m"},"text":{"profile":"$text","model":"m"}}}}""").jsonObject

    @Test
    fun theProfilesAreTheOnesTheManifestNamesWhateverIsChosenNow() {
        val saved = assertNotNull(RunStack.saved(manifest(profile.id, profile.id), mapOf(profile.id to profile, "other" to profile)))
        assertEquals(profile.id, saved.vision.id); assertEquals(profile.id, saved.text.id)
    }

    @Test
    fun aRunWithNoRecordOrWithAProfileNoLongerOfferedHasNoModelsRatherThanOtherOnes() {
        assertNull(RunStack.saved(null, mapOf(profile.id to profile)))
        assertNull(RunStack.saved(JsonObject(emptyMap()), mapOf(profile.id to profile)))
        assertNull(RunStack.saved(manifest(profile.id, "gone"), mapOf(profile.id to profile)))
        assertNull(RunStack.saved(manifest(profile.id, profile.id), emptyMap()))
    }
}
