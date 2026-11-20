package app.epicreplay.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import app.epicreplay.core.sheet.ContactSheet
import app.epicreplay.core.spotter.RenderedSheet
import app.epicreplay.core.spotter.SheetRenderer
import java.io.ByteArrayOutputStream

/**
 * Draws a contact sheet (plan 4.3) from a clip: the frames at the given numbers in a 3-column grid, each cell [ContactSheet.CELL_W] wide with its
 * number large in the top-left corner, as `media/contact_sheet.py` does. A frame that cannot be decoded is left out and the cells are numbered
 * again, as the PC's `write_sheet` does. [fps] gives each cell's time. The numbers are drawn with Android's default font, not OpenCV's Hershey one,
 * so the sheet is the same in layout and not in pixels (the model reads the cell number, not the typeface).
 */
class ClipSheetRenderer(private val path: String, private val fps: Double) : SheetRenderer, AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(path) }

    override fun render(frames: List<Int>): RenderedSheet {
        val got = frames.mapNotNull { f -> retriever.getFrameAtIndex(f)?.let { f to it } }
        val cellW = ContactSheet.CELL_W
        require(got.isNotEmpty()) { "none of the frames $frames could be read from $path" }
        val first = got.first().second
        val geo = ContactSheet.layout(first.width, first.height, got.size)
        val sheet = Bitmap.createBitmap(geo.width, geo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (32 * geo.labelScale).toFloat() }
        val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
        for ((k, pair) in got.withIndex()) {
            val col = k % ContactSheet.COLS; val row = k / ContactSheet.COLS
            val x = col * geo.cellW; val y = row * geo.cellH
            val cell = shrink(pair.second, cellW, geo.cellH)
            canvas.drawBitmap(cell, null, Rect(x, y, x + geo.cellW, y + geo.cellH), Paint(Paint.FILTER_BITMAP_FLAG))
            if (cell !== pair.second) cell.recycle()
            pair.second.recycle()
            val label = (k + 1).toString()
            val ox = (x + geo.labelX).toFloat(); val oy = (y + geo.labelY).toFloat()
            text.color = Color.BLACK; text.style = Paint.Style.STROKE; text.strokeWidth = (geo.labelThickness + 5).toFloat()
            canvas.drawText(label, ox, oy, text)
            text.color = Color.WHITE; text.style = Paint.Style.FILL
            canvas.drawText(label, ox, oy, text)
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + geo.cellW - 1).toFloat(), (y + geo.cellH - 1).toFloat(), border)
        }
        val out = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 85, out)
        sheet.recycle()
        return RenderedSheet(out.toByteArray(), got.mapIndexed { k, (f, _) -> RenderedSheet.Cell(k + 1, f, Math.round(f / fps * 1000) / 1000.0) })
    }

    /** Halve the picture while it is more than twice the target, then filter the last step: close to OpenCV's INTER_AREA (a plain filtered draw of a 1080p frame into 448 px skips pixels). */
    private fun shrink(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width >= 2 * w && cur.height >= 2 * h) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }

    override fun close() = retriever.release()
}
