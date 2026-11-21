package app.epicreplay.core.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A take: mono samples in -1 to 1 and their rate. */
class Pcm(val samples: FloatArray, val sampleRate: Int) {
    val durationS: Double get() = samples.size.toDouble() / sampleRate
}

/** 16-bit PCM WAV files, as `media/audio.py` reads and writes them. */
object Wav {
    /** Reads a 16-bit PCM WAV; several channels are averaged. */
    fun read(bytes: ByteArray): Pcm {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") { channels = b.getShort(body + 2).toInt(); rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt() }
            if (id == "data") {
                require(bits == 16) { "only 16-bit WAV is read" }
                val len = minOf(size.toLong() and 0xFFFFFFFFL, (bytes.size - body).toLong()).toInt()
                val frames = len / 2 / channels
                val out = FloatArray(frames) { f ->
                    var s = 0f
                    for (c in 0 until channels) s += b.getShort(body + (f * channels + c) * 2) / 32768f
                    if (channels > 1) s / channels else s
                }
                return Pcm(out, rate)
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("no data in the WAV file")
    }

    /** Writes mono 16-bit PCM, the samples clipped to -1 to 1 and cut toward zero as NumPy's `astype('<i2')` does. */
    fun write(pcm: Pcm): ByteArray {
        val n = pcm.samples.size
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(pcm.sampleRate).putInt(pcm.sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n * 2)
        for (s in pcm.samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }
}
