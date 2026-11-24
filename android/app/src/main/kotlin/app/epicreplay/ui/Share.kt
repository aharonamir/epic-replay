package app.epicreplay.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Sharing a finished video (spec 004 FR-023): the video alone, through a content address the receiving app is granted for this one share. */
object Share {
    fun authority(context: Context) = context.packageName + ".share"

    fun intent(context: Context, runDir: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), File(runDir, "final.mp4"))
        val send = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return Intent.createChooser(send, null)
    }
}
