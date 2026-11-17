package app.epicreplay.core.net

import app.epicreplay.core.models.ModelError
import app.epicreplay.core.secrets.Secret
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * One HTTP request. [timeoutS] is the number as the reference client records it (`45.0` for `look`, `60` for `chat`).
 * Its `toString` leaves the headers out: they carry the key.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutS: JsonPrimitive,
) {
    override fun toString(): String = "HttpRequest($method $url)"
}

class HttpResponse(val status: Int, val body: ByteArray)

/** The only place that talks to the network. Tests replace it; nothing else in the app opens a connection. */
interface HttpTransport {
    /** Sends [request] and returns the answer for any HTTP status. Throws [ModelError.Timeout] when it takes too long and [ModelError.Unreachable] when no answer comes. */
    suspend fun send(request: HttpRequest, profileId: String): HttpResponse
}

/** `HttpURLConnection`: on the JVM and on Android. A timeout applies to the connection and to each read. */
class JavaHttpTransport : HttpTransport {
    override suspend fun send(request: HttpRequest, profileId: String): HttpResponse = withContext(Dispatchers.IO) {
        val ms = (request.timeoutS.content.toDouble() * 1000).toInt().coerceAtLeast(1)
        val host = runCatching { URL(request.url).host }.getOrDefault("the server")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false // a redirect would carry the Authorization header to another host
                connectTimeout = ms
                readTimeout = ms
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (request.body != null) {
                    doOutput = true
                    outputStream.use { it.write(request.body) }
                }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: SocketTimeoutException) {
            throw ModelError.Timeout(profileId, "the model call to $host took longer than ${request.timeoutS.content} s")
        } catch (e: IOException) {
            throw ModelError.Unreachable(profileId, "could not reach $host (${e.javaClass.simpleName})")
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Wraps a transport so that no failure carries a key: any error that is not already a [ModelError] is
 * reported as an [ModelError.Unreachable] built from the exception's class name only, with no cause, and
 * a [ModelError] message has the key cut out of it. The key is asked for at the moment of the failure.
 */
class RedactingTransport(private val inner: HttpTransport, private val keys: () -> List<Secret?>) : HttpTransport {
    override suspend fun send(request: HttpRequest, profileId: String): HttpResponse = try {
        inner.send(request, profileId)
    } catch (e: ModelError) {
        throw redact(e, profileId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ModelError.Unreachable(profileId, "the model call failed (${e.javaClass.simpleName})")
    }

    private fun redact(e: ModelError, profileId: String): ModelError {
        val m = e.message ?: return e
        val clean = keys().mapNotNull { it?.reveal() }.filter { it.isNotEmpty() }.fold(m) { acc, k -> acc.replace(k, "***") }
        return if (clean == m) e else when (e) {
            is ModelError.Timeout -> ModelError.Timeout(profileId, clean)
            is ModelError.Unreachable -> ModelError.Unreachable(profileId, clean)
            is ModelError.Server -> ModelError.Server(profileId, e.status, clean)
            else -> ModelError.Unreachable(profileId, clean)
        }
    }
}
