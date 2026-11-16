package app.epicreplay.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared data lives once (constitution XI, root rule 11, spec 004 FR-025): a file under `android/`
 * that is byte for byte a file under `shared/` fails this test. Only Gradle's and the IDE's own
 * output folders at the top of a module are skipped (`<module>/build`, `.gradle`, `.kotlin`, `.idea`,
 * `.cxx`): the build packages shared data there on purpose and git ignores it. Files under 16 bytes
 * are too small to be a copy of anything.
 */
class NoTwinsOfSharedTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val android = File(System.getProperty("android.dir"))

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun generated(root: File, f: File): Boolean {
        val parts = f.relativeTo(root).invariantSeparatorsPath.split("/")
        return parts.any { it in setOf(".gradle", ".kotlin", ".idea", ".cxx") } ||
            (parts.size > 1 && parts[1] == "build") // <module>/build
    }

    private fun files(root: File, skipGenerated: Boolean): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.length() >= 16 && (!skipGenerated || !generated(root, it)) }
            .toList()

    /** The scan: every file under `androidRoot` (outside generated folders) that equals a file under `sharedRoot`. */
    private fun twins(androidRoot: File, sharedRoot: File): List<String> {
        val byHash = files(sharedRoot, false).associateBy(::sha)
        return files(androidRoot, true).mapNotNull { f ->
            byHash[sha(f)]?.let { "${f.relativeTo(androidRoot)} = shared/${it.relativeTo(sharedRoot)}" }
        }
    }

    @Test
    fun noFileUnderAndroidIsAByteForByteCopyOfASharedFile() {
        val found = twins(android, shared)
        assertTrue(found.isEmpty(), "byte-identical copies of shared files: $found")
    }

    @Test
    fun theScanFindsATwinAndOnlyATwin() {
        val tmp = Files.createTempDirectory("twins").toFile()
        try {
            val s = File(tmp, "shared").apply { mkdirs() }
            val a = File(tmp, "android/app/src").apply { mkdirs() }
            File(s, "pack.json").writeText("{\"id\": \"the same bytes in both\"}")
            File(a, "renamed.json").writeText("{\"id\": \"the same bytes in both\"}") // a twin under another name
            File(a, "other.json").writeText("{\"id\": \"different bytes entirely\"}")
            File(tmp, "android/app/build").apply { mkdirs() }
            File(tmp, "android/app/build/generated.json").writeText("{\"id\": \"the same bytes in both\"}") // generated: skipped
            assertEquals(listOf("app/src/renamed.json = shared/pack.json"), twins(File(tmp, "android"), s))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
