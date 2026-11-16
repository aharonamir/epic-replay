package app.epicreplay.core.secrets

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T406, spec 004 FR-008 and SC-6: a key never reaches a log line. Two source rules, checked on
 * every Kotlin file of every module (the runtime side is in SecretStoreTest):
 *  1. A file that handles a `Secret`, the store or controller, or the key-entry field does not log: no `Log.`, `println`, `printStackTrace`, `Timber`.
 *  2. `.reveal()` is called only in the files listed here. A new model client (T408) adds itself to
 *     the list on purpose, which puts the change in front of a reviewer.
 */
class NoKeyInLogsTest {
    private val android = File(System.getProperty("android.dir"))

    private val mainSources: List<File> = android.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".idea", ".cxx") }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
        .toList()

    private val logging = Regex("""\bLog\.\w+\(|\bprintln\(|\bprint\(|printStackTrace|\bTimber\.|android\.util\.Log|System\.(out|err)|\bLogger\b""")
    // Files that hold a key or the text a user types as one: the store, the controller, the key-entry screen.
    private val sensitiveMarkers = listOf("Secret", "reveal()", "KeysController", "SecretStore", "KeyKind", "PasswordVisualTransformation")
    private val allowedReveal = setOf("Secret.kt", "SecretStore.kt")

    @Test
    fun theScanSeesTheSources() {
        assertTrue(mainSources.size >= 5, "found only ${mainSources.size} sources")
    }

    @Test
    fun aFileThatHandlesASecretDoesNotLog() {
        val offenders = mainSources.filter { f ->
            val t = f.readText()
            sensitiveMarkers.any { it in t } && logging.containsMatchIn(t)
        }.map { it.name }
        assertEquals(emptyList(), offenders, "files that handle a Secret and log")
    }

    @Test
    fun theKeyEntryScreenIsInTheScan() {
        val names = mainSources.filter { f -> sensitiveMarkers.any { it in f.readText() } }.map { it.name }
        assertTrue("SettingsScreen.kt" in names && "AppGraph.kt" in names, "sensitive files seen: $names")
    }

    @Test
    fun revealIsCalledOnlyWhereItIsAllowed() {
        val offenders = mainSources.filter { ".reveal()" in it.readText() && it.name !in allowedReveal }.map { it.name }
        assertEquals(emptyList(), offenders, "files that call reveal() without being on the list")
    }

    @Test
    fun noSourceHardcodesAKeyShapedString() {
        val keyShape = Regex("""["'](sk-[A-Za-z0-9]{16,}|xi-[A-Za-z0-9]{16,})["']""")
        val offenders = mainSources.filter { keyShape.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList(), offenders)
    }
}
