package app.epicreplay.media

import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.voice.AudioConverter
import app.epicreplay.core.voice.ElevenLabs
import java.io.File

/** A take from the provider (MP3) as the 48 kHz mono samples a take is kept as: the phone's decoder, a mix down to one channel and a resample (the PC's FFmpeg `-ac 1 -ar 48000`). */
class AndroidAudioConverter(private val cacheDir: File) : AudioConverter {
    override fun toWav48k(mp3: ByteArray): Pcm {
        val f = File.createTempFile("take", ".mp3", cacheDir)
        try {
            f.writeBytes(mp3)
            val d = SignalDecoder.decodeAudio(f.path, ElevenLabs.SAMPLE_RATE) ?: throw IllegalArgumentException("the take has no sound")
            return Pcm(d.samples, ElevenLabs.SAMPLE_RATE)
        } finally { f.delete() }
    }
}
