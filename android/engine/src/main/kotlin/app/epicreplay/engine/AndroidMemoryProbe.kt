package app.epicreplay.engine

import android.app.ActivityManager
import android.content.Context
import app.epicreplay.core.models.MemoryProbe

/** Free memory as the system reports it (`ActivityManager.MemoryInfo.availMem`), in MB: what a model may be loaded into (spec 004 FR-014). */
class AndroidMemoryProbe(context: Context) : MemoryProbe {
    private val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun freeMb(): Long {
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }
}
