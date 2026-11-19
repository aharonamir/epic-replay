package app.epicreplay.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.epicreplay.EpicApp
import app.epicreplay.core.download.ModelNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a model download going when the app is in the background or the screen is off (spec 004 FR-009, T411): a
 * foreground service of type `dataSync` with a progress notification. The download itself is [DownloadTracker]'s;
 * stopping the service (the user's cancel, or the system's time limit for this type) stops it and keeps the part.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker get() = (application as EpicApp).graph.downloads

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as EpicApp
        val profile = intent?.getStringExtra(EXTRA_PROFILE)?.let { app.graph.profiles.profiles[it] } as? app.epicreplay.core.models.OnDeviceProfile
        if (profile == null) { stopSelf(startId); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting the download", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        tracker.start(CoroutineScope(SupervisorJob() + Dispatchers.IO), profile) { stopSelf() }
        scope.launch {
            tracker.status.collectLatest { s ->
                if (s is DownloadStatus.Running) {
                    val p = s.progress
                    val text = if (p == null) "Starting the download" else "${ModelNotice.megabytes(p.doneBytes)} of ${ModelNotice.megabytes(p.totalBytes)}"
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, p?.let { it.doneBytes.toFloat() / it.totalBytes }))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android's time limit for a `dataSync` service (Android 15): stop cleanly, keeping what was downloaded. */
    override fun onTimeout(startId: Int, fgsType: Int) { tracker.cancel(); stopSelf() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String, fraction: Float?): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading the model")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .build()

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, profileId: String) {
            context.startForegroundService(Intent(context, DownloadService::class.java).putExtra(EXTRA_PROFILE, profileId))
        }

        fun cancel(context: Context, tracker: DownloadTracker) {
            tracker.cancel()
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
