package app.epicreplay.core.download

import app.epicreplay.core.models.OnDeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the download is doing, for the screen and the notification. */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    class Running(val profile: OnDeviceProfile, val progress: Progress?) : DownloadStatus
    /** The user (or the system) stopped it; what was downloaded is kept and a new start resumes. */
    class Stopped(val profile: OnDeviceProfile) : DownloadStatus
    class Failed(val profile: OnDeviceProfile, val message: String) : DownloadStatus
    class Done(val profile: OnDeviceProfile) : DownloadStatus
}

/**
 * The one running download of the app, outside any screen: the service starts it, the screen and the notification watch
 * [status]. One at a time; a start while one runs is ignored. The files are the downloader's, so a stop keeps the part.
 */
class DownloadTracker(private val run: suspend (OnDeviceProfile, (Progress) -> Unit) -> Unit) {
    private val state = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val status: StateFlow<DownloadStatus> = state
    private var job: Job? = null

    /**
     * Starts [profile] as a child of [scope] and returns true; returns false (and changes nothing) while a download runs.
     * Whatever happens, including a cancel before the job's first line runs and the end of [scope], the completion
     * handler leaves a final state and calls [onEnd] once: the lifecycle does not depend on the coroutine body.
     */
    @Synchronized
    fun start(scope: CoroutineScope, profile: OnDeviceProfile, onEnd: () -> Unit = {}): Boolean {
        if (job?.isActive == true) return false
        state.value = DownloadStatus.Running(profile, null)
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(profile) { p -> state.value = DownloadStatus.Running(profile, p) }
                state.value = DownloadStatus.Done(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadError) {
                state.value = DownloadStatus.Failed(profile, e.message ?: "The download failed.")
            } catch (e: Exception) {
                state.value = DownloadStatus.Failed(profile, "The download failed: ${e.javaClass.simpleName}")
            }
        }
        j.invokeOnCompletion {
            if (state.value is DownloadStatus.Running) state.value = DownloadStatus.Stopped(profile) // cancelled, however early
            onEnd()
        }
        job = j
        j.start()
        return true
    }

    /** Whether a download is running (and so will end through its completion handler). */
    @Synchronized
    fun active(): Boolean = job?.isActive == true

    /** Cancels the running download, if any, and says whether there was one. The part stays on disk. */
    @Synchronized
    fun cancel(): Boolean { val j = job?.takeIf { it.isActive } ?: return false; j.cancel(); return true }

    fun acknowledge() { if (state.value !is DownloadStatus.Running) state.value = DownloadStatus.Idle }
}
