package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.budget.PaidResult
import app.epicreplay.core.budget.paidCall
import app.epicreplay.core.models.LabeledImage
import app.epicreplay.core.models.Platform
import app.epicreplay.core.models.ProfileFile
import app.epicreplay.core.models.ProfileLoader
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpResponse
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.record.ProviderMode
import app.epicreplay.core.record.cacheKey
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T408 on the phone: a full run of cloud calls on the real Keystore key, recorded and then replayed, with no
 * network (the transport is a fake that answers a recorded reply). The key must appear nowhere in the app's logcat.
 */
@RunWith(AndroidJUnit4::class)
class CloudRunOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val sentinel = "sk-CLOUDRUN-0123456789abcdef"
    private val reply = """{"choices":[{"message":{"role":"assistant","content":"{\"cell\": 6}"}}],"usage":{"prompt_tokens":1000,"completion_tokens":500}}"""

    private fun text(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }

    private class Fake(val body: String) : HttpTransport {
        var calls = 0
        var sawKey: String? = null
        override suspend fun send(request: HttpRequest, profileId: String): HttpResponse {
            calls++; sawKey = request.headers["Authorization"]
            return HttpResponse(200, body.toByteArray())
        }
    }

    @Test
    fun aRecordedThenReplayedCloudRunLeavesNoKeyInLogcat() {
        val graph = AppGraph(ctx)
        graph.secrets.put(KeyKind.MODEL_PROVIDER, Secret(sentinel))
        val files = ctx.assets.list("models")!!.filter { it.endsWith(".json") }.map { ProfileFile(it, text("models/$it")) }
        val profile = ProfileLoader(text("contracts/model_profile.schema.json"), Platform.ANDROID).load(files).profiles["deepseek-flash"] as ServerProfile
        val run = "device-test-" + System.nanoTime()
        val fake = Fake(reply)
        val backend = graph.cloudBackend(profile, Role.VISION, fake)
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        val key = cacheKey("vision", profile.model, buildJsonObject { put("question", JsonPrimitive("Which cell?")) }, listOf("00"))
        val jpeg = ByteArray(2048) { it.toByte() }
        fun once(mode: ProviderMode): Double = runBlocking {
            val rec = graph.recorder(mode)
            graph.ledger.paidCall(run, "S2", profile.apiBase, profile.model, if (rec.has("vision", key)) 0.0 else profile.callCeilingUsd) {
                val (payload, hit) = rec.fetch("vision", key) {
                    val r = backend.look(listOf(LabeledImage("Grid 1", jpeg)), "Which cell?", schema, "high")
                    buildJsonObject { put("text", JsonPrimitive(r.text)); put("usage_usd", JsonPrimitive(r.usd)) }
                }
                PaidResult(payload, if (hit) 0.0 else (payload["usage_usd"] as JsonPrimitive).content.toDouble(), hit)
            }
            graph.ledger.runTotal(run)
        }
        assertEquals(0.0009, once(ProviderMode.RECORD), 0.0) // the paid call: 1000 and 500 tokens
        assertEquals(1, fake.calls); assertEquals("Bearer $sentinel", fake.sawKey)
        assertEquals(0.0009, once(ProviderMode.REPLAY), 0.0) // the replay costs nothing more and makes no call
        assertEquals(1, fake.calls)
        assertTrue(graph.ledger.inFlight().isEmpty())
        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.bufferedReader().readText()
        assertFalse("the key is in logcat", log.contains("CLOUDRUN") || log.contains(sentinel))
        graph.secrets.delete(KeyKind.MODEL_PROVIDER)
    }
}
