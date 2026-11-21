package app.epicreplay.media

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import app.epicreplay.core.visuals.BlendSchedule
import app.epicreplay.core.visuals.BlendStep

/**
 * Turns the picture of a slowed segment into a steady [fps] a second by mixing frames (spec 004 T421; spike S2: Media3 stretches the times of a 0.25
 * segment, so a source frame comes every 133 ms and nothing fills the gaps). Between two source frames it makes the frames the PC's FFmpeg `framerate`
 * makes: the two mixed, the later one more and more ([BlendSchedule]). Put it after the speed change and the size, so it mixes the pictures that are kept.
 */
@UnstableApi
class FrameBlendEffect(private val fps: Double = 30.0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean) = FrameBlendProgram(useHdr, fps)
}

/**
 * The program of [FrameBlendEffect]. One input frame can make several output frames, which Media3's base class does not do, so this keeps its own
 * pool of output textures and a copy of the previous source frame; when the pool is empty it waits for the consumer to give a texture back and goes on.
 */
@UnstableApi
class FrameBlendProgram(private val useHdr: Boolean, private val fps: Double) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT).apply { setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4) }
    private val all = ArrayList<GlTextureInfo>()
    private val free = ArrayDeque<GlTextureInfo>()
    private var previous: GlTextureInfo? = null
    private var previousUs = 0L
    private var havePrevious = false
    private var lastGapUs = 0L
    private var tail: List<BlendStep> = emptyList() // the last source frame held for the rest of its gap, once the input has ended
    private var tailNext = 0

    // the input frame being turned into outputs, and how far it has got
    private var current: GlTextureInfo? = null
    private var currentUs = 0L
    private var steps: List<BlendStep> = emptyList()
    private var next = 0
    private var first = false
    private var endPending = false
    private var ending = false // the input is over and the tail is being written
    private var width = 0
    private var height = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = throw UnsupportedOperationException("FrameBlendProgram draws its own frames")

    private fun ensure(provider: GlObjectsProvider, w: Int, h: Int) {
        if (w == width && h == height && all.isNotEmpty()) return
        releaseTextures()
        width = w; height = h
        repeat(POOL) { all += newTexture(provider, w, h) }
        free.addAll(all)
        previous = newTexture(provider, w, h)
    }

    private fun newTexture(provider: GlObjectsProvider, w: Int, h: Int): GlTextureInfo = provider.createBuffersForTexture(GlUtil.createTexture(w, h, useHdr), w, h)

    private fun draw(a: GlTextureInfo, b: GlTextureInfo, weight: Double, into: GlTextureInfo) {
        GlUtil.focusFramebufferUsingCurrentContext(into.fboId, into.width, into.height)
        GlUtil.clearFocusedBuffers()
        program.use()
        program.setSamplerTexIdUniform("uTexA", a.texId, 0)
        program.setSamplerTexIdUniform("uTexB", b.texId, 1)
        program.setFloatUniform("uWeight", weight.toFloat())
        program.bindAttributesAndUniforms()
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_FAN, 0, 4)
        GlUtil.checkGlError()
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        try {
            ensure(glObjectsProvider, inputTexture.width, inputTexture.height)
            current = inputTexture; currentUs = presentationTimeUs; next = 0
            first = !havePrevious
            steps = if (first) listOf(BlendStep(presentationTimeUs, 1.0)) else BlendSchedule.steps(previousUs, presentationTimeUs, fps)
            pump()
        } catch (e: Exception) { onError(e) }
    }

    /** Makes the outputs that a free texture allows; when the input frame is done it becomes the previous one and is given back. */
    private fun pump() {
        try {
            val cur = current ?: return
            while (next < steps.size && free.isNotEmpty()) {
                val out = free.removeFirst()
                val step = steps[next]
                if (first) draw(cur, cur, 1.0, out) else draw(previous!!, cur, step.weight, out)
                getOutputListener().onOutputFrameAvailable(out, step.timeUs)
                next++
            }
            if (next >= steps.size) {
                val prev = previous!!
                draw(cur, cur, 1.0, prev) // the copy that the next gap starts from
                if (havePrevious) lastGapUs = currentUs - previousUs
                previousUs = currentUs; havePrevious = true
                current = null
                getInputListener().onInputFrameProcessed(cur)
                getInputListener().onReadyToAcceptInputFrame()
                if (endPending) { endPending = false; startTail() }
            }
        } catch (e: Exception) { onError(e) }
    }

    /** At the end of the input the last source frame is held for the rest of its gap, as FFmpeg's `framerate` holds it (a gap's worth of frames in all). */
    private fun startTail() {
        tail = if (havePrevious && lastGapUs > 0) BlendSchedule.steps(previousUs, previousUs + lastGapUs, fps).dropLast(1) else emptyList()
        tailNext = 0
        pumpTail()
    }

    private fun pumpTail() {
        try {
            val prev = previous
            while (prev != null && tailNext < tail.size && free.isNotEmpty()) {
                val out = free.removeFirst()
                draw(prev, prev, 1.0, out)
                getOutputListener().onOutputFrameAvailable(out, tail[tailNext].timeUs)
                tailNext++
            }
            if (tailNext >= tail.size && ending) { ending = false; tail = emptyList(); resetStream(); getOutputListener().onCurrentOutputStreamEnded() }
        } catch (e: Exception) { onError(e) }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (outputTexture !in all || outputTexture in free) return // not one of ours
        free.addLast(outputTexture)
        if (current != null) pump() else if (ending) pumpTail()
    }

    private fun resetStream() { havePrevious = false; previousUs = 0L; lastGapUs = 0L; steps = emptyList(); next = 0 }

    override fun signalEndOfCurrentInputStream() {
        ending = true
        if (current != null) endPending = true else startTail()
    }

    override fun flush() {
        current = null; endPending = false; ending = false; tail = emptyList(); tailNext = 0; resetStream()
        free.clear(); free.addAll(all)
        getInputListener().onFlush()
        getInputListener().onReadyToAcceptInputFrame()
    }

    private fun releaseTextures() {
        try { all.forEach { it.release() }; previous?.release() } catch (e: GlUtil.GlException) { onError(e) }
        all.clear(); free.clear(); previous = null; width = 0; height = 0
    }

    override fun release() {
        try { releaseTextures(); program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    companion object {
        private const val POOL = 6
        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexA;
            uniform sampler2D uTexB;
            uniform float uWeight;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_FragColor = mix(texture2D(uTexA, vTexSamplingCoord), texture2D(uTexB, vTexSamplingCoord), uWeight);
            }
        """
    }
}
