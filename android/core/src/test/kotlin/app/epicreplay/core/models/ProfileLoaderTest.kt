package app.epicreplay.core.models

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T407: the Kotlin loader against the cases of `pc/tests/unit/test_models.py` (loading and refusing;
 * the choosing and key cases of that file belong to the PC's settings) and the T403 cases for `on_device`.
 */
class ProfileLoaderTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val schema = File(shared, "contracts/model_profile.schema.json")
    private val modelsDir = File(shared, "models")
    private lateinit var tmp: File
    private val default: JsonObject = Json.parseToJsonElement(File(modelsDir, "deepseek-flash.json").readText()).jsonObject

    @BeforeTest fun setUp() { tmp = Files.createTempDirectory("models").toFile() }
    @AfterTest fun tearDown() { tmp.deleteRecursively() }

    private fun with(base: JsonObject, vararg more: Pair<String, JsonElement>): JsonObject =
        JsonObject(base + more.toMap())

    /** A local server profile: no key, free, as `local()` in the PC test. */
    private fun local(vararg more: Pair<String, JsonElement>): JsonObject = with(
        default,
        "id" to JsonPrimitive("tiny-local"), "label" to JsonPrimitive("Tiny (local)"), "location" to JsonPrimitive("local"),
        "api_base" to JsonPrimitive("http://127.0.0.1:8080/v1"), "model" to JsonPrimitive("tiny-model"), "api_key_env" to JsonNull,
        "price_in_per_m" to JsonPrimitive(0), "price_out_per_m" to JsonPrimitive(0), "call_ceiling_usd" to JsonPrimitive(0),
        "call_mean_usd" to JsonPrimitive(0), "timeout_s" to buildJsonObject { put("vision", JsonPrimitive(300)); put("text", JsonPrimitive(120)) },
        "max_parallel" to JsonPrimitive(1), "status" to JsonPrimitive("experimental"),
    ).let { with(it, *more) }

    private fun phone(vararg more: Pair<String, JsonElement>): JsonObject = with(
        buildJsonObject {
            put("id", JsonPrimitive("phone-model")); put("label", JsonPrimitive("Phone model"))
            put("kind", JsonPrimitive("on_device")); put("location", JsonPrimitive("device"))
            put("roles", JsonArray(listOf(JsonPrimitive("vision"), JsonPrimitive("text")))); put("engine", JsonPrimitive("llama.cpp"))
            put("files", JsonArray(listOf(buildJsonObject {
                put("name", JsonPrimitive("w.gguf")); put("url", JsonPrimitive("https://example.org/w.gguf"))
                put("sha256", JsonPrimitive("0".repeat(64))); put("bytes", JsonPrimitive(10)); put("role", JsonPrimitive("weights"))
            })))
            put("context_tokens", JsonPrimitive(4096)); put("min_free_ram_mb", JsonPrimitive(3000)); put("min_free_storage_mb", JsonPrimitive(4000))
            put("license", JsonPrimitive("Apache-2.0")); put("price_in_per_m", JsonPrimitive(0)); put("price_out_per_m", JsonPrimitive(0))
            put("supports_tools", JsonPrimitive(false)); put("status", JsonPrimitive("experimental")); put("notes", JsonPrimitive("test"))
        },
        *more,
    )

    /** A folder with the default profile and [profiles]; the file name defaults to the profile's id. */
    private fun folder(vararg profiles: Pair<String, String>): List<ProfileFile> =
        listOf(ProfileFile("deepseek-flash.json", File(modelsDir, "deepseek-flash.json").readText())) +
            profiles.map { (name, text) -> ProfileFile("$name.json", text) }

    private fun loader(p: Platform) = ProfileLoader(schema.readText(), p)
    private fun pc(vararg more: Pair<String, String>) = loader(Platform.PC).load(folder(*more))

    // -- the shipped profiles

    @Test
    fun theShippedProfilesAllLoadOnThePcAndHaveTheirOwnModel() {
        val shipped = modelsDir.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }.sorted()
        val r = ProfileLoader.loadDir(modelsDir, schema, Platform.PC)
        assertEquals(emptyList(), r.problems.map { it.file + ": " + it.reason }, "nothing refused or left out on the PC")
        assertEquals(shipped, r.profiles.keys.sorted())
        val models = r.profiles.values.filterIsInstance<ServerProfile>().map { it.model }
        assertEquals(models.size, models.toSet().size) // FR-007
    }

    @Test
    fun androidLoadsTheSharedFolderMinusWhatItCannotRun() {
        // computed from the raw files, not from the loader: the PC list minus profiles at a location the phone does not run
        val raw = modelsDir.listFiles { f -> f.extension == "json" }!!.associate {
            it.nameWithoutExtension to Json.parseToJsonElement(it.readText()).jsonObject
        }
        val pcIds = raw.filterValues { it.getValue("kind").jsonPrimitive.content == "openai_compatible" }.keys.sorted()
        val expected = raw.filter { (_, o) ->
            o.getValue("location").jsonPrimitive.content in setOf("cloud", "device")
        }.keys.sorted()
        assertEquals(pcIds, ProfileLoader.loadDir(modelsDir, schema, Platform.PC).profiles.keys.sorted())
        val android = ProfileLoader.loadDir(modelsDir, schema, Platform.ANDROID)
        assertEquals(expected, android.profiles.keys.sorted())
        assertTrue(android.refused().isEmpty(), "nothing refused on Android: ${android.refused()}")
        assertTrue("deepseek-flash" in android.profiles && "gemma-4-e2b-local" !in android.profiles)
        assertTrue(android.leftOut().all { "cannot run on android" in it.reason })
    }

    @Test
    fun theDefaultProfileHoldsTheValuesTheCodeHad() {
        val p = assertIs<ServerProfile>(ProfileLoader.loadDir(modelsDir, schema, Platform.PC).profiles["deepseek-flash"])
        assertEquals(listOf("https://api.deepseek.com", "deepseek-flash", "cloud"), listOf(p.apiBase, p.model, p.location))
        assertEquals(listOf(Role.VISION, Role.TEXT), p.roles)
        assertEquals(0.30 to 1.20, p.priceInPerM to p.priceOutPerM)
        assertEquals(0.008 to 0.0035, p.callCeilingUsd to p.callMeanUsd)
        assertEquals(45.0 to 60.0, p.timeout(Role.VISION) to p.timeout(Role.TEXT))
        assertEquals(Triple("json_object", "request", true), Triple(p.jsonMode, p.image.detail, p.supportsTools))
        assertEquals(0.0009, p.usd(1000, 500))
    }

    // -- refusing a bad profile (the parametrised case list of test_models.py)

    private fun refused(change: Pair<String, JsonElement>, why: String, file: String = "tiny-local") {
        val r = pc(file to local(change).toString())
        assertEquals(listOf("deepseek-flash"), r.profiles.keys.toList(), "refused $change")
        val reason = r.refused().single().reason
        assertTrue(why in reason, "expected '$why' in: $reason")
    }

    @Test fun aWrongIdIsRefused() = refused("id" to JsonPrimitive("other"), "is not the file name")
    @Test fun noRolesIsRefused() = refused("roles" to JsonArray(emptyList()), "roles")
    @Test fun anUnknownRoleIsRefused() = refused("roles" to JsonArray(listOf(JsonPrimitive("audio"))), "roles")
    @Test fun aTrailingSlashIsRefused() = refused("api_base" to JsonPrimitive("http://x/v1/"), "api_base")
    @Test fun aBaseWithoutASchemeIsRefused() = refused("api_base" to JsonPrimitive("localhost:8080"), "api_base")
    @Test fun aNegativePriceIsRefused() = refused("price_in_per_m" to JsonPrimitive(-1), "price_in_per_m")
    @Test fun anUnknownJsonModeIsRefused() = refused("json_mode" to JsonPrimitive("xml"), "json_mode")
    @Test fun aServerProfileClaimingOnDeviceIsRefused() = refused("kind" to JsonPrimitive("on_device"), "engine")
    @Test fun aCeilingBelowTheMeanIsRefused() =
        run { val r = pc("tiny-local" to local("call_ceiling_usd" to JsonPrimitive(0.001), "call_mean_usd" to JsonPrimitive(0.002)).toString())
            assertTrue("below call_mean_usd" in r.refused().single().reason) }
    @Test fun timeoutsMustHaveExactlyTheRoles() =
        refused("timeout_s" to buildJsonObject { put("vision", JsonPrimitive(45)) }, "timeout_s must have exactly the roles")
    @Test fun aZeroTimeoutIsRefused() =
        refused("timeout_s" to buildJsonObject { put("vision", JsonPrimitive(0)); put("text", JsonPrimitive(5)) }, "timeout_s")
    @Test fun noParallelCallsIsRefused() = refused("max_parallel" to JsonPrimitive(0), "max_parallel")
    @Test fun aLowercaseKeyVariableIsRefused() = refused("api_key_env" to JsonPrimitive("lower"), "api_key_env")
    @Test fun anUnknownFieldIsRefused() = refused("surprise" to JsonPrimitive(1), "surprise")

    @Test
    fun twoProfilesMayNotShareAModelName() {
        val r = pc("twin" to local("id" to JsonPrimitive("twin"), "model" to JsonPrimitive("deepseek-flash")).toString())
        assertEquals(listOf("deepseek-flash"), r.profiles.keys.toList())
        assertTrue("also the model of profile" in r.refused().single().reason)
    }

    @Test
    fun aFileThatIsNotJsonIsLeftOutAndTheOthersLoad() {
        val r = pc("broken" to "{not json")
        assertEquals(listOf("deepseek-flash"), r.profiles.keys.toList())
        assertTrue("not JSON" in r.refused().single().reason)
    }

    @Test
    fun aMissingFolderIsNamed() {
        val e = assertFailsWith<IllegalArgumentException> { ProfileLoader.loadDir(File(tmp, "nope"), schema, Platform.PC) }
        assertTrue("model profiles folder not found" in e.message!!)
    }

    // -- parity with Python on edge cases (review of T407)

    @Test
    fun aWholeNumberWrittenWithADecimalPointIsAcceptedAsPythonDoes() {
        val r = loader(Platform.ANDROID).load(folder("phone-model" to phone("context_tokens" to JsonPrimitive(4096.0), "min_free_ram_mb" to JsonPrimitive(3000.0)).toString()))
        assertEquals(4096, (r.profiles["phone-model"] as OnDeviceProfile).contextTokens)
        val s = pc("tiny-local" to local("max_parallel" to JsonPrimitive(2.0)).toString())
        assertEquals(2, (s.profiles["tiny-local"] as ServerProfile).maxParallel)
    }

    @Test
    fun aLargeExactIntegerIsNeverRoundedThroughADouble() {
        val big = 9007199254740993L // 2^53 + 1: a Double would make it 2^53
        fun withBytes(n: JsonPrimitive) = phone("files" to JsonArray(listOf(buildJsonObject {
            put("name", JsonPrimitive("w.gguf")); put("url", JsonPrimitive("https://example.org/w.gguf"))
            put("sha256", JsonPrimitive("0".repeat(64))); put("bytes", n); put("role", JsonPrimitive("weights"))
        }))).toString()
        val r = loader(Platform.ANDROID).load(folder("phone-model" to withBytes(JsonPrimitive(big))))
        assertEquals(big, (r.profiles["phone-model"] as OnDeviceProfile).files[0].bytes)
        val frac = loader(Platform.ANDROID).load(folder("phone-model" to withBytes(JsonPrimitive(10.5))))
        assertEquals(listOf("phone-model.json"), frac.refused().map { it.file }) // the schema says integer; 10.5 is not
    }

    @Test
    fun aNumberTheSchemaAllowsButAnIntCannotHoldIsRefusedNotACrash() {
        val r = loader(Platform.ANDROID).load(folder("phone-model" to phone("context_tokens" to JsonPrimitive(99999999999L)).toString()))
        assertEquals(listOf("phone-model.json"), r.refused().map { it.file })
        assertTrue("out of range" in r.refused().single().reason)
        assertEquals(listOf("deepseek-flash"), r.profiles.keys.toList())
    }

    @Test
    fun aUnicodeSpaceInAUrlIsRefusedAsPythonRefusesIt() {
        // Python: re.search(r"^https?://[^\s]+[^/\s]$", "http://a\u00a0b/v1") is None; Kotlin's \s is ASCII only
        val r = pc("tiny-local" to local("api_base" to JsonPrimitive("http://a\u00a0b/v1")).toString())
        assertEquals(listOf("tiny-local.json"), r.refused().map { it.file })
        assertTrue("whitespace" in r.refused().single().reason)
        val files = loader(Platform.ANDROID).load(folder("phone-model" to phone("files" to JsonArray(listOf(buildJsonObject {
            put("name", JsonPrimitive("w.gguf")); put("url", JsonPrimitive("https://example.org/w\u2003.gguf"))
            put("sha256", JsonPrimitive("0".repeat(64))); put("bytes", JsonPrimitive(10)); put("role", JsonPrimitive("weights"))
        }))).toString()))
        assertEquals(listOf("phone-model.json"), files.refused().map { it.file })
    }

    @Test
    fun theCostIsRoundedToSixPlacesAsPythonDoes() {
        // expected values are the output of Python's round((i*pin + o*pout)/1e6, 6)
        val cases = listOf(
            listOf(1, 0, 0.5, 0.0, 0.0), listOf(3, 0, 0.5, 0.0, 2e-06), listOf(7, 0, 0.25, 0.0, 2e-06), listOf(1, 1, 0.5, 0.5, 1e-06),
            listOf(1000, 500, 0.30, 1.20, 0.0009), listOf(123456, 7890, 0.27, 1.13, 0.042249), listOf(2, 0, 0.25, 0.0, 0.0), listOf(1500, 0, 0.5, 0.0, 0.00075),
        )
        for (c in cases) {
            val p = (pc("tiny-local" to local("price_in_per_m" to JsonPrimitive(c[2] as Double), "price_out_per_m" to JsonPrimitive(c[3] as Double)).toString())
                .profiles["tiny-local"] as ServerProfile)
            assertEquals(c[4] as Double, p.usd((c[0] as Int).toLong(), (c[1] as Int).toLong()), "case $c")
        }
    }

    @Test
    fun aNotJsonFileSaysWhyInBriefly() {
        val reason = pc("broken" to "{\"a\": ").refused().single().reason
        assertTrue(reason.startsWith("not JSON (") && reason.length < 160, reason)
    }

    // -- T403: the on_device kind and the platform filter

    @Test
    fun anOnDeviceProfileLoadsOnAndroidWithItsFieldsAndIsLeftOutOnThePc() {
        val files = folder("phone-model" to phone().toString())
        val android = loader(Platform.ANDROID).load(files)
        val p = assertIs<OnDeviceProfile>(android.profiles["phone-model"])
        assertEquals(listOf("llama.cpp", "device", 0.0), listOf(p.engine, p.location, p.priceInPerM))
        assertEquals(1, p.files.size); assertEquals("weights", p.files[0].role); assertEquals(3000, p.minFreeRamMb)
        val pc = loader(Platform.PC).load(files)
        assertEquals(listOf("deepseek-flash"), pc.profiles.keys.toList())
        assertEquals(listOf("phone-model.json"), pc.leftOut().map { it.file }) // left out, not refused
        assertTrue(pc.refused().isEmpty())
    }

    @Test
    fun androidLeavesOutServersOnThisComputerAndOnTheNetwork() {
        val files = folder(
            "tiny-local" to local().toString(),
            "net" to local("id" to JsonPrimitive("net"), "location" to JsonPrimitive("network"), "model" to JsonPrimitive("m2")).toString(),
        )
        val android = loader(Platform.ANDROID).load(files)
        assertEquals(listOf("deepseek-flash"), android.profiles.keys.toList())
        assertEquals(listOf("net.json", "tiny-local.json"), android.leftOut().map { it.file }.sorted())
        assertEquals(listOf("deepseek-flash", "net", "tiny-local"), loader(Platform.PC).load(files).profiles.keys.sorted())
    }

    @Test
    fun aLeftOutProfileDoesNotClaimItsModelName() {
        // as models.py: the platform filter runs before the model-name check
        val files = folder("tiny-local" to local("model" to JsonPrimitive("shared-model")).toString(),
            "net" to local("id" to JsonPrimitive("net"), "location" to JsonPrimitive("cloud"), "model" to JsonPrimitive("shared-model")).toString())
        val android = loader(Platform.ANDROID).load(files)
        assertEquals(listOf("deepseek-flash", "net"), android.profiles.keys.toList())
    }

    @Test
    fun aBrokenOnDeviceProfileIsRefusedNotLeftOut() {
        val r = loader(Platform.PC).load(folder("phone-model" to phone("files" to JsonArray(emptyList())).toString()))
        assertEquals(listOf("phone-model.json"), r.refused().map { it.file })
        assertTrue(r.leftOut().isEmpty())
    }

    @Test
    fun anOnDeviceProfileMayNotCarryServerFieldsOrTheOtherWayRound() {
        for (change in listOf("api_base" to JsonPrimitive("http://x/v1"), "serve" to JsonPrimitive("x"), "model" to JsonPrimitive("m"), "location" to JsonPrimitive("local"))) {
            val r = loader(Platform.ANDROID).load(folder("phone-model" to phone(change).toString()))
            assertEquals(listOf("phone-model.json"), r.refused().map { it.file }, "on_device with $change")
        }
        for (extra in listOf("engine" to JsonPrimitive("llama.cpp"), "files" to JsonArray(emptyList()), "license" to JsonPrimitive("x"))) {
            val r = pc("tiny-local" to local(extra).toString())
            assertEquals(listOf("tiny-local.json"), r.refused().map { it.file }, "server with $extra")
        }
    }

    @Test
    fun theOfferedProfilesForARoleAreTheOnesThatListIt() {
        val textOnly = local("id" to JsonPrimitive("t"), "roles" to JsonArray(listOf(JsonPrimitive("text"))),
            "timeout_s" to buildJsonObject { put("text", JsonPrimitive(60)) }, "location" to JsonPrimitive("cloud"), "model" to JsonPrimitive("t-model"))
        val r = loader(Platform.ANDROID).load(folder("t" to textOnly.toString()))
        assertEquals(listOf("deepseek-flash"), r.offeredFor(Role.VISION).map { it.id })
        assertEquals(listOf("deepseek-flash", "t"), r.offeredFor(Role.TEXT).map { it.id })
    }
}
