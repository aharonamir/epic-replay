package app.epicreplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.media.ClipFrames
import app.epicreplay.media.ClipIntake
import app.epicreplay.media.SignalDecoder
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T420 on the phone: the PC's intake tests (`pc/tests/unit/test_intake.py`) on the same kinds of synthetic clips (`shared/fixtures/intake/`, made by `pc/tools/make_intake_fixtures.py`). */
@RunWith(AndroidJUnit4::class)
class IntakeOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val assets = inst.context.assets
    private val cache = inst.targetContext.cacheDir

    private fun clip(name: String): File = File(cache, "intake-in/$name").also { f -> f.parentFile!!.mkdirs(); assets.open("fixtures/intake/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
    private fun runDir(): File = File(cache, "intake-run-${System.nanoTime()}").also { it.mkdirs() }
    private fun meta(d: File) = Json.parseToJsonElement(File(d, "clip_meta.json").readText()).jsonObject
    private fun num(o: kotlinx.serialization.json.JsonObject, k: String) = o.getValue(k).jsonPrimitive.content.toDouble()

    @Test
    fun aValidClipGivesAWorkingCopyAndClipMeta() {
        val up = clip("ok_25fps_with_sound.mp4"); val d = runDir()
        val r = ClipIntake.run(up, "upload.mp4", d)
        assertEquals(r.note, "ok", r.status)
        val m = meta(d)
        val fixture = Json.parseToJsonElement(assets.open("fixtures/sample_run/clip_meta.json").bufferedReader().readText()).jsonObject
        assertEquals(fixture.keys, m.keys) // the same fields as the sample run's
        assertEquals(30.0, num(m, "fps"), 0.0); assertEquals(25.0, num(m, "capture_fps"), 0.5); assertEquals(0.0, num(m, "rotation"), 0.0)
        assertEquals("true", m.getValue("has_audio").jsonPrimitive.content); assertEquals(4.0, num(m, "duration_s"), 0.2)
        assertEquals(320.0, num(m, "width"), 0.0); assertEquals(180.0, num(m, "height"), 0.0)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(up.readBytes()).joinToString("") { "%02x".format(it) }, m.getValue("source_sha256").jsonPrimitive.content)
        assertTrue(File(d, "work.mp4").length() == up.length())
    }

    @Test
    fun aLargeFrameIsScaledTo1080LinesAndAClipWithNoSoundSaysSo() {
        val d = runDir()
        assertEquals("ok", ClipIntake.run(clip("large_1080p_no_sound.mp4"), "upload.mp4", d).status)
        val m = meta(d)
        assertEquals(1920.0, num(m, "width"), 0.0); assertEquals(1080.0, num(m, "height"), 0.0)
        assertEquals("false", m.getValue("has_audio").jsonPrimitive.content)
    }

    @Test
    fun aRotatedClipComesOutUprightAndAVerticalOneStaysVerticalWithItsCaptureRate() {
        val d1 = runDir()
        assertEquals("ok", ClipIntake.run(clip("rotated_90.mp4"), "upload.mp4", d1).status)
        val m1 = meta(d1)
        assertEquals(180.0, num(m1, "width"), 0.0); assertEquals(320.0, num(m1, "height"), 0.0); assertEquals(0.0, num(m1, "rotation"), 0.0)
        val d2 = runDir()
        assertEquals("ok", ClipIntake.run(clip("high_rate_120fps.mp4"), "upload.mp4", d2).status)
        val m2 = meta(d2)
        assertEquals(180.0, num(m2, "width"), 0.0); assertEquals(320.0, num(m2, "height"), 0.0)
        assertEquals(30.0, num(m2, "fps"), 0.0); assertEquals(120.0, num(m2, "capture_fps"), 2.0)
    }

    @Test
    fun clipsOutsideThreeToThirtySecondsAndOtherFilesAreRefusedWithTheReason() {
        fun refused(name: String, asName: String = "upload.mp4"): String {
            val d = runDir()
            val r = ClipIntake.run(clip(name), asName, d)
            assertEquals("failed", r.status); assertFalse(File(d, "clip_meta.json").exists()); assertFalse(File(d, "work.mp4").exists())
            return r.note
        }
        refused("too_long_40s.mp4").let { assertTrue(it, "40.0 s" in it && "3 to 30" in it) }
        refused("too_short_2s.mp4").let { assertTrue(it, "3 to 30" in it) }
        refused("wrong_type.avi", "upload.avi").let { assertTrue(it, "MP4, MOV or WebM" in it) }
        refused("not_a_video.mp4").let { assertTrue(it, "could not be read" in it) }
        val cut = refused("cut_short.mp4")
        assertTrue("a file cut short is refused on what can be read: $cut", cut.startsWith("Only ") && "3 to 30" in cut || "could not be" in cut)
    }

    @Test
    fun exactlyThirtySecondsIsAccepted() {
        val d = runDir()
        assertEquals("ok", ClipIntake.run(clip("exactly_30s.mp4"), "upload.mp4", d).status)
        assertEquals(30.0, num(meta(d), "duration_s"), 0.2)
    }

    @Test
    fun theClipIsReadAtThirtyFramesASecondByTime() {
        val up = clip("ok_25fps_with_sound.mp4")
        ClipFrames(up.path).use { f ->
            assertTrue("the 4 s clip has about 120 frames at 30 a second: ${f.count}", f.count in 118..121)
            assertNotNull(f.frameAt(0)); assertNotNull(f.frameAt(f.count - 1)); assertNull(f.frameAt(f.count)); assertNull(f.frameAt(-1))
        }
        val s = SignalDecoder.scan(up.path, top = 1)
        assertEquals(30.0, s.fps, 0.0)
        assertTrue("the signal scan sees the same count: ${s.frames}", s.frames in 118..122)
    }
}
