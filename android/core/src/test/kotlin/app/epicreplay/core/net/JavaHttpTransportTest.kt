package app.epicreplay.core.net

import app.epicreplay.core.models.ModelError
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** The real transport against a server on this computer's loopback: no network, no key. */
class JavaHttpTransportTest {
    private var server: HttpServer? = null
    private var seenAuth: String? = null
    private var seenBody: String? = null

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(status: Int = 200, body: String = "{}", delayMs: Long = 0): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/chat/completions") { ex ->
            seenAuth = ex.requestHeaders.getFirst("Authorization")
            seenBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}/chat/completions"
    }

    private fun post(url: String, timeoutS: Double = 5.0) =
        runBlocking { JavaHttpTransport().send(HttpRequest("POST", url, mapOf("Authorization" to "Bearer k", "Content-Type" to "application/json"), "{\"a\":1}".toByteArray(), JsonPrimitive(timeoutS)), "p") }

    @Test
    fun aPostCarriesTheHeadersAndTheBodyAndReturnsTheAnswer() {
        val r = post(serve(body = """{"ok":true}"""))
        assertEquals(200, r.status); assertEquals("""{"ok":true}""", String(r.body))
        assertEquals("Bearer k", seenAuth); assertEquals("""{"a":1}""", seenBody)
    }

    @Test
    fun anErrorStatusComesBackAsAStatusNotAnException() {
        val r = post(serve(status = 500, body = "boom"))
        assertEquals(500, r.status); assertEquals("boom", String(r.body))
    }

    @Test
    fun aSlowServerIsATimeoutNamingTheHostOnly() {
        val e = assertFailsWith<ModelError.Timeout> { post(serve(delayMs = 1500), timeoutS = 0.3) }
        assertTrue("127.0.0.1" in e.message!! && "?" !in e.message!!)
    }

    @Test
    fun noServerIsUnreachableWithoutTheUrlsQuery() {
        val port = ServerSocket(0).use { it.localPort } // a port that is closed again
        val e = assertFailsWith<ModelError.Unreachable> { post("http://127.0.0.1:$port/chat/completions?secret=abc") }
        assertTrue("secret=abc" !in e.message!!)
    }
}
