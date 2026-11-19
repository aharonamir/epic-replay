package app.epicreplay.core.download

import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A file server on this computer's loopback, with the misbehaviours a download must survive. */
class TestServer(val content: ByteArray) {
    class Seen(val range: String?, val ifRange: String?)
    val seen = CopyOnWriteArrayList<Seen>()
    @Volatile var honorRange = true
    @Volatile var etag = "\"v1\""
    @Volatile var cutAfter: Int? = null // the next full or ranged answer ends after this many body bytes
    @Volatile var wrongRangeStart = false
    @Volatile var redirectTo: String? = null
    @Volatile var slowMs = 0L
    @Volatile var stallAfter: Int? = null // after this many body bytes the server goes quiet for 10 s
    @Volatile var content2: ByteArray? = null // served instead of [content] (a flipped byte, a wrong length)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    val url get() = "http://127.0.0.1:$port/file"

    init {
        server.executor = java.util.concurrent.Executors.newCachedThreadPool() // a stalled answer must not block the next request
        server.createContext("/file") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        seen += Seen(range, ex.requestHeaders.getFirst("If-Range"))
        redirectTo?.let { ex.responseHeaders.add("Location", it); ex.sendResponseHeaders(302, -1); ex.close(); return }
        val data = content2 ?: content
        ex.responseHeaders.add("ETag", etag)
        var start = 0
        var status = 200
        val m = range?.let { Regex("bytes=(\\d+)-").find(it) }
        if (m != null && honorRange) {
            start = m.groupValues[1].toInt()
            status = 206
            val shown = if (wrongRangeStart) start + 1 else start
            ex.responseHeaders.add("Content-Range", "bytes $shown-${data.size - 1}/${data.size}")
        }
        val length = data.size - start
        ex.sendResponseHeaders(status, length.toLong())
        val cut = cutAfter
        cutAfter = null
        ex.responseBody.use { out ->
            val chunk = 32 * 1024
            var sent = 0
            while (sent < length) {
                val n = minOf(chunk, length - sent)
                if (cut != null && sent + n > cut) { out.write(data, start + sent, cut - sent); out.flush(); ex.close(); return }
                out.write(data, start + sent, n); sent += n
                if (stallAfter != null && sent >= stallAfter!!) { out.flush(); Thread.sleep(10_000) }
                if (slowMs > 0) Thread.sleep(slowMs)
            }
        }
    }

    fun stop() = server.stop(0)
}

class ModelDownloaderTest {
    private lateinit var dir: File
    private val servers = mutableListOf<TestServer>()
    private val data = Random(7).nextBytes(3 * 1024 * 1024)
    private val local = DownloadPolicy(listOf(HostRule.exact("127.0.0.1")), requireHttps = false)

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("dl").toFile() }
    @AfterTest fun tearDown() { servers.forEach { it.stop() }; dir.deleteRecursively() }

    private fun server(content: ByteArray = data) = TestServer(content).also { servers += it }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun profile(url: String, content: ByteArray = data, floorMb: Int = 1) = OnDeviceProfile(
        id = "phone-model", label = "Phone", roles = listOf(Role.TEXT), supportsTools = false, status = "experimental", notes = "",
        engine = "llama.cpp", files = listOf(ModelFile("m.gguf", url, sha(content), content.size.toLong(), "weights")),
        contextTokens = 4096, minFreeRamMb = 1, minFreeStorageMb = floorMb, license = "Apache-2.0",
    )

    private fun downloader(free: Long = 100_000, policy: DownloadPolicy = local, stallMs: Long = 60_000) =
        ModelDownloader(dir, policy, { free }, JavaDownloadHttp(2000, 300), slackMb = 1, stallTimeoutMs = stallMs)

    @Test
    fun aFileIsDownloadedVerifiedAndRenamedOnlyAtTheEnd() = runBlocking {
        val s = server()
        val progress = mutableListOf<Progress>()
        downloader().download(profile(s.url)) { progress += it }
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertFalse(File(dir, "m.gguf.part").exists()); assertFalse(File(dir, "m.gguf.part.meta").exists())
        assertEquals(data.size.toLong(), progress.last().doneBytes)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.doneBytes >= a.doneBytes }, "progress went backwards")
        assertTrue(downloader().installed(profile(s.url)))
        Unit
    }

    @Test
    fun aFlippedByteIsRefusedAndTheFileDeleted() {
        val bad = data.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val s = server(); s.content2 = bad
        val e = assertFailsWith<DownloadError.ChecksumMismatch> { runBlocking { downloader().download(profile(s.url)) } }
        assertEquals("m.gguf", e.file)
        assertEquals(emptyList(), dir.list()!!.toList()) // no final file, no .part, no .meta
    }

    @Test
    fun aWrongLengthIsRefusedAndNothingIsKept() {
        val s = server(); s.content2 = data.copyOf(data.size + 100)
        assertFailsWith<DownloadError.SizeMismatch> { runBlocking { downloader().download(profile(s.url)) } }
        assertFalse(File(dir, "m.gguf").exists()); assertFalse(File(dir, "m.gguf.part").exists())
    }

    @Test
    fun anInterruptedDownloadResumesFromWhereItStoppedWithARangeRequest() = runBlocking {
        val s = server(); s.cutAfter = 1_200_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        val part = File(dir, "m.gguf.part")
        assertEquals(1_200_000L, part.length()) // what arrived is kept
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertEquals("bytes=1200000-", s.seen.last().range); assertEquals("\"v1\"", s.seen.last().ifRange)
        assertEquals(2, s.seen.size)
        Unit
    }

    @Test
    fun aServerThatIgnoresRangeMakesTheDownloadRestartNeverAppend() = runBlocking {
        val s = server(); s.cutAfter = 1_000_000; s.honorRange = false
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        downloader().download(profile(s.url)) // asks for a range, gets the whole file with 200
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data), "the file is not exactly the content (appended?)")
        assertEquals(3, s.seen.size) // the first try, the ranged try, the restart without a range
        assertNull(s.seen.last().range)
        Unit
    }

    @Test
    fun aMismatchedContentRangeMakesItRestart() = runBlocking {
        val s = server(); s.cutAfter = 900_000; s.wrongRangeStart = true
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        s.honorRange = true
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertNull(s.seen.last().range)
        Unit
    }

    @Test
    fun aChangedValidatorMakesItRestart() = runBlocking {
        val s = server(); s.cutAfter = 800_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        s.etag = "\"v2\"" // the file on the server is another version now
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertNull(s.seen.last().range)
        Unit
    }

    @Test
    fun aPartLongerThanTheFileIsStartedAgain() = runBlocking {
        val s = server()
        File(dir, "m.gguf.part").writeBytes(ByteArray(data.size + 10)); File(dir, "m.gguf.part.meta").writeText("\"v1\"")
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertNull(s.seen.first().range)
        Unit
    }

    @Test
    fun aRedirectToAnOtherHostIsRefusedBeforeAnythingIsFetched() {
        val target = server()
        val first = server(); first.redirectTo = "http://localhost:${target.port}/file" // "localhost" is not "127.0.0.1"
        val e = assertFailsWith<DownloadError.DisallowedHost> { runBlocking { downloader().download(profile(first.url)) } }
        assertTrue("localhost" in e.message!!)
        assertEquals(0, target.seen.size)
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun aRedirectInsideTheAllowlistIsFollowed() = runBlocking {
        val target = server(); val first = server(); first.redirectTo = "http://127.0.0.1:${target.port}/file"
        downloader().download(profile(first.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data)); assertEquals(1, target.seen.size)
        Unit
    }

    @Test
    fun aRedirectLoopStops() {
        val a = server()
        a.redirectTo = "http://127.0.0.1:${a.port}/file"
        assertFailsWith<DownloadError.TooManyRedirects> { runBlocking { downloader().download(profile(a.url)) } }
    }

    @Test
    fun plainHttpAndUnknownHostsAreRefusedByTheDefaultPolicy() {
        val strict = DownloadPolicy.DEFAULT
        assertTrue(strict.refusal("https://huggingface.co/x/resolve/abc/f.gguf") == null)
        assertTrue(strict.refusal("https://us.aws.cdn.hf.co/repos/aa/f") == null)
        assertTrue(strict.refusal("http://huggingface.co/x") is DownloadError.NotHttps)
        assertTrue(strict.refusal("https://huggingface.co.evil.example/x") is DownloadError.DisallowedHost)
        assertTrue(strict.refusal("https://evilhf.co/x") is DownloadError.DisallowedHost)
        assertTrue(strict.refusal("https://cdn.hf.co.example.com/x") is DownloadError.DisallowedHost)
        assertTrue(strict.refusal("https://x.cdn.hf.co@evil.example/x") is DownloadError.DisallowedHost) // the host is after the @
        assertTrue(strict.refusal("not a url") is DownloadError.DisallowedHost)
        val s = server()
        assertFailsWith<DownloadError.NotHttps> { runBlocking { downloader(policy = DownloadPolicy(local.hosts)).download(profile(s.url)) } }
        assertEquals(0, s.seen.size)
    }

    @Test
    fun aFullStorageIsRefusedBeforeTheFirstByte() {
        val s = server()
        val e = assertFailsWith<DownloadError.InsufficientStorage> { runBlocking { downloader(free = 2).download(profile(s.url, floorMb = 50)) } }
        assertEquals(2L, e.freeMb); assertEquals(0, s.seen.size)
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun theProfilesOwnFloorCountsEvenWhenTheFilesAreSmaller() {
        val s = server()
        assertFailsWith<DownloadError.InsufficientStorage> { runBlocking { downloader(free = 20).download(profile(s.url, floorMb = 50)) } }
        runBlocking { downloader(free = 60).download(profile(s.url, floorMb = 50)) }
        assertTrue(File(dir, "m.gguf").isFile)
    }

    @Test
    fun cancellingKeepsThePartAndTheNextCallResumes() = runBlocking {
        val s = server(); s.slowMs = 4
        val job = launch(Dispatchers.Default) { downloader().download(profile(s.url)) }
        delay(300)
        job.cancel(); job.join()
        val part = File(dir, "m.gguf.part")
        assertTrue(part.isFile && part.length() > 0 && part.length() < data.size, "the .part should hold some of the file: ${part.length()}")
        s.slowMs = 0
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertTrue(s.seen.last().range!!.startsWith("bytes="))
        Unit
    }

    @Test
    fun aFinishedFileIsNotFetchedAgain() = runBlocking {
        val s = server()
        downloader().download(profile(s.url)); downloader().download(profile(s.url))
        assertEquals(1, s.seen.size)
        Unit
    }

    @Test
    fun deleteRemovesTheFileAndAnyPartialOne() = runBlocking {
        val s = server(); s.cutAfter = 500_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        File(dir, "m.gguf").writeBytes(byteArrayOf(1))
        downloader().delete(profile(s.url))
        assertEquals(emptyList(), dir.list()!!.toList())
        assertFalse(downloader().installed(profile(s.url)))
        Unit
    }

    @Test
    fun remainingBytesCountsWhatAResumablePartAlreadyHolds() = runBlocking {
        val s = server(); s.cutAfter = 1_000_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        assertEquals((data.size - 1_000_000).toLong(), downloader().remainingBytes(profile(s.url)))
        Unit
    }

    @Test
    fun theErrorMessagesNameAHostAndNeverAnAddressWithAQuery() {
        val s = server(); s.redirectTo = "http://localhost:1/file?token=SECRET123"
        val e = assertFailsWith<DownloadError.DisallowedHost> { runBlocking { downloader().download(profile(s.url)) } }
        assertFalse("SECRET123" in e.message!!)
    }

    @Test
    fun aSameSizeFileThatWasNeverVerifiedIsNotInstalledAndIsFetchedAgain() = runBlocking {
        val s = server()
        File(dir, "m.gguf").writeBytes(ByteArray(data.size)) // the right length, the wrong bytes, no marker
        assertFalse(downloader().installed(profile(s.url)))
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data)); assertEquals(1, s.seen.size)
        assertTrue(downloader().installed(profile(s.url)))
        File(dir, "m.gguf").appendBytes(byteArrayOf(0)); File(dir, "m.gguf").setLastModified(1) // touched afterwards: the marker no longer matches
        assertFalse(downloader().installed(profile(s.url)))
        Unit
    }

    @Test
    fun verifyChecksAFilePlacedByHandAndDeletesOneThatDoesNotMatch() = runBlocking {
        val s = server()
        File(dir, "m.gguf").writeBytes(data)
        assertFalse(downloader().installed(profile(s.url)))
        assertTrue(downloader().verify(profile(s.url))); assertTrue(downloader().installed(profile(s.url)))
        File(dir, "m.gguf").writeBytes(ByteArray(data.size)); File(dir, "m.gguf.verified").delete()
        assertFalse(downloader().verify(profile(s.url))); assertFalse(File(dir, "m.gguf").exists())
        Unit
    }

    @Test
    fun aPartFromAnotherAddressOrChecksumIsNotResumed() = runBlocking {
        val s = server(); s.cutAfter = 700_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        // a newer profile: same file name, another revision of the file
        val other = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        s.content2 = other
        downloader().download(profile(s.url, content = other))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(other))
        assertNull(s.seen.last().range, "the old part was resumed under a new checksum")
        Unit
    }

    @Test
    fun twoDownloadsOfTheSameProfileDoNotCorruptEachOther() = runBlocking {
        val s = server(); s.slowMs = 2
        val a = launch(Dispatchers.Default) { downloader().download(profile(s.url)) }
        val b = launch(Dispatchers.Default) { downloader().download(profile(s.url)) }
        a.join(); b.join()
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertEquals(1, s.seen.size, "the second call should have found the file already verified")
        Unit
    }

    @Test
    fun anAllowedHostOnAnotherPortIsRefusedByTheDefaultPolicy() {
        assertTrue(DownloadPolicy.DEFAULT.refusal("https://huggingface.co:8443/x") is DownloadError.DisallowedHost)
        assertNull(DownloadPolicy.DEFAULT.refusal("https://huggingface.co:443/x"))
        assertNull(DownloadPolicy.DEFAULT.refusal("https://HUGGINGFACE.CO/x"))
    }

    @Test
    fun aWeakEtagIsNotAValidatorSoTheFileStartsAgain() = runBlocking {
        val s = server(); s.etag = "W/\"v1\""; s.cutAfter = 600_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertNull(s.seen.last().range)
        Unit
    }

    @Test
    fun cancellingWhileTheServerIsStalledReturnsAtOnceNotAfterTheReadTimeout() = runBlocking {
        val s = server(); s.stallAfter = 300_000
        val job = launch(Dispatchers.Default) { downloader().download(profile(s.url)) } // reads time out every 0.3 s in these tests
        while (File(dir, "m.gguf.part").length() < 250_000) delay(20)
        val t0 = System.nanoTime()
        job.cancel(); job.join()
        val s1 = (System.nanoTime() - t0) / 1e9
        assertTrue(s1 < 2.0, "the cancel waited for the stalled read: $s1 s")
        assertTrue(File(dir, "m.gguf.part").isFile) // the part is kept
        Unit
    }

    @Test
    fun aServerThatGoesQuietForTooLongEndsTheAttemptAndTheNextOneResumes() = runBlocking {
        val s = server(); s.stallAfter = 400_000
        assertFailsWith<DownloadError.Interrupted> { downloader(stallMs = 800).download(profile(s.url)) }
        val part = File(dir, "m.gguf.part")
        assertTrue(part.length() in 1 until data.size)
        s.stallAfter = null
        downloader().download(profile(s.url))
        assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        assertTrue(s.seen.last().range!!.startsWith("bytes="))
        Unit
    }

    @Test
    fun aFileNameThatIsNotAPlainNameIsRefusedBeforeAnythingIsWritten() {
        val s = server()
        for (bad in listOf("../evil.gguf", "a/b.gguf", ".hidden", "..", "")) {
            val p = profile(s.url).let { it.copy(files = listOf(it.files[0].copy(name = bad))) }
            assertFailsWith<DownloadError.UnsafeName> { runBlocking { downloader().download(p) } }
        }
        assertEquals(0, s.seen.size)
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun theStorageSlackMustBeAtLeastOneMegabyte() {
        assertFailsWith<IllegalArgumentException> { ModelDownloader(dir, local, { 1000 }, slackMb = 0) }
    }

    @Test
    fun aStorageFailureIsATypedErrorAndTheDownloadCanBeRetried() {
        val s = server()
        dir.setWritable(false)
        try {
            if (dir.canWrite()) return // running as a user the permission does not bind (root): nothing to test
            assertFailsWith<DownloadError.Storage> { runBlocking { downloader().download(profile(s.url)) } }
        } finally { dir.setWritable(true) }
        runBlocking { downloader().download(profile(s.url)) }
        assertTrue(File(dir, "m.gguf").isFile)
    }

    @Test
    fun aContentRangeWithNumbersBeyondLongRestartsInsteadOfThrowing() = runBlocking {
        val s = server(); s.cutAfter = 600_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        // a hand-made 206 whose numbers overflow a Long: a server of its own, answering every request that way
        val bad = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        bad.createContext("/file") { ex ->
            if (ex.requestHeaders.getFirst("Range") != null) {
                ex.responseHeaders.add("Content-Range", "bytes 99999999999999999999-99999999999999999999/99999999999999999999")
                ex.responseHeaders.add("ETag", "\"v1\""); ex.sendResponseHeaders(206, 0); ex.responseBody.close()
            } else { ex.responseHeaders.add("ETag", "\"v1\""); ex.sendResponseHeaders(200, data.size.toLong()); ex.responseBody.use { it.write(data) } }
        }
        bad.start()
        try {
            val p = profile("http://127.0.0.1:${bad.address.port}/file")
            File(dir, "m.gguf.part").writeBytes(data.copyOf(600_000)) // a part with a record for this very address
            File(dir, "m.gguf.part.meta").writeText("${p.files[0].url}\n${p.files[0].sha256}\n\"v1\"")
            downloader().download(p)
            assertTrue(File(dir, "m.gguf").readBytes().contentEquals(data))
        } finally { bad.stop(0) }
        Unit
    }

    @Test
    fun aPartThatCannotBeResumedIsNotCountedAsAlreadyDownloaded() = runBlocking {
        val s = server(); s.cutAfter = 1_000_000
        assertFailsWith<DownloadError.Interrupted> { downloader().download(profile(s.url)) }
        assertEquals((data.size - 1_000_000).toLong(), downloader().remainingBytes(profile(s.url)))
        // the same file name from another address: its part is not resumable, so the whole file is still to fetch
        val elsewhere = profile("http://127.0.0.1:${s.port}/other")
        assertEquals(data.size.toLong(), downloader().remainingBytes(elsewhere))
        Unit
    }

    @Test
    fun deleteReportsAFileItCouldNotRemove() {
        val s = server()
        runBlocking { downloader().download(profile(s.url)) }
        dir.setWritable(false)
        try {
            if (dir.canWrite()) return // not enforced for this user (root)
            assertFailsWith<DownloadError.Storage> { runBlocking { downloader().delete(profile(s.url)) } }
        } finally { dir.setWritable(true) }
        runBlocking { downloader().delete(profile(s.url)) }
        assertEquals(emptyList(), dir.list()!!.toList())
    }
}
