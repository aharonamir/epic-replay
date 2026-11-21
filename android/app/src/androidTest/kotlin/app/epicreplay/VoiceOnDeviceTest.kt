package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.audio.AudioAnalysis
import app.epicreplay.media.AndroidAudioConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T422 on the phone: a take from the provider (MP3, 44.1 kHz) as 48 kHz mono samples, and the measures on it (`shared/fixtures/voice/take_44100_128.mp3`, the recorded take of "Noa..."). */
@RunWith(AndroidJUnit4::class)
class VoiceOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test
    fun anMp3TakeIsDecodedToFortyEightKilohertzMonoAndMeasured() {
        val mp3 = inst.context.assets.open("fixtures/voice/take_44100_128.mp3").readBytes()
        val pcm = AndroidAudioConverter(inst.targetContext.cacheDir).toWav48k(mp3)
        assertEquals(48000, pcm.sampleRate)
        // the recorded take is 0.66 s of words in a little silence; an MP3 adds its own padding at both ends
        assertTrue("a take of about a second: ${pcm.durationS}", pcm.durationS in 0.6..1.3)
        val (a, b) = AudioAnalysis.trimBounds(pcm.samples, pcm.sampleRate)
        val trimmed = (b - a).toDouble() / pcm.sampleRate
        assertEquals("the words are 0.66 s: $trimmed", 0.66, trimmed, 0.15)
        assertTrue("there is speech to find the first onset of", AudioAnalysis.blockRms(pcm.samples, pcm.sampleRate).max() > 0.02f)
        val left = inst.targetContext.cacheDir.list { _, n -> n.startsWith("take") && n.endsWith(".mp3") }?.toList() ?: emptyList()
        assertTrue("the temporary file is gone: $left", left.isEmpty())
    }
}
