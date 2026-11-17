package app.epicreplay.core.budget

import app.epicreplay.core.io.Durable
import app.epicreplay.core.models.ModelError
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The three caps (settings; the PC's defaults). */
data class Caps(val runUsd: Double = 1.00, val dayUsd: Double = 20.00, val preCheckpointUsd: Double = 0.30)

class CapExceeded(val cap: String, val limit: Double, val wouldBe: Double) :
    Exception("%s cap %.2f USD would be exceeded (%.4f USD)".format(cap, limit, wouldBe))

/** A reservation held against the caps until the call settles. Its id is what the disk knows it by. */
class Reservation internal constructor(val id: Long, val runId: String, val estimateUsd: Double, val preCheckpoint: Boolean)

@Serializable
data class CallRecord(
    val stage: String, val provider: String, val model: String, val estimateUsd: Double, val actualUsd: Double,
    val cacheHit: Boolean, val preCheckpoint: Boolean, val at: String, val failed: String? = null,
)

@Serializable
data class BlockedRecord(
    val stage: String, val provider: String, val model: String, val estimateUsd: Double, val cap: String,
    val limitUsd: Double, val at: String,
)

@Serializable
data class HeldReservation(
    val id: Long, val runId: String, val estimateUsd: Double, val preCheckpoint: Boolean,
    val stage: String, val provider: String, val model: String, val at: String,
    /** The UTC day it was reserved on: its cost is charged to that day when it settles, or after a restart. */
    val day: String = "",
)

@Serializable
data class RunLedger(val calls: List<CallRecord> = emptyList(), val blocked: List<BlockedRecord> = emptyList())

/** Everything the ledger knows, written whole to its storage after every change. */
@Serializable
data class LedgerState(
    val days: Map<String, Double> = emptyMap(),
    val runs: Map<String, RunLedger> = emptyMap(),
    val reservations: List<HeldReservation> = emptyList(),
    val nextId: Long = 1,
)

/** Where the ledger keeps its state. A save is atomic: it leaves the old state or the new one. */
interface LedgerStorage {
    fun load(): LedgerState?
    fun save(state: LedgerState)
}

class InMemoryLedgerStorage : LedgerStorage {
    private var state: LedgerState? = null
    override fun load(): LedgerState? = state
    override fun save(state: LedgerState) { this.state = state }
}

/** One JSON file, written durably and replaced by a rename; the phone's app is one process, so this is the single writer. */
class JsonFileLedgerStorage(private val file: File) : LedgerStorage {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): LedgerState? = if (file.isFile) json.decodeFromString(LedgerState.serializer(), file.readText()) else null

    override fun save(state: LedgerState) {
        try {
            Durable.writeAtomic(file, json.encodeToString(LedgerState.serializer(), state).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw IllegalStateException("the cost ledger could not be written")
        }
    }
}

/**
 * The cost ledger of the whole app (`shared/contracts/model-backend.md`, "Budget"; `guards/budget.py`): every
 * run and every paid call, model or voice, reserves against the same three caps under one lock, so two runs
 * cannot both spend what is left of the day. A reservation is written to [storage] before [reserve] returns;
 * a reservation found there when the ledger starts again (the app was killed between the reservation and the
 * charge) is recorded as spent at its estimate, because the call may have been made and billed.
 */
class Ledger(
    private val storage: LedgerStorage,
    private val caps: Caps = Caps(),
    private val today: () -> String,
    private val now: () -> String,
) {
    private val lock = ReentrantLock()
    private var state: LedgerState = storage.load() ?: LedgerState()

    init {
        recoverAfterRestart()
    }

    private fun recoverAfterRestart() = lock.withLock {
        if (state.reservations.isEmpty()) return
        var s = state
        for (r in s.reservations) s = withCall(s, r.runId, CallRecord(r.stage, r.provider, r.model, r.estimateUsd, r.estimateUsd, false, r.preCheckpoint, now(), failed = "Restart"), r.day.ifEmpty { today() })
        state = s.copy(reservations = emptyList())
        storage.save(state)
    }

    private fun withCall(s: LedgerState, runId: String, call: CallRecord, day: String): LedgerState {
        val run = s.runs[runId] ?: RunLedger()
        return s.copy(
            runs = s.runs + (runId to run.copy(calls = run.calls + call)),
            days = s.days + (day to round6((s.days[day] ?: 0.0) + call.actualUsd)),
        )
    }

    /** Python's round(x, 6): the double's exact value, half to even. */
    private fun round6(x: Double) = BigDecimal(x).setScale(6, RoundingMode.HALF_EVEN).toDouble()

    fun runTotal(runId: String): Double = lock.withLock { round6(state.runs[runId]?.calls?.sumOf { it.actualUsd } ?: 0.0) }
    fun dayTotal(): Double = lock.withLock { state.days[today()] ?: 0.0 }
    fun calls(runId: String): List<CallRecord> = lock.withLock { state.runs[runId]?.calls ?: emptyList() }
    fun blocked(runId: String): List<BlockedRecord> = lock.withLock { state.runs[runId]?.blocked ?: emptyList() }
    fun inFlight(): List<HeldReservation> = lock.withLock { state.reservations }

    private fun preTotal(runId: String) = (state.runs[runId]?.calls ?: emptyList()).filter { it.preCheckpoint }.sumOf { it.actualUsd }

    /** Checks the caps against what is spent and in flight, and holds [estimateUsd], in one step. Throws [CapExceeded]; the call is then not made. */
    fun reserve(runId: String, estimateUsd: Double, preCheckpoint: Boolean, stage: String = "", provider: String = "", model: String = ""): Reservation = lock.withLock {
        val inRun = state.reservations.filter { it.runId == runId }
        val tests = buildList {
            add(Triple("run", caps.runUsd, (state.runs[runId]?.calls?.sumOf { it.actualUsd } ?: 0.0) + inRun.sumOf { it.estimateUsd } + estimateUsd))
            add(Triple("day", caps.dayUsd, (state.days[today()] ?: 0.0) + state.reservations.filter { it.day.isEmpty() || it.day == today() }.sumOf { it.estimateUsd } + estimateUsd)) // a call from an earlier day is charged to that day
            if (preCheckpoint) add(Triple("pre_checkpoint", caps.preCheckpointUsd, preTotal(runId) + inRun.filter { it.preCheckpoint }.sumOf { it.estimateUsd } + estimateUsd))
        }
        for ((cap, limit, wouldBe) in tests) {
            if (wouldBe > limit + 1e-9) {
                val run = state.runs[runId] ?: RunLedger()
                state = state.copy(runs = state.runs + (runId to run.copy(blocked = run.blocked + BlockedRecord(stage, provider, model, estimateUsd, cap, limit, now()))))
                storage.save(state)
                throw CapExceeded(cap, limit, wouldBe)
            }
        }
        val id = state.nextId
        state = state.copy(nextId = id + 1, reservations = state.reservations + HeldReservation(id, runId, estimateUsd, preCheckpoint, stage, provider, model, now(), today()))
        storage.save(state) // written before the call is made
        Reservation(id, runId, estimateUsd, preCheckpoint)
    }

    /** The call finished: the reservation is replaced by the actual cost. */
    fun settle(r: Reservation, actualUsd: Double, cacheHit: Boolean, failed: String? = null) = lock.withLock {
        val held = state.reservations.firstOrNull { it.id == r.id } ?: return@withLock
        val s = state.copy(reservations = state.reservations - held)
        state = withCall(s, r.runId, CallRecord(held.stage, held.provider, held.model, held.estimateUsd, actualUsd, cacheHit, held.preCheckpoint, now(), failed), held.day.ifEmpty { today() })
        storage.save(state)
    }

    /** The call failed in a way the provider may still bill (a timeout): recorded at its estimate. */
    fun settleAtEstimate(r: Reservation, failed: String) = settle(r, r.estimateUsd, false, failed)

    /** The call failed and was not billed: the reservation is dropped. */
    fun release(r: Reservation) = lock.withLock {
        state = state.copy(reservations = state.reservations.filter { it.id != r.id })
        storage.save(state)
    }
}

class PaidResult<T>(val value: T, val actualUsd: Double, val cacheHit: Boolean = false)

/**
 * Reserves [estimateUsd], makes the call, and replaces the reservation with the actual cost; a recording hit
 * costs nothing. A timeout may still be billed, so it is recorded at the estimate; any other failure releases it.
 */
suspend fun <T> Ledger.paidCall(
    runId: String, stage: String, provider: String, model: String, estimateUsd: Double,
    preCheckpoint: Boolean = false, fn: suspend () -> PaidResult<T>,
): T {
    val r = reserve(runId, estimateUsd, preCheckpoint, stage, provider, model)
    val res = try {
        fn()
    } catch (e: ModelError.Timeout) {
        settleAtEstimate(r, e.javaClass.simpleName)
        throw e
    } catch (e: Throwable) {
        release(r)
        throw e
    }
    settle(r, res.actualUsd, res.cacheHit)
    return res.value
}
