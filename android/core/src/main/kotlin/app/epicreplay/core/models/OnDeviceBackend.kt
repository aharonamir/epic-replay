package app.epicreplay.core.models

import app.epicreplay.core.json.PythonJson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Why a native generation stopped. */
enum class Stop { END_OF_TEXT, TOKEN_LIMIT, CANCELLED }

class GenerateRequest(
    val roles: List<String>,
    val contents: List<String>,
    val images: List<ByteArray>,
    /** The JSON Schema as text, or null: the reply is then constrained to match it. */
    val schema: String?,
    val maxTokens: Int,
    val temperature: Float,
    val thinking: Boolean,
)

class GenerateResult(
    val text: String, val promptTokens: Long, val completionTokens: Long, val stop: Stop,
    val promptMs: Long, val generationMs: Long, val imageEncodingMs: Long,
)

/**
 * One loaded model and the one operation that runs it. The Android implementation is the JNI bridge to
 * llama.cpp and libmtmd; tests use a fake. [generate] blocks, and returns soon after [cancel] is called
 * from another thread (a running image encoding is the one thing that cannot be interrupted). [close] is called
 * only on the engine's own thread, after the running call has returned.
 */
interface InferenceEngine : AutoCloseable {
    /** The text the prompt uses where an image goes. */
    val mediaMarker: String
    fun load(modelPath: String, projectorPath: String?, contextTokens: Int, threads: Int, imageMaxTokens: Int, repack: Boolean = false)

    /**
     * Runs one call, numbered [op] (from 1). A [cancel] for the same number stops it even when the cancel
     * came before the call began; a cancel for another number does nothing.
     */
    fun generate(request: GenerateRequest, op: Long): GenerateResult
    fun cancel(op: Long)
}

/** Free memory for the check before a model is loaded (spec 004 FR-014). */
fun interface MemoryProbe {
    fun freeMb(): Long
}

/** The knobs that an on-device profile does not carry (FR-001 leaves `timeout_s` out of it). */
data class OnDeviceConfig(
    val visionTimeoutS: Double = 300.0,
    val textTimeoutS: Double = 120.0,
    val threads: Int = 6,
    val maxTokens: Int = 1024,
    val imageMaxTokens: Int = 280,
    /**
     * Whether the CPU backend may copy the weights into its own repacked layout. Off: the repacked copy is 3 GB of anonymous memory that Android can only swap, and on a phone with its other apps
     * open the swapping made a sheet call take 300 s or more (T427: the same calls took about 19 s each without it, and the whole of S2 162 s instead of 600 s with three calls left out).
     */
    val repack: Boolean = false,
    /** How long a cancelled call is waited for. A native call that does not stop in this time is abandoned (T427: one clip's sheet kept the engine at 100 % of a core for over an hour and a 300 s timeout could not end it). */
    val cancelGraceS: Double = 20.0,
    val temperature: Float = 0.2f,
    /** Off for a thinking model: its replies would otherwise be spent on reasoning (spike S1). */
    val thinking: Boolean = false,
)

/**
 * The backend for an `on_device` profile (spec 004 FR-012 to FR-014). Calls are serialised, one at a time;
 * the model is loaded on the first call, after the memory check and a check that its files are there;
 * a call that is cancelled or runs out of time stops the running generation and leaves the engine ready
 * for the next call. The reply costs nothing (`usd` 0).
 */
class OnDeviceBackend(
    override val profile: OnDeviceProfile,
    private val role: Role,
    private val modelDir: File,
    private val memory: MemoryProbe,
    private val engineFactory: () -> InferenceEngine,
    private val config: OnDeviceConfig = OnDeviceConfig(),
    private val nanoTime: () -> Long = System::nanoTime,
) : ModelBackend, AutoCloseable {
    init {
        require(role in profile.roles) { "model profile ${profile.id} is not for the ${role.id} role" }
    }

    private val mutex = Mutex()
    // Whoever takes the engine out with getAndSet(null) owns freeing it: close() and a load that finishes after close() cannot both free it.
    private val engineRef = java.util.concurrent.atomic.AtomicReference<InferenceEngine?>(null)
    // The engine runs on one thread with a big stack: the chat template and the prompt are parsed recursively.
    @Volatile private var executor = newExecutor()
    @Volatile private var dispatcher = executor.asCoroutineDispatcher()
    private fun newExecutor() = Executors.newSingleThreadExecutor { r -> Thread(null, r, "epic-engine", 16L * 1024 * 1024) }

    fun file(role: String): File? = profile.files.firstOrNull { it.role == role }?.let { File(modelDir, it.name) }

    /** The names of the profile's files that are not in the model folder. */
    fun missingFiles(): List<String> = profile.files.filter { !File(modelDir, it.name).isFile }.map { it.name }

    override suspend fun look(images: List<LabeledImage>, question: String, schema: JsonObject?, detail: String): ModelResult =
        run(config.visionTimeoutS, schema != null) { marker ->
            val content = buildString {
                for (img in images) {
                    if (!img.label.isNullOrEmpty()) append(img.label).append('\n')
                    append(marker).append('\n')
                }
                append(if (schema == null) question else question + CloudBackend.SCHEMA_LINE + PythonJson.dumps(schema))
            }
            GenerateRequest(listOf("user"), listOf(content), images.map { it.jpeg }, schema?.toString(), config.maxTokens, config.temperature, config.thinking)
        }

    override suspend fun chat(messages: List<ChatMessage>, schema: JsonObject?, tools: List<ToolDef>?): ModelResult {
        require(tools.isNullOrEmpty() || profile.supportsTools) { "model profile ${profile.id} does not support tool calls" }
        return run(config.textTimeoutS, schema != null) {
            GenerateRequest(messages.map { it.role.name.lowercase() }, messages.map { it.content }, emptyList(), schema?.toString(), config.maxTokens, config.temperature, config.thinking)
        }
    }

    private val ops = AtomicLong(0)
    @Volatile private var activeOp = 0L
    @Volatile private var closed = false

    /** [build] makes the request from the engine's image marker, which is known once the engine exists. */
    private suspend fun run(timeoutS: Double, withSchema: Boolean, build: (marker: String) -> GenerateRequest): ModelResult {
        val started = nanoTime()
        // The timeout is the generation's own: the wait for the calls before it and the loading of the model (a minute or more on a phone short of memory, T428) are not counted in it, as a
        // call that had the engine to itself would not count them. withTimeoutOrNull ends only this call's own timeout with null; a timeout or cancellation of the caller passes through unchanged.
        return mutex.withLock {
            check(!closed) { "the on-device backend for profile ${profile.id} was closed" } // a call that was waiting when close() came
            val e = ensureLoaded()
            withTimeoutOrNull((timeoutS * 1000).toLong()) {
                val request = build(e.mediaMarker)
                val op = ops.incrementAndGet()
                activeOp = op
                val r = blocking(e, op) { e.generate(request, op) }
                if (r.stop == Stop.CANCELLED) throw CancellationException("the call was cancelled")
                val data = if (withSchema) FirstJson.of(r.text) as? JsonObject else null
                ModelResult(r.text, data, emptyList(), Usage(r.promptTokens, r.completionTokens), 0.0, (nanoTime() - started) / 1e9)
            } ?: throw ModelError.Timeout(profile.id, "the on-device call took longer than ${timeoutS.toLong()} s")
        }
    }

    /** Loads the model on the first call: the files first, then the memory, never a crash for lack of either. */
    private suspend fun ensureLoaded(): InferenceEngine {
        engineRef.get()?.let { return it }
        val missing = missingFiles()
        if (missing.isNotEmpty()) throw ModelError.NotInstalled(profile.id, missing)
        val free = memory.freeMb()
        if (free < profile.minFreeRamMb) throw ModelError.OutOfMemory(profile.id, profile.minFreeRamMb, free.toInt())
        val e = engineFactory()
        try {
            // The load and the hand-over of the engine happen on the engine thread, in order with a close() that
            // may be queued behind them: close() frees whatever is in engineRef, on that same thread.
            blocking(e, 0) {
                e.load(file("weights")!!.path, file("projector")?.path, profile.contextTokens, config.threads, config.imageMaxTokens, config.repack)
                engineRef.set(e)
            }
        } catch (t: Throwable) {
            e.close() // not loaded (or the executor is shut down): nothing is running on it
            throw t
        }
        check(!closed) { "the on-device backend for profile ${profile.id} was closed" } // close() is queued behind the load and frees the engine
        return e
    }

    /**
     * Runs [block] on the engine thread. If this coroutine is cancelled, the engine is told to stop and
     * the block is waited for, so the engine is free again before the next call starts.
     */
    private suspend fun <T> blocking(e: InferenceEngine, op: Long, block: () -> T): T {
        // not a child of the caller: a child would keep the caller from finishing until a native call that does not stop had returned (a coroutine scope waits for its children)
        val job = try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher).async { block() }
        } catch (r: java.util.concurrent.RejectedExecutionException) {
            throw IllegalStateException("the on-device backend for profile ${profile.id} was closed")
        }
        try {
            return job.await()
        } catch (c: CancellationException) {
            e.cancel(op) // by number: it works even if the call has not started, and never touches a later call
            val stopped = withContext(NonCancellable) { withTimeoutOrNull((config.cancelGraceS * 1000).toLong()) { job.join() } }
            if (stopped == null) abandon(e) // it did not stop: the next call has an engine of its own
            throw c
        }
    }

    /**
     * An engine whose native call does not return after a cancel is given up: it is taken out, its thread left to finish if it ever does (a native call cannot be killed; its memory stays until then),
     * and the next call loads a new engine on a new thread. The caller gets its timeout at once instead of waiting for a call that may never end.
     */
    private fun abandon(e: InferenceEngine) {
        engineRef.compareAndSet(e, null)
        val old = executor
        executor = newExecutor(); dispatcher = executor.asCoroutineDispatcher()
        old.shutdown() // no new work for the stuck thread; it ends when its call does
        android_log("on-device engine abandoned: a native call did not stop ${config.cancelGraceS.toLong()} s after its cancel")
    }

    private fun android_log(message: String) { System.err.println("EpicReplay: $message") }

    /** Frees the model's memory; the next call loads it again. */
    suspend fun unload() = mutex.withLock {
        if (closed) return@withLock
        val e = engineRef.getAndSet(null) ?: return@withLock
        blocking(e, 0) { e.close() }
    }

    /**
     * Stops the call in flight, if any, and frees the model on the engine thread once that call has returned,
     * so the memory is never freed under a running generation. A call that arrives later fails.
     */
    override fun close() {
        closed = true // a call waiting for the mutex sees it when it gets in, and fails
        engineRef.get()?.cancel(activeOp)
        try {
            // queued behind any load or call on the engine thread; the executor shuts down after the free
            executor.execute {
                engineRef.getAndSet(null)?.close()
                executor.shutdown()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // closed before: nothing more to do
        }
    }

}
