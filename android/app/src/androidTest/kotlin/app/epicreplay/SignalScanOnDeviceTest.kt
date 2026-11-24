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
        // a lossy encode may add weak peaks (the keyframes); the strike must be stronger than every other candidate
        assertTrue(s.candidates.filter { it !== top }.all { it.score < top.score })
    }

    /**
     * The scan's speed (T427 asks for it; the owner's first full run took 279 s for a 20 s, 608 by 1080 clip): the 15 s 1080p fixture is timed and held to a ceiling, and the newest clip that is
     * on the phone from a real run is timed too when there is one (its frames are read by the decoder only; nothing is shown or kept but the seconds).
     */
    @Test
    fun theScanOfAClipTakesAFewSecondsAFrameNotAThirdOfASecond() {
        val clip = File(cache, "work_15s_1080p.mp4"); copy("fixtures/clip/work_15s_1080p.mp4", clip)
        val t0 = System.nanoTime(); val s = SignalDecoder.scan(clip.path); val secs = (System.nanoTime() - t0) / 1e9
        val log = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "t426_scan.txt")
        log.appendText("fixture 1080p ${s.frames} frames: $secs s (${secs / s.frames * 1000} ms a frame)\n")
        assertTrue("the 15 s 1080p clip took $secs s", secs < 20.0)
        val runs = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "runs")
        val real = runs.listFiles()?.filter { File(it, "work.mp4").isFile }?.maxByOrNull { it.name }?.let { File(it, "work.mp4") }
        if (real != null) {
            val t1 = System.nanoTime(); val r = SignalDecoder.scan(real.path); val rs = (System.nanoTime() - t1) / 1e9
            log.appendText("a real run's clip ${r.frames} frames: $rs s (${rs / r.frames * 1000} ms a frame)\n")
        }
    }
}
