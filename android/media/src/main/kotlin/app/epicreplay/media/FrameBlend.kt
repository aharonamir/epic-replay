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
import app.epicreplay.core.visuals.ClockOut
import app.epicreplay.core.visuals.FrameClock

/**
 * Makes the picture of a segment a steady [fps] a second whatever the source's frame rate (spec 004 T421): FFmpeg's `fps` for a cut (each picture is the
 * nearer source frame) and `framerate` for a slowed one (the two nearest source frames mixed; spike S2: Media3 stretches the times of a 0.25 segment, so a
 * source frame comes every 133 ms and nothing fills the gaps). With [frames] the segment has exactly that many pictures, the last one held if the source
 * runs out (the PC's `fit`). The clock is [FrameClock]. Put it after the speed change and the size, so it mixes the pictures that are kept.
 */
@UnstableApi
class FrameBlendEffect(private val fps: Double = 30.0, private val blend: Boolean = true, private val frames: Int? = null) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean) = FrameBlendProgram(useHdr, fps, blend, frames)
}

/**
 * The program of [FrameBlendEffect]. One input frame can make several output frames, which Media3's base class does not do, so this keeps its own
 * pool of output textures and a copy of the previous source frame; when the pool is empty it waits for the consumer to give a texture back and goes on.
 */
@UnstableApi
class FrameBlendProgram(private val useHdr: Boolean, private val fps: Double, private val blend: Boolean, private val limit: Int?) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT).apply { setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4) }
    private val all = ArrayList<GlTextureInfo>()
    private val free = ArrayDeque<GlTextureInfo>()
    private var previous: GlTextureInfo? = null
    private var clock = FrameClock(fps, blend, limit)
    private var havePrevious = false

    // the input frame being turned into pictures, the pictures still to make, and where the stream is
    private var current: GlTextureInfo? = null
    private var queue = ArrayDeque<ClockOut>()
    private var firstFrame = false
    private var endPending = false
    private var ending = false // the input is over and the held frames are being written
    private var pumping = false
    private var width = 0
    private var height = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = throw UnsupportedOperationException("FrameBlendProgram draws its own frames")

    private fun ensure(provider: GlObjectsProvider, w: Int, h: Int) {
        if (w == width && h == height && all.isNotEmpty()) return
        releaseTextures()
        havePrevious = false // the textures it was in are gone
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
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        try {
            ensure(glObjectsProvider, inputTexture.width, inputTexture.height)
            current = inputTexture
            firstFrame = !havePrevious
            queue = ArrayDeque(clock.onFrame(presentationTimeUs))
            pump()
        } catch (e: Exception) { onError(e) }
    }

    /** Makes the pictures that a free texture allows; when the input frame has made all of its own it becomes the previous one and is given back. */
    private fun pump() {
        if (pumping) return // a consumer that gives a texture back at once must not start the loop again
        pumping = true
        try {
            val cur = current ?: return
            while (queue.isNotEmpty() && free.isNotEmpty()) {
                val out = free.removeFirst()
                val step = queue.removeFirst() // before the consumer is told: it may call back
                if (firstFrame) draw(cur, cur, 1.0, out) else draw(previous!!, cur, step.weight, out)
                getOutputListener().onOutputFrameAvailable(out, step.timeUs)
            }
            if (queue.isEmpty()) {
                draw(cur, cur, 1.0, previous!!) // the copy that the next gap starts from
                havePrevious = true
                current = null
                getInputListener().onInputFrameProcessed(cur)
                getInputListener().onReadyToAcceptInputFrame()
                if (endPending) { endPending = false; startTail() }
            }
        } catch (e: Exception) { onError(e) } finally { pumping = false }
    }

    /** At the end of the input the last source frame is held for the pictures still wanted (the limit, or one more gap of the source). */
    private fun startTail() {
        queue = ArrayDeque(clock.finish())
        pumpTail()
    }

    private fun pumpTail() {
        if (pumping) return
        pumping = true
        try {
            val prev = previous
            while (prev != null && queue.isNotEmpty() && free.isNotEmpty()) {
                val out = free.removeFirst()
                val step = queue.removeFirst()
                draw(prev, prev, 1.0, out)
                getOutputListener().onOutputFrameAvailable(out, step.timeUs)
            }
            if (queue.isEmpty() && ending) { ending = false; resetStream(); getOutputListener().onCurrentOutputStreamEnded() }
        } catch (e: Exception) { onError(e) } finally { pumping = false }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (outputTexture !in all || outputTexture in free) return // not one of ours
        free.addLast(outputTexture)
        if (current != null) pump() else if (ending) pumpTail()
    }

    private fun resetStream() { havePrevious = false; clock = FrameClock(fps, blend, limit); queue = ArrayDeque() }

    override fun signalEndOfCurrentInputStream() {
        ending = true
        if (current != null) endPending = true else startTail()
    }

    override fun flush() {
        current = null; endPending = false; ending = false; resetStream()
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
