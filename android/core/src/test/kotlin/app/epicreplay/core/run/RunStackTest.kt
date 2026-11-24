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

    @Test
    fun theUsersOwnServerIsReadFromTheRunsCopyNotFromWhatIsSavedNow() {
        val mine = Testing.profile()
        val snapshot = Json.parseToJsonElement("""{"id":"custom-cloud","model":"old-model"}""").jsonObject
        val manifest = Json.parseToJsonElement("""{"stack":{"custom_profile":{"id":"custom-cloud","model":"old-model"},"models":{"vision":{"profile":"custom-cloud"},"text":{"profile":"custom-cloud"}}}}""").jsonObject
        val now = mapOf("custom-cloud" to Testing.profile())
        val got = RunStack.saved(manifest, now) { snap -> assertEquals(snapshot, snap); mine }
        kotlin.test.assertSame(mine, got!!.vision) // the copy, not the profile of that name that is saved now
        assertNull(RunStack.saved(manifest, now)) // without a way to read the copy there is none
    }
}
