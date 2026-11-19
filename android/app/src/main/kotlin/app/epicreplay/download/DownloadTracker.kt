package app.epicreplay.download

import app.epicreplay.core.download.DownloadError
import app.epicreplay.core.download.ModelDownloader
import app.epicreplay.core.download.Progress
import app.epicreplay.core.models.OnDeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
class DownloadTracker(private val downloader: () -> ModelDownloader) {
    private val state = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val status: StateFlow<DownloadStatus> = state
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope, profile: OnDeviceProfile, onEnd: () -> Unit = {}): Boolean {
        if (job?.isActive == true) return false
        state.value = DownloadStatus.Running(profile, null)
        job = scope.launch {
            try {
                downloader().download(profile) { p -> state.value = DownloadStatus.Running(profile, p) }
                state.value = DownloadStatus.Done(profile)
            } catch (e: CancellationException) {
                state.value = DownloadStatus.Stopped(profile)
                throw e
            } catch (e: DownloadError) {
                state.value = DownloadStatus.Failed(profile, e.message ?: "The download failed.")
            } catch (e: Exception) {
                state.value = DownloadStatus.Failed(profile, "The download failed: ${e.javaClass.simpleName}")
            } finally {
                onEnd()
            }
        }
        return true
    }

    @Synchronized
    fun cancel() { job?.cancel() }

    fun acknowledge() { if (state.value !is DownloadStatus.Running) state.value = DownloadStatus.Idle }
}
