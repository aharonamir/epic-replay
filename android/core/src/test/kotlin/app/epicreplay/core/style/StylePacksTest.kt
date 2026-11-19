package app.epicreplay.core.style

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The pack loader, the language resolver and the slots, as `pc/tests/unit/test_styles.py` and `test_languages.py` check them in Python. */
class StylePacksTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val schema = File(shared, "contracts/style_pack.schema.json").readText()
    private val voices = Json.parseToJsonElement(File(shared, "styles/voices.json").readText()).jsonObject
    private fun pack(id: String) = File(shared, "styles/$id.json").readText()
    private fun loader() = StyleLoader(schema, voices, { File(shared, "assets/$it").isFile })

    /** The football pack with `edit` applied, as a file called [name]. */
    private fun variant(name: String, edit: (MutableMap<String, JsonElement>) -> Unit): PackFile {
        val m = Json.parseToJsonElement(pack("football_commentator")).jsonObject.toMutableMap()
        m["id"] = JsonPrimitive(name); m["default"] = JsonPrimitive(false)
        edit(m)
        return PackFile("$name.json", JsonObject(m).toString())
    }

    private fun obj(e: JsonElement?) = e!!.jsonObject.toMutableMap()

    @Test
    fun theSharedPacksLoadInOrderWithOneDefaultAndMandarin(): Unit {
        val packs = StyleLoader.loadShared(shared)
        assertEquals(emptyList(), packs.problems)
        assertEquals(listOf("football_commentator", "nature_documentary", "news_reporter"), packs.packs.keys.sorted())
        assertEquals("football_commentator", packs.default().getValue("id").jsonPrimitive.content)
        for (id in packs.packs.keys) assertTrue("zh" in packs.packs.getValue(id).getValue("languages").jsonObject, "$id has no zh")
        val zh = packs.resolved("news_reporter", "zh")
        assertEquals("zh", zh.getValue("language").jsonPrimitive.content)
        assertFalse("languages" in zh)
        assertEquals(packs.packs.getValue("news_reporter"), packs.resolved("news_reporter", "en"))
        assertFailsWith<NoStyles> { packs.resolved("news_reporter", "fr") }
        assertFailsWith<NoStyles> { packs.resolved("nobody", null) }
    }

    @Test
    fun aPackWithAMissingAssetAnUnknownVoiceOrABrokenSchemaIsRefusedWithTheReason(): Unit {
        val files = listOf(
            PackFile("football_commentator.json", pack("football_commentator")),
            variant("no_roar") { m -> m["sound"] = JsonObject(obj(m["sound"]) + ("climax" to JsonPrimitive("sfx/nothing.wav"))) },
            variant("stranger") { m ->
                val v = obj(m["voice"]); val p = obj(v["primary"]); p["voice_id"] = JsonPrimitive("nobody"); v["primary"] = JsonObject(p); m["voice"] = JsonObject(v)
            },
            variant("loud") { m -> m["sound"] = JsonObject(obj(m["sound"]) + ("bed_gain_db" to JsonPrimitive(6))) },
        )
        val r = loader().load(files)
        assertEquals(listOf("football_commentator"), r.packs.keys.toList())
        fun why(name: String) = r.problems.single { "$name.json refused" in it }
        assertContains(why("no_roar"), "asset sfx/nothing.wav is missing")
        assertContains(why("stranger"), "elevenlabs voice nobody is not known")
        assertContains(why("loud"), "bed_gain_db")
    }

    @Test
    fun twoDefaultsAndNoPacksStopTheStartUp(): Unit {
        val files = listOf(PackFile("football_commentator.json", pack("football_commentator")), variant("second") { it["default"] = JsonPrimitive(true) })
        assertContains(assertFailsWith<NoStyles> { loader().load(files) }.message!!, "exactly one")
        assertFailsWith<NoStyles> { loader().load(emptyList()) }
    }

    @Test
    fun aMandarinBlockThatFailsIsRefusedAloneAndMalformedLanguagesLeaveTheEnglishPack(): Unit {
        fun news(edit: (MutableMap<String, JsonElement>) -> Unit): StylePacks {
            val m = Json.parseToJsonElement(pack("news_reporter")).jsonObject.toMutableMap()
            edit(m)
            return loader().load(listOf(PackFile("news_reporter.json", JsonObject(m).toString()), PackFile("nature_documentary.json", pack("nature_documentary"))).let {
                // football is the default, so it must be there too
                it + PackFile("football_commentator.json", pack("football_commentator"))
            })
        }
        val noLabel = news { m ->
            val zh = obj(obj(m["languages"])["zh"]); val script = obj(zh["script"]); val labels = obj(script["labels"])
            labels.remove("ai_label"); script["labels"] = JsonObject(labels); zh["script"] = JsonObject(script)
            m["languages"] = JsonObject(mapOf("zh" to JsonObject(zh)))
        }
        assertFalse("languages" in noLabel.packs.getValue("news_reporter")) // the pack stays, in English
        assertContains(noLabel.problems.joinToString(), "ai_label")
        assertTrue("languages" in noLabel.packs.getValue("nature_documentary"))
        val bad = news { it["languages"] = JsonPrimitive("zh") }
        assertFalse("languages" in bad.packs.getValue("news_reporter"))
    }

    @Test
    fun aSlowerNarratorGetsFewerWordsAndAKeySlotWithNoRoomRaises(): Unit {
        val sb = Json.parseToJsonElement(File(shared, "fixtures/sample_run/storyboard.json").readText()).jsonObject
        val packs = StyleLoader.loadShared(shared)
        fun words(id: String) = Slots.build(sb, packs.packs.getValue(id), "e6").sumOf { it.jsonObject.getValue("max_words").jsonPrimitive.content.toInt() }
        assertTrue(words("nature_documentary") < words("football_commentator"))
        val tight = JsonObject(sb + ("markers" to JsonObject(sb.getValue("markers").jsonObject + ("climax_replay_t" to JsonPrimitive(26.0)))))
        assertContains(assertFailsWith<NoRoom> { Slots.build(tight, packs.default(), "e6") }.message!!, "replay_climax")
        val keys = Slots.build(sb, packs.default(), "e6").map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertTrue("climax" in keys && "replay_climax" in keys)
        assertEquals(sb.getValue("segments").jsonArray.size > 0, true)
    }
}
