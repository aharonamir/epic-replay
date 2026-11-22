package app.epicreplay

import android.content.res.AssetManager
import java.io.File

/** The shared data the app packages, written out as plain files: the core classes that read `shared/` (words, lexicons, schemas, the mix rules) read a folder, and assets are not files. */
object SharedFiles {
    private val FOLDERS = listOf("copy", "lexicons", "contracts", "mix")

    /** Copies the folders the stages read into [dest] and returns it; nothing is copied again when [dest] already holds the same app version's files. */
    fun extract(assets: AssetManager, dest: File, version: Long): File {
        val stamp = File(dest, ".version")
        if (stamp.isFile && stamp.readText() == version.toString()) return dest
        dest.deleteRecursively()
        fun copy(path: String) {
            val children = assets.list(path)
            if (children.isNullOrEmpty()) File(dest, path).apply { parentFile!!.mkdirs() }.outputStream().use { o -> assets.open(path).use { it.copyTo(o) } }
            else children.forEach { copy("$path/$it") }
        }
        FOLDERS.forEach(::copy)
        stamp.writeText(version.toString())
        return dest
    }
}
