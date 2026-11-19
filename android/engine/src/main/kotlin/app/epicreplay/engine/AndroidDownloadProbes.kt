package app.epicreplay.engine

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import app.epicreplay.core.download.StorageProbe
import java.io.File

/** Free storage where the models are kept, in MB (`StatFs` on the folder or the nearest existing parent). */
class AndroidStorageProbe(private val dir: File) : StorageProbe {
    override fun freeMb(): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return StatFs((d ?: dir).path).availableBytes / (1024 * 1024)
    }
}

/** Whether the active network is unmetered (Wi-Fi), for the download hint (spec 004 FR-009). */
class AndroidNetworkInfo(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isUnmetered(): Boolean = !manager.isActiveNetworkMetered
}
