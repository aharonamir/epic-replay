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
        ClipFrames(f.path).use { c ->
            ClipFrames(clip.path).use { src ->
                val l = (0 until 56).map { luma(c.frameAt(it)) }
                for (m in 0 until 13) { // each source frame m shows up as output frame 4m, and the frames after it are mixes towards frame m + 1
                    assertEquals("source frame ${30 + m} at output ${4 * m}", luma(src.frameAt(30 + m)).toDouble(), l[4 * m].toDouble(), 7.0)
                    val middle = 4 * m + 2
                    assertEquals("the middle frame $middle is the mean of its neighbours", (l[middle - 1] + l[middle + 1]) / 2.0, l[middle].toDouble(), 4.0)
                    assertEquals("the middle frame $middle is half way between the source frames", (l[4 * m] + l[4 * m + 4]) / 2.0, l[middle].toDouble(), 5.0)
                }
                assertTrue("the brightness rises 0.3 a frame (the encoder adds a little noise): $l", (1 until 56).all { l[it] >= l[it - 1] - 3 } && l[55] - l[0] in 14..30)
            }
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
