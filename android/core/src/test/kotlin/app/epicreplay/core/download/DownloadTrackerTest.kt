package app.epicreplay.core.download

import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** The lifecycle the service relies on (T411 review): one download, a final state and one `onEnd`, however it ends. */
class DownloadTrackerTest {
    private fun profile(id: String) = OnDeviceProfile(
        id = id, label = id, roles = listOf(Role.VISION), supportsTools = false, status = "experimental", notes = "", engine = "llama.cpp",
        files = listOf(ModelFile("w.gguf", "https://huggingface.co/x/w.gguf", "0".repeat(64), 10, "weights")),
        contextTokens = 4096, minFreeRamMb = 1000, minFreeStorageMb = 10, license = "x",
    )

    @Test
    fun aCancelBeforeTheJobsFirstLineStillEndsInStoppedAndCallsOnEnd() = runBlocking {
        val tracker = DownloadTracker { _, _ -> awaitCancellation() }
        val ends = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        val hold = CountDownLatch(1)
        pool.execute { hold.await() } // the only thread is busy: the job cannot reach its first line
        val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
        assertTrue(tracker.start(scope, profile("a")) { ends.incrementAndGet() })
        assertTrue(tracker.cancel())
        hold.countDown()
        while (ends.get() == 0) Thread.sleep(5)
        assertIs<DownloadStatus.Stopped>(tracker.status.value)
        assertEquals(1, ends.get())
        assertFalse(tracker.active())
        pool.shutdown()
        Unit
    }

    @Test
    fun aSecondStartWhileOneRunsIsRefusedAndChangesNothing()  = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val tracker = DownloadTracker { _, _ -> gate.await() }
        val ends = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertTrue(tracker.start(scope, profile("first")) { ends.incrementAndGet() })
        assertFalse(tracker.start(scope, profile("second")) { ends.incrementAndGet() })
        assertEquals("first", (tracker.status.value as DownloadStatus.Running).profile.id)
        gate.complete(Unit)
        while (tracker.status.value is DownloadStatus.Running) Thread.sleep(5)
        assertIs<DownloadStatus.Done>(tracker.status.value)
        assertEquals("first", (tracker.status.value as DownloadStatus.Done).profile.id)
        assertEquals(1, ends.get()) // the refused start never had an onEnd of its own
        scope.cancel()
        Unit
    }

    @Test
    fun theEndOfTheScopeCancelsTheJobAndLeavesAFinalState()  = runBlocking {
        val tracker = DownloadTracker { _, _ -> awaitCancellation() }
        val ends = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tracker.start(scope, profile("a")) { ends.incrementAndGet() }
        scope.cancel() // the service is destroyed
        while (tracker.status.value is DownloadStatus.Running || ends.get() == 0) Thread.sleep(5)
        assertIs<DownloadStatus.Stopped>(tracker.status.value)
        assertEquals(1, ends.get())
        Unit
    }

    @Test
    fun aFailureAndACancelAreDifferentFinalStatesAndAStartWorksAfterEach()  = runBlocking {
        var fail = true
        val tracker = DownloadTracker { _, _ -> if (fail) throw DownloadError.InsufficientStorage(10, 5) else awaitCancellation() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tracker.start(scope, profile("a"))
        while (tracker.status.value is DownloadStatus.Running) Thread.sleep(5)
        assertIs<DownloadStatus.Failed>(tracker.status.value)
        fail = false
        assertTrue(tracker.start(scope, profile("a")))
        assertTrue(tracker.cancel())
        while (tracker.status.value is DownloadStatus.Running) Thread.sleep(5)
        assertIs<DownloadStatus.Stopped>(tracker.status.value)
        assertFalse(tracker.cancel()) // nothing left to cancel
        tracker.acknowledge(); assertIs<DownloadStatus.Idle>(tracker.status.value)
        scope.cancel()
        Unit
    }
}
