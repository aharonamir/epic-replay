package app.epicreplay

import android.app.Application

/** Owns the app's one [AppGraph], so the activity and the download service share the same objects. */
class EpicApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
