package app.epicreplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a replay being made going when the app is in the background or the screen is off (spec 004 FR-PLAYER's neighbour, T428): a foreground service of type `dataSync` with a
 * notification naming the step, and a partial wake lock so the processor is not put to sleep. Without it Android lowers the process's priority or freezes it, and a run of several minutes
 * (the phone's model, the export) timed out on a clip that took 160 s when the app stayed in front. The work itself is [app.epicreplay.core.ui.RunSession]'s; this only keeps it alive, and
 * the app starts and stops it as the run starts and stops working.
 */
class RunService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); running = true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Making a replay", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_TEXT) ?: "Making your replay"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (lock == null) lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "epicreplay:run").apply { acquire(WAKE_LIMIT_MS) }
        return START_NOT_STICKY
    }

    /** Android's time limit for this service type: it stops, and the run goes on as any app's would. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() { running = false; lock?.takeIf { it.isHeld }?.release(); lock = null; super.onDestroy() }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mark).setContentTitle("Making your replay").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
    }

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TEXT = "text"
        private const val WAKE_LIMIT_MS = 60 * 60 * 1000L // a replay never takes an hour: the lock does not outlive a mistake

        /** Whether the service is up (for tests). */
        @Volatile var running = false
            private set

        /** The run is working: the service is up, with [text] (the step) in its notification. A start the system refuses (the app is not in front) leaves the run as it was. */
        fun show(context: Context, text: String) {
            try { context.startForegroundService(Intent(context, RunService::class.java).putExtra(EXTRA_TEXT, text)) } catch (e: Exception) { /* not allowed now: the run goes on without it */ }
        }

        fun stop(context: Context) { context.stopService(Intent(context, RunService::class.java)) }
    }
}
