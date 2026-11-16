package app.epicreplay.core

import java.io.File

/** Where the shared data is. On the JVM it is the repository's `shared/` folder, read in place. */
class SharedDir(val root: File) {
    init {
        require(root.isDirectory) { "shared data folder not found: $root" }
    }

    fun file(path: String): File = File(root, path).also { require(it.isFile) { "missing shared file: $path" } }
}
