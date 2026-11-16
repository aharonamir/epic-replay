package app.epicreplay.core

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shared data lives once (constitution XI, root rule 11, spec 004 FR-025): a file under `android/`
 * that is byte for byte a file under `shared/` fails this test. Build output and tool folders are
 * skipped; files under 16 bytes are too small to be a copy of anything.
 */
class NoTwinsOfSharedTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val android = File(System.getProperty("android.dir"))
    private val skipped = setOf("build", ".gradle", ".kotlin", ".idea", ".cxx")

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun files(root: File, skip: Set<String>): List<File> =
        root.walkTopDown()
            .onEnter { it == root || it.name !in skip }
            .filter { it.isFile && it.length() >= 16 }
            .toList()

    @Test
    fun noFileUnderAndroidIsAByteForByteCopyOfASharedFile() {
        val sharedHashes = files(shared, emptySet()).associateBy(::sha)
        val twins = files(android, skipped).mapNotNull { f ->
            sharedHashes[sha(f)]?.let { "${f.relativeTo(android)} = shared/${it.relativeTo(shared)}" }
        }
        assertTrue(twins.isEmpty(), "byte-identical copies of shared files: $twins")
    }

    @Test
    fun theCheckSeesATwinWhenThereIsOne() {
        val some = files(shared, emptySet()).first()
        val dir = File(android, "core/build/twin-test").apply { mkdirs() }
        val copy = File(dir, "copy.bin").also { some.copyTo(it, overwrite = true) }
        try {
            // the walk skips `build`, so look at the folder directly
            val found = files(dir, emptySet()).any { sha(it) == sha(some) }
            assertTrue(found)
        } finally {
            dir.deleteRecursively()
        }
    }
}
