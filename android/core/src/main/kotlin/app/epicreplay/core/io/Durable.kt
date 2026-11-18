package app.epicreplay.core.io

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Durable, atomic file writes: what a reservation or a key needs to survive a crash or a power loss. */
object Durable {
    /**
     * Writes [bytes] to [file] so that a reader, and a restart after a crash, sees the old content or the new
     * and never a mix: the data is written to a neighbour file and flushed to storage (`fsync`), renamed over
     * [file], and the folder is flushed too where the system allows it.
     */
    fun writeAtomic(file: File, bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no folder for $file")
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        syncFolder(dir)
    }

    /** Flushes the folder entry of the rename. Not every filesystem lets a folder be opened; then the rename is as durable as the system makes it. */
    private fun syncFolder(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}
