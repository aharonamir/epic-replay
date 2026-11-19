package app.epicreplay.engine

import app.epicreplay.core.models.GenerateRequest
import app.epicreplay.core.models.GenerateResult
import app.epicreplay.core.models.InferenceEngine
import app.epicreplay.core.models.Stop

/**
 * The JNI bridge to llama.cpp and libmtmd (`native/engine_jni.cpp`, built into `libepic_engine.so`).
 * One instance holds one loaded model. [generate] blocks; [cancel] may be called from any thread and
 * makes a running [generate] return soon (an image encoding in progress finishes first).
 */
class NativeEngine : InferenceEngine {
    @Volatile private var handle = 0L // 0: no model
    override val mediaMarker: String get() = nativeMarker()

    override fun load(modelPath: String, projectorPath: String?, contextTokens: Int, threads: Int, imageMaxTokens: Int) {
        check(handle == 0L) { "a model is already loaded" }
        handle = nativeLoad(modelPath, projectorPath ?: "", contextTokens, threads, imageMaxTokens)
    }

    override fun generate(request: GenerateRequest, op: Long): GenerateResult {
        val h = handle
        check(h != 0L) { "no model is loaded" }
        val stats = LongArray(6)
        val reply = nativeGenerate(
            h, op, request.roles.toTypedArray(), request.contents.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            request.images.toTypedArray(), (request.schema ?: "").toByteArray(Charsets.UTF_8),
            request.maxTokens, request.temperature, request.thinking, stats,
        )
        val stop = when (stats[2]) { 0L -> Stop.END_OF_TEXT; 1L -> Stop.TOKEN_LIMIT; else -> Stop.CANCELLED }
        return GenerateResult(String(reply, Charsets.UTF_8), stats[0], stats[1], stop, stats[3], stats[4], stats[5])
    }

    /**
     * Callable from any thread, also after [close]: the handle is read and used under the same lock that frees it.
     * [generate] and [close] are not made safe against each other here: the caller runs both on one thread, and
     * frees only after the running call has returned (OnDeviceBackend does).
     */
    override fun cancel(op: Long) = synchronized(lock) {
        val h = handle
        if (h != 0L) nativeCancel(h, op)
    }

    override fun close() = synchronized(lock) {
        val h = handle
        handle = 0L
        if (h != 0L) nativeFree(h)
    }

    private val lock = Any()

    private external fun nativeMarker(): String
    private external fun nativeLoad(modelPath: String, projectorPath: String, contextTokens: Int, threads: Int, imageMaxTokens: Int): Long
    private external fun nativeCancel(handle: Long, op: Long)
    private external fun nativeFree(handle: Long)
    private external fun nativeGenerate(
        handle: Long, op: Long, roles: Array<String>, contents: Array<ByteArray>, images: Array<ByteArray>, schema: ByteArray,
        maxTokens: Int, temperature: Float, thinking: Boolean, stats: LongArray,
    ): ByteArray

    companion object {
        init {
            System.loadLibrary("epic_engine")
        }
    }
}
