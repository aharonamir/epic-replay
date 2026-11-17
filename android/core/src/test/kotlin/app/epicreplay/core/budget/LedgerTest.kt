package app.epicreplay.core.budget

import app.epicreplay.core.models.ModelError
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** T408: the ledger's own rules beyond the shared cases: it is written before the call, survives a kill, and is safe under concurrency. */
class LedgerTest {
    private lateinit var dir: File
    @BeforeTest fun setUp() { dir = Files.createTempDirectory("ledger").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun ledger(storage: LedgerStorage = JsonFileLedgerStorage(File(dir, "ledger.json")), caps: Caps = Caps(), day: String = "2026-11-17") =
        Ledger(storage, caps, today = { day }, now = { "t" })

    @Test
    fun aReservationIsOnDiskBeforeTheCallIsMade() {
        val l = ledger()
        l.reserve("r1", 0.4, false, "S2", "p", "m")
        val text = File(dir, "ledger.json").readText()
        assertTrue("\"estimateUsd\":0.4" in text.replace(" ", ""), text)
    }

    @Test
    fun aKillBetweenReservationAndChargeCountsTheReservationAsSpentAtTheCeiling() {
        val first = ledger(caps = Caps(runUsd = 1.0))
        first.reserve("r1", 0.8, false, "S2", "p", "m") // the app is killed here: no settle, no release
        val restarted = ledger(caps = Caps(runUsd = 1.0))
        assertEquals(0.8, restarted.runTotal("r1"))
        assertEquals(0.8, restarted.dayTotal())
        assertEquals("Restart", restarted.calls("r1").single().failed)
        assertTrue(restarted.inFlight().isEmpty())
        assertFailsWith<CapExceeded> { restarted.reserve("r1", 0.3, false) } // the cap is respected: 0.8 + 0.3 > 1.0
        restarted.reserve("r1", 0.2, false) // exactly the rest is still allowed
    }

    @Test
    fun theStateSurvivesARestartWhenNothingWasInFlight() {
        val a = ledger()
        val r = a.reserve("r1", 0.1, false); a.settle(r, 0.05, false)
        val b = ledger()
        assertEquals(0.05, b.runTotal("r1")); assertEquals(0.05, b.dayTotal())
        assertEquals(emptyList(), b.calls("r1").mapNotNull { it.failed })
    }

    @Test
    fun aNewDayStartsFromZeroButTheRunKeepsItsTotal() {
        val storage = InMemoryLedgerStorage()
        val mon = ledger(storage, day = "2026-11-17"); val r = mon.reserve("r1", 0.1, false); mon.settle(r, 0.1, false)
        val tue = ledger(storage, day = "2026-11-18")
        assertEquals(0.0, tue.dayTotal()); assertEquals(0.1, tue.runTotal("r1"))
    }

    @Test
    fun aCallIsChargedToTheDayItWasReservedOnEvenIfItSettlesAfterMidnight() {
        var day = "2026-11-17"
        val l = Ledger(InMemoryLedgerStorage(), Caps(), today = { day }, now = { "t" })
        val r = l.reserve("r1", 0.3, false)
        day = "2026-11-18" // midnight passes while the call is in flight
        l.settle(r, 0.2, false)
        assertEquals(0.0, l.dayTotal()) // the new day starts at zero
        day = "2026-11-17"; assertEquals(0.2, l.dayTotal())
    }

    @Test
    fun aReservationLeftOverAcrossMidnightIsChargedToItsOwnDayAfterARestart() {
        val storage = InMemoryLedgerStorage()
        val first = Ledger(storage, Caps(), today = { "2026-11-17" }, now = { "t" })
        first.reserve("r1", 0.3, false) // killed
        val restarted = Ledger(storage, Caps(), today = { "2026-11-18" }, now = { "t" })
        assertEquals(0.0, restarted.dayTotal()); assertEquals(0.3, restarted.runTotal("r1"))
        assertEquals(0.3, Ledger(storage, Caps(), today = { "2026-11-17" }, now = { "t" }).dayTotal())
    }

    @Test
    fun aReservationIsDurablyWrittenThroughAFlushedFile() {
        val f = File(dir, "durable.bin")
        app.epicreplay.core.io.Durable.writeAtomic(f, byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), f.readBytes().toList())
        app.epicreplay.core.io.Durable.writeAtomic(f, byteArrayOf(9))
        assertEquals(listOf<Byte>(9), f.readBytes().toList())
        assertEquals(listOf("durable.bin"), dir.list()!!.filter { it.startsWith("durable") })
    }

    @Test
    fun manyThreadsCannotTakeTheRunPastItsCap() {
        val l = ledger(InMemoryLedgerStorage(), Caps(runUsd = 1.0))
        val won = CopyOnWriteArrayList<Reservation>()
        (1..40).map { Thread { try { won += l.reserve("r1", 0.1, false) } catch (_: CapExceeded) {} } }.also { ts -> ts.forEach { it.start() }; ts.forEach { it.join() } }
        assertEquals(10, won.size) // 0.1 x 10 = 1.0, the cap itself is allowed
        assertEquals(10, l.inFlight().size)
    }

    @Test
    fun twoRunsCannotBothSpendWhatIsLeftOfTheDay() {
        repeat(30) {
            val l = ledger(InMemoryLedgerStorage(), Caps(runUsd = 1.0, dayUsd = 0.5))
            val ok = CopyOnWriteArrayList<String>()
            listOf("r1", "r2").map { run -> Thread { try { l.reserve(run, 0.3, false); ok += run } catch (_: CapExceeded) {} } }.also { ts -> ts.forEach { it.start() }; ts.forEach { it.join() } }
            assertEquals(1, ok.size, "both runs got through: $ok")
        }
    }

    @Test
    fun aSavedFileIsReplacedAtomicallyAndLeavesNoTemporaryFile() {
        val l = ledger()
        repeat(20) { l.release(l.reserve("r1", 0.01, false)) }
        assertEquals(listOf("ledger.json"), dir.list()!!.sorted())
    }

    @Test
    fun paidCallSettlesAtTheActualCostAndReleasesOnFailure() = runBlocking {
        val l = ledger(InMemoryLedgerStorage())
        assertEquals("ok", l.paidCall("r1", "S2", "p", "m", 0.2) { PaidResult("ok", 0.05) })
        assertEquals(0.05, l.runTotal("r1"))
        assertFailsWith<IllegalStateException> { l.paidCall("r1", "S2", "p", "m", 0.2) { error("boom") } }
        assertEquals(0.05, l.runTotal("r1")); assertTrue(l.inFlight().isEmpty()) // released, not charged
        assertEquals(0.05, l.dayTotal())
    }

    @Test
    fun aTimeoutMayStillBeBilledSoItIsRecordedAtItsEstimate() = runBlocking {
        val l = ledger(InMemoryLedgerStorage())
        assertFailsWith<ModelError.Timeout> { l.paidCall("r1", "S2", "p", "m", 0.2) { throw ModelError.Timeout("p", "slow") } }
        assertEquals(0.2, l.runTotal("r1")); assertEquals("Timeout", l.calls("r1").single().failed)
    }

    @Test
    fun aBlockedCallIsLoggedAndNotMade() = runBlocking {
        val l = ledger(InMemoryLedgerStorage(), Caps(runUsd = 0.1))
        var made = false
        assertFailsWith<CapExceeded> { l.paidCall("r1", "S2", "p", "m", 0.5) { made = true; PaidResult("x", 0.0) } }
        assertTrue(!made)
        assertEquals("run", l.blocked("r1").single().cap)
    }

    @Test
    fun aCancelledCallReleasesItsReservation() {
        val l = ledger(InMemoryLedgerStorage())
        runBlocking {
            val job = launch(Dispatchers.Default) { l.paidCall<String>("r1", "S2", "p", "m", 0.2) { delay(10_000); PaidResult("x", 0.0) } }
            delay(100)
            job.cancel(); job.join()
        }
        assertTrue(l.inFlight().isEmpty()); assertEquals(0.0, l.runTotal("r1"))
    }
}
