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
    @Volatile private var handle = 0L
    override val mediaMarker: String get() = nativeMarker()

    override fun load(modelPath: String, projectorPath: String?, contextTokens: Int, threads: Int, imageMaxTokens: Int) {
        check(handle == 0L) { "a model is already loaded" }
        handle = nativeLoad(modelPath, projectorPath ?: "", contextTokens, threads, imageMaxTokens)
    }

    override fun generate(request: GenerateRequest): GenerateResult {
        val h = handle
        check(h != 0L) { "no model is loaded" }
        val stats = LongArray(6)
        val text = nativeGenerate(
            h, request.roles.toTypedArray(), request.contents.toTypedArray(), request.images.toTypedArray(),
            request.schema ?: "", request.maxTokens, request.temperature, request.thinking, stats,
        )
        val stop = when (stats[2]) { 0L -> Stop.END_OF_TEXT; 1L -> Stop.TOKEN_LIMIT; else -> Stop.CANCELLED }
        return GenerateResult(text, stats[0], stats[1], stop, stats[3], stats[4], stats[5])
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeFree(h)
    }

    private external fun nativeMarker(): String
    private external fun nativeLoad(modelPath: String, projectorPath: String, contextTokens: Int, threads: Int, imageMaxTokens: Int): Long
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    private external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<String>, images: Array<ByteArray>, schema: String,
        maxTokens: Int, temperature: Float, thinking: Boolean, stats: LongArray,
    ): String

    companion object {
        init {
            System.loadLibrary("epic_engine")
        }
    }
}
