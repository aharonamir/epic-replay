package app.epicreplay

import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epicreplay.media.ClipFrames
import app.epicreplay.media.ClipProbe
import app.epicreplay.media.SegmentExporter
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T421 on the phone: the segments of a visuals plan made with Media3, on a synthetic clip (`shared/fixtures/visuals/ramp_30fps_4s.mp4`) whose brightness
 * rises 1.2 a frame and 0.3 a pixel to the right, so a picture says which frame it is and a mix of two frames is a brightness in between.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VisualsOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val dir = File(ctx.cacheDir, "visuals").apply { mkdirs() }
    private val clip = File(dir, "ramp.mp4").also { f -> inst.context.assets.open("fixtures/visuals/ramp_30fps_4s.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
    private val exporter = SegmentExporter(ctx, clip)
    private val W = 320; private val H = 180 // above the smallest size the phone's encoder takes (it made 160x90 into 228x128)

    private fun entry(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun export(name: String, e: JsonObject): File = File(dir, "$name.mp4").also { runBlocking { exporter.export(e, W, H, it) } }
    private fun frameCount(f: File): Int = ClipProbe.readable(f.path)!!.videoSamplesUs.size
    private fun luma(b: Bitmap?, x: Int = (b?.width ?: 0) / 2, y: Int = (b?.height ?: 0) / 2): Int { val p = b!!.getPixel(x, y); return (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / 3 }

    @Test
    fun aCutHasTheFramesOfThePlanAndTheSizeOfTheStoryboard() {
        val f = export("cut", entry("""{"id":"s2","method":"native","frames":30,"hold_s":0.0,"first_frame":30,"end_frame":60,"speed":1.0,"crop":null}"""))
        assertEquals(30, frameCount(f))
        ClipFrames(f.path).use { c ->
            val first = c.frameAt(0)!!
            val p = ClipProbe.readable(f.path)!!; assertEquals("coded ${p.codedWidth}x${p.codedHeight} rotation ${p.rotation}, bitmap ${first.width}x${first.height}", W, first.width); assertEquals(H, first.height)
            // the first picture is frame 30 of the source: brightness 20 + 1.5 * 30 = 65 (before the conversion to RGB)
            ClipFrames(clip.path).use { src -> assertEquals(luma(src.frameAt(30)).toDouble(), luma(first).toDouble(), 6.0) }
            assertTrue("the picture rises with the frames", luma(c.frameAt(20)) > luma(c.frameAt(2)) + 20)
        }
    }

    @Test
    fun aSlowedSegmentHasThirtyFramesASecondWithTheInBetweenFramesMixedFromTheirNeighbours() {
        // 15 source frames (30 to 45) at 0.25 are 60 frames; the PC's `framerate` mixes the four frames of each gap
        val f = export("slow", entry("""{"id":"s4","method":"blend","frames":60,"hold_s":0.0,"first_frame":30,"end_frame":45,"speed":0.25,"crop":null}"""))
        val times = ClipProbe.readable(f.path)!!.videoSamplesUs
        assertEquals("sample times ${times.take(10).map { it / 1000 }} ... ${times.takeLast(6).map { it / 1000 }}", 60, times.size)
        assertTrue("a picture every 33 ms: ${times.zipWithNext { a, b -> (b - a) / 1000 }}", times.zipWithNext().all { (a, b) -> (b - a) / 1000 in 32L..34L })
        ClipFrames(f.path).use { c ->
            ClipFrames(clip.path).use { src ->
                val l = (0 until 56).map { luma(c.frameAt(it)) }
                for (m in 0 until 13) { // each source frame m shows up as output frame 4m, and the frames after it are mixes towards frame m + 1
                    assertEquals("source frame ${30 + m} at output ${4 * m}", luma(src.frameAt(30 + m)).toDouble(), l[4 * m].toDouble(), 7.0)
                    val middle = 4 * m + 2
                    assertEquals("the middle frame $middle is the mean of its neighbours", (l[middle - 1] + l[middle + 1]) / 2.0, l[middle].toDouble(), 4.0)
                }
                assertTrue("the brightness rises 0.3 a frame (the encoder adds a little noise): $l", (1 until 56).all { l[it] >= l[it - 1] - 3 } && l[55] - l[0] in 14..30)
            }
        }
    }

    @Test
    fun theMixedFramesShowTwoFainterBarsWhereARepeatWouldShowOne() {
        val bars = File(dir, "bars.mp4").also { f -> inst.context.assets.open("fixtures/visuals/bars_30fps_4s.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
        // source frames 2 to 11 (a bar 8 pixels wide at x = 8 * frame) at 0.25: 40 pictures
        val f = File(dir, "bars_slow.mp4").also { runBlocking { SegmentExporter(ctx, bars).export(entry("""{"id":"s4","method":"blend","frames":40,"hold_s":0.0,"first_frame":2,"end_frame":12,"speed":0.25,"crop":null}"""), W, H, it) } }
        val times = ClipProbe.readable(f.path)!!.videoSamplesUs
        assertTrue("a picture every 33 ms", times.zipWithNext().all { (a, b) -> (b - a) / 1000 in 32L..34L })
        ClipFrames(f.path).use { c ->
            fun at(frame: Int, sourceX: Int) = luma(c.frameAt(frame), 2 * sourceX, H / 2) // the output is the source scaled to twice the size
            for (m in 0 until 9) {
                val a = 8 * (2 + m) + 4; val b = 8 * (2 + m + 1) + 4 // the middle of the bar in source frame m and in m + 1
                // weight 0.25, 0.5, 0.75 of the later frame: the earlier bar fades from 195 to 76 and the later one comes up from 76 to 195 (16 dark, 255 bright once converted to RGB)
                for ((j, w) in listOf(1 to 0.25, 2 to 0.5, 3 to 0.75)) {
                    assertEquals("output ${4 * m + j}: the earlier bar at weight $w", 255 - 239 * w, at(4 * m + j, a).toDouble(), 28.0)
                    assertEquals("output ${4 * m + j}: the later bar at weight $w", 16 + 239 * w, at(4 * m + j, b).toDouble(), 28.0)
                }
            }
            // the last source frame is held for the rest of its gap: the last four pictures are the same
            val tail = (36 until 40).map { at(it, 8 * 11 + 4) }
            assertTrue("held: $tail", tail.max() - tail.min() <= 6)
        }
    }

    @Test
    fun aCutStartsAtItsFirstFrameAndEndsBeforeItsEndFrame() {
        val bars = File(dir, "bars_cut.mp4").also { f -> inst.context.assets.open("fixtures/visuals/bars_30fps_4s.mp4").use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        // frames 2 to 11 (10 frames): picture j is source frame 2 + j, whose bar is at source x = 8 * (2 + j) .. +7; the output is the source at twice the size
        val f = File(dir, "bars_cut_out.mp4").also { runBlocking { SegmentExporter(ctx, bars).export(entry("""{"id":"s2","method":"native","frames":10,"hold_s":0.0,"first_frame":2,"end_frame":12,"speed":1.0,"crop":null}"""), W, H, it) } }
        assertEquals(10, frameCount(f))
        ClipFrames(f.path).use { c ->
            for (j in 0 until 10) {
                val own = luma(c.frameAt(j), 2 * (8 * (2 + j) + 4), H / 2)
                val other = luma(c.frameAt(j), 2 * (8 * (2 + j + 1) + 4), H / 2) // where the next frame's bar would be
                assertTrue("picture $j is source frame ${2 + j}: its bar is $own, the next one's place $other", own > 200 && other < 40)
            }
        }
    }

    @Test
    fun aCutFromAClipAtAnotherFrameRateIsStillThirtyFramesASecond() {
        fun intake(name: String): File = File(dir, name).also { f -> inst.context.assets.open("fixtures/intake/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
        // one second of a 25 fps clip and one of a 120 fps clip, each 30 pictures
        for (name in listOf("ok_25fps_with_sound.mp4", "high_rate_120fps.mp4")) {
            val f = File(dir, "rate_$name").also { runBlocking { SegmentExporter(ctx, intake(name)).export(entry("""{"id":"s2","method":"native","frames":30,"hold_s":0.0,"first_frame":30,"end_frame":60,"speed":1.0,"crop":null}"""), W, H, it) } }
            val times = ClipProbe.readable(f.path)!!.videoSamplesUs
            assertEquals("$name: ${times.take(5).map { it / 1000 }}", 30, times.size)
            assertTrue("$name: a picture every 33 ms", times.zipWithNext().all { (a, b) -> (b - a) / 1000 in 32L..34L })
        }
    }

    @Test
    fun aStillHoldsOneFrameForTheFramesOfThePlan() {
        val f = export("still", entry("""{"id":"s1","method":null,"frames":24,"hold_s":0.0,"freeze_frame":50}"""))
        assertEquals(24, frameCount(f))
        ClipFrames(f.path).use { c ->
            ClipFrames(clip.path).use { src -> assertEquals(luma(src.frameAt(50)).toDouble(), luma(c.frameAt(10)).toDouble(), 6.0) }
            assertEquals(luma(c.frameAt(0)).toDouble(), luma(c.frameAt(22)).toDouble(), 3.0)
        }
    }

    @Test
    fun aReplayThatEndsShortHoldsItsLastFrameForTheRestOfItsLength() {
        // 10 source frames at 0.25 are 40 frames; the plan wants 55: the last frame is held for 15
        val f = export("hold", entry("""{"id":"s4","method":"blend","frames":55,"hold_s":0.5,"first_frame":30,"end_frame":40,"speed":0.25,"crop":null}"""))
        assertEquals(55, frameCount(f))
        ClipFrames(f.path).use { c ->
            val a = luma(c.frameAt(45)); val b = luma(c.frameAt(54))
            assertEquals("held", a.toDouble(), b.toDouble(), 3.0)
            ClipFrames(clip.path).use { src -> assertEquals(luma(src.frameAt(39)).toDouble(), b.toDouble(), 8.0) }
        }
    }

    @Test
    fun aZoomCropsTowardsTheFocusBoxAndKeepsTheSize() {
        // a crop of the right part of the picture, in the storyboard's 320 by 180 (x from 64, 256 wide, 144 high): its left edge is the source's pixel 32 and its right edge the source's last pixels
        val f = export("zoom", entry("""{"id":"s4","method":"native","frames":15,"hold_s":0.0,"first_frame":30,"end_frame":45,"speed":1.0,"crop":{"scale":1.25,"w":256,"h":144,"x":64,"y":0}}"""))
        assertEquals(15, frameCount(f))
        ClipFrames(f.path).use { z ->
            val out = z.frameAt(0)!!
            assertEquals(W, out.width); assertEquals(H, out.height)
            ClipFrames(clip.path).use { src ->
                val s0 = src.frameAt(30)!!
                assertEquals("the left edge is the source's pixel 32", luma(s0, 32, 36).toDouble(), luma(out, 2, H / 2).toDouble(), 6.0)
                assertEquals("the right edge is the source's last pixels", luma(s0, 158, 36).toDouble(), luma(out, W - 3, H / 2).toDouble(), 6.0)
            }
            // with no crop the left edge would be the source's pixel 0, about 9 darker
            assertTrue(luma(out, 2, H / 2) > luma(src0(), 2, 36) + 4)
        }
    }

    private fun src0(): Bitmap = ClipFrames(clip.path).use { it.frameAt(30)!! }
}
