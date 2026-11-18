package app.epicreplay.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.epicreplay.core.models.ImageResizer
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Scales a JPEG so its longest side is at most `maxSidePx` (JPEG quality 90); the same input always gives the same bytes. */
class BitmapImageResizer : ImageResizer {
    override fun resize(jpeg: ByteArray, maxSidePx: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("not a JPEG")
        val longest = max(src.width, src.height)
        if (longest <= maxSidePx) return jpeg
        val scale = maxSidePx.toDouble() / longest
        val small = Bitmap.createScaledBitmap(src, max(1, (src.width * scale).roundToInt()), max(1, (src.height * scale).roundToInt()), true)
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
