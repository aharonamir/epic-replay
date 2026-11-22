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

    /** The luma plane, averaged down to [WIDTH] pixels wide when it is wider. */
    private fun small(image: android.media.Image): Gray {
        val crop = image.cropRect
        val w = crop.width(); val h = crop.height()
        val plane = image.planes[0]
        val buf = plane.buffer; val stride = plane.rowStride; val px = plane.pixelStride
        val scale = Math.max(1, w / WIDTH)
        val ow = w / scale; val oh = h / scale
        val out = ByteArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            var s = 0
            for (j in 0 until scale) for (i in 0 until scale)
                s += buf.get((crop.top + y * scale + j) * stride + (crop.left + x * scale + i) * px).toInt() and 0xFF
            out[y * ow + x] = ((s + scale * scale / 2) / (scale * scale)).toByte()
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
                } else if (o >= 0) {
                    if (info.size > 0) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        val raw = codec.getOutputBuffer(o)!!
                        raw.position(info.offset); raw.limit(info.offset + info.size)
                        val s = raw.order(ByteOrder.nativeOrder()).asShortBuffer()
                        chunks.add(ShortArray(info.size / 2).also { s.get(it) })
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

    /** The first audio track with its channels kept apart (one or two; more are cut to the first two) at [targetRate], or null when there is none. */
    fun decodeChannels(path: String, targetRate: Int): List<FloatArray>? {
        val r = decodeRaw(path) ?: return null
        val keep = minOf(2, r.channels)
        return List(keep) { c -> resample(FloatArray(r.interleaved.size / r.channels) { n -> r.interleaved[n * r.channels + c] / 32768f }, r.rate, targetRate) }
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
