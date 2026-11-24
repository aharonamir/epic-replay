package app.epicreplay.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.epicreplay.core.signals.Audio
import app.epicreplay.core.signals.Gray
import app.epicreplay.core.signals.SignalScan
import app.epicreplay.core.signals.Signals
import java.nio.ByteOrder

/**
 * The phone's decoders feeding the S1 scan (T413): the clip's frames as small grey pictures (320 wide, from the luma plane)
 * and its sound as mono samples at 22,050 Hz, the same things `ffmpeg` and OpenCV hand the Python scan.
 */
object SignalDecoder {
    const val WIDTH = 320
    const val WORK_FPS = 30.0

    /** Scan the clip at [path]; frames are decoded one at a time as the scan reads them. */
    fun scan(path: String, top: Int = 3): Signals {
        val audio = decodeAudio(path)
        val video = openVideo(path)
        val has = audio != null && audio.samples.size > Audio.SR / 10
        val offset = if (audio != null && video.firstPtsUs != null) (audio.firstPtsUs - video.firstPtsUs) / 1e6 else 0.0
        return SignalScan.scan(video.frames, video.fps, if (has) audio!!.samples else FloatArray(0), if (has) offset else 0.0, top)
    }

    class Video(val fps: Double, val frames: Sequence<Gray>, val firstPtsUs: Long?)
    class Pcm(val samples: FloatArray, val firstPtsUs: Long)

    private fun track(ex: MediaExtractor, prefix: String): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }

    fun openVideo(path: String): Video {
        val probe = MediaExtractor().apply { setDataSource(path) }
        val t = track(probe, "video/") ?: run { probe.release(); throw IllegalArgumentException("no video track in $path") }
        val format = probe.getTrackFormat(t)
        probe.release()
        val decoded = sequence<Pair<Long, Gray>> {
            val ex = MediaExtractor().apply { setDataSource(path) }
            val vt = track(ex, "video/")!!
            ex.selectTrack(vt)
            val f = ex.getTrackFormat(vt)
            val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(f, null, null, 0); codec.start()
            try {
                val info = MediaCodec.BufferInfo()
                var inputDone = false; var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        if (info.size > 0) {
                            val image = codec.getOutputImage(o)
                            if (image != null) { yield(info.presentationTimeUs to small(image)); image.close() }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally { codec.stop(); codec.release(); ex.release() }
        }
        return Video(WORK_FPS, app.epicreplay.core.signals.Resample.atThirtyFramesASecond(decoded), firstPts(path))
    }

    /** The presentation time of the first video sample, from the container. */
    private fun firstPts(path: String): Long? {
        val ex = MediaExtractor().apply { setDataSource(path) }
        return try { track(ex, "video/")?.let { ex.selectTrack(it); ex.sampleTime.takeIf { t -> t >= 0 } } } finally { ex.release() }
    }

    /**
     * The luma plane as a grey picture [WIDTH] wide when the frame is wider (as the PC's `cv2.resize(frame, (320, round(h * 320 / w)))`), each output pixel the mean of the source pixels it covers;
     * a frame no wider than [WIDTH] is kept as it is. (An integer-only reduction left a 608-wide phone clip at 608 and made the scan three times slower than it needs to be.)
     * Each source row is read from the buffer once, as a whole.
     */
    private fun small(image: android.media.Image): Gray {
        val crop = image.cropRect
        val w = crop.width(); val h = crop.height()
        val plane = image.planes[0]
        val buf = plane.buffer; val stride = plane.rowStride; val px = plane.pixelStride
        val ow = if (w > WIDTH) WIDTH else w
        val oh = if (w > WIDTH) Math.max(2, Math.rint(h.toDouble() * WIDTH / w).toInt()) else h
        val xLo = IntArray(ow) { (it.toLong() * w / ow).toInt() }; val xHi = IntArray(ow) { Math.min(w, Math.max(xLo[it] + 1, Math.ceil((it + 1).toDouble() * w / ow).toInt())) }
        val out = ByteArray(ow * oh)
        val row = ByteArray(stride + px)
        val sum = IntArray(ow)
        var loadedRow = -1
        fun load(y: Int) { // one source row, whole
            if (y == loadedRow) return
            val start = (crop.top + y) * stride + crop.left * px
            buf.position(start); buf.get(row, 0, Math.min(w * px, buf.limit() - start)); loadedRow = y
        }
        for (oy in 0 until oh) {
            val y0 = (oy.toLong() * h / oh).toInt(); val y1 = Math.min(h, Math.max(y0 + 1, Math.ceil((oy + 1).toDouble() * h / oh).toInt()))
            java.util.Arrays.fill(sum, 0)
            for (y in y0 until y1) {
                load(y)
                for (ox in 0 until ow) { var s = 0; for (x in xLo[ox] until xHi[ox]) s += row[x * px].toInt() and 0xFF; sum[ox] += s }
            }
            for (ox in 0 until ow) { val n = (y1 - y0) * (xHi[ox] - xLo[ox]); out[oy * ow + ox] = ((sum[ox] + n / 2) / n).toByte() }
        }
        return Gray(ow, oh, out)
    }

    /** The clip's sound as mono floats at [Audio.SR], or null when there is no audio track. */
    private class Raw(val interleaved: ShortArray, val rate: Int, val channels: Int, val firstPts: Long)

    private fun decodeRaw(path: String): Raw? {
        val ex = MediaExtractor().apply { setDataSource(path) }
        val t = track(ex, "audio/")
        if (t == null) { ex.release(); return null }
        ex.selectTrack(t)
        val f = ex.getTrackFormat(t)
        val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(f, null, null, 0); codec.start()
        val chunks = ArrayList<ShortArray>()
        var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var firstPts = -1L
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        val raw = codec.getOutputBuffer(o)!!
                        raw.position(info.offset); raw.limit(info.offset + info.size)
                        raw.order(ByteOrder.nativeOrder())
                        chunks.add(when (encoding) { // the decoder's own format: 16-bit unless it says otherwise
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> raw.asFloatBuffer().let { f -> ShortArray(info.size / 4) { (f.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() } }
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> ShortArray(info.size) { (((raw.get().toInt() and 0xFF) - 128) shl 8).toShort() }
                            else -> raw.asShortBuffer().let { sb -> ShortArray(info.size / 2).also { sb.get(it) } }
                        })
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        val all = ShortArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        return Raw(all, rate, channels, firstPts)
    }

    /** The first audio track as mono at [targetRate] (the channels averaged), with the presentation time of its first sample. */
    fun decodeAudio(path: String, targetRate: Int = Audio.SR): Pcm? {
        val r = decodeRaw(path) ?: return null
        val mono = FloatArray(r.interleaved.size / r.channels) { n -> var s = 0f; for (c in 0 until r.channels) s += r.interleaved[n * r.channels + c]; s / r.channels / 32768f }
        return Pcm(resample(mono, r.rate, targetRate), Math.max(0L, r.firstPts))
    }

    /**
     * The first audio track as one channel (mono) or two (stereo) at [targetRate], or null when there is none. More than two channels are mixed down: 5.1 as FFmpeg's
     * default does (the front pair, the centre and the back pair at 0.707 each, the effects channel left out, the sum scaled so it cannot clip); any other count as the average of all.
     */
    fun decodeChannels(path: String, targetRate: Int): List<FloatArray>? {
        val r = decodeRaw(path) ?: return null
        val n = r.interleaved.size / r.channels
        fun ch(c: Int, i: Int) = r.interleaved[i * r.channels + c] / 32768f
        val out: List<FloatArray> = when {
            r.channels <= 2 -> List(r.channels) { c -> FloatArray(n) { ch(c, it) } }
            r.channels == 6 -> { val k = 0.7071068f; val norm = 1f / (1f + 2 * k); listOf(FloatArray(n) { (ch(0, it) + k * ch(2, it) + k * ch(4, it)) * norm }, FloatArray(n) { (ch(1, it) + k * ch(2, it) + k * ch(5, it)) * norm }) }
            else -> { val m = FloatArray(n) { i -> var s = 0f; for (c in 0 until r.channels) s += ch(c, i); s / r.channels }; listOf(m, m.copyOf()) }
        }
        return out.map { resample(it, r.rate, targetRate) }
    }

    /** Linear resampling to [target]. */
    private fun resample(x: FloatArray, rate: Int, target: Int): FloatArray {
        if (rate == target) return x
        val n = (x.size.toLong() * target / rate).toInt()
        return FloatArray(n) { i ->
            val p = i.toDouble() * rate / target; val a = p.toInt(); val b = Math.min(x.size - 1, a + 1); val fr = (p - a).toFloat()
            x[Math.min(a, x.size - 1)] * (1 - fr) + x[b] * fr
        }
    }

}
