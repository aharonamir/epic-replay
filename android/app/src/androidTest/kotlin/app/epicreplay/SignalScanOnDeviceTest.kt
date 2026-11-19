package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.core.conformance.ConformanceRunner
import app.epicreplay.media.SignalDecoder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T413 on the phone: the `signals` conformance cases run on ART, and the phone's decoders feed the scan the clip the recipe draws. */
@RunWith(AndroidJUnit4::class)
class SignalScanOnDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun copy(path: String, to: File) { to.parentFile!!.mkdirs(); assets.open(path).use { i -> to.outputStream().use { i.copyTo(it) } } }

    @Test
    fun everySignalsCasePassesOnTheDevice() {
        val shared = File(cache, "shared").apply { deleteRecursively() }
        val names = assets.list("conformance/signals")!!
        for (n in names) copy("conformance/signals/$n/case.json", File(shared, "conformance/signals/$n/case.json"))
        // the other functions' data is not needed here: only the signals area is run
        val runner = ConformanceRunner(shared, mapOf("signals.scan" to app.epicreplay.core.signals.Synthetic::scan))
        assertEquals(7, names.size)
        for (c in runner.cases()) assertEquals(c.name, emptyList<String>(), runner.check(c))
    }

    @Test
    fun theDecodersGiveTheScanTheStrikeAndItsSound() {
        val clip = File(cache, "strike_with_sound.mp4")
        copy("fixtures/signals/strike_with_sound.mp4", clip)
        val s = SignalDecoder.scan(clip.path)
        assertEquals(150, s.frames)
        assertEquals(30.0, s.fps, 0.0)
        assertTrue(s.hasSound)
        val top = s.candidates.maxByOrNull { it.score }
        assertNotNull(top)
        // the case's tolerance: 0.1 s. The strike is at 2.0 s and its thud begins at 1.974 s.
        assertEquals(2.0, top!!.t, 0.1)
        assertEquals(1.974, top.soundOnsetT!!, 0.1)
        assertTrue("a lossy encode may add weak peaks, but the strike is the strongest by far", s.candidates.count { it.score >= top.score } == 1)
    }
}
