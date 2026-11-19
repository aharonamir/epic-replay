package app.epicreplay.core.sheet

/**
 * The contact sheets of the Spotter (plan 4.3): which frames a sheet shows, how the next one narrows, and the geometry
 * of the grid. A port of `media/contact_sheet.py` (conformance area `cells`); the pixels are drawn in `media/`.
 * Python's `round` is half to even and so is [Math.rint]; the frame numbers are computed in doubles as numpy does.
 */
object ContactSheet {
    const val CELLS = 9
    const val COLS = 3
    const val CELL_W = 448

    private fun round(x: Double): Int = Math.rint(x).toInt()

    /** Nine frame numbers spread evenly over [startS] to [endS], distinct, inside the clip. */
    fun sheetFrames(startS: Double, endS: Double, fps: Double, nFrames: Int): List<Int> {
        var first = maxOf(0, round(startS * fps))
        val last = minOf(nFrames - 1, round(endS * fps))
        if (last - first + 1 <= CELLS) { // a window shorter than nine frames: nine consecutive ones
            first = maxOf(0, minOf(first, nFrames - CELLS))
            return (first until minOf(nFrames, first + CELLS)).toList()
        }
        val step = (last - first).toDouble() / (CELLS - 1)
        // numpy.linspace: start + i * step, and the last value is exactly the stop
        return List(CELLS) { i -> if (i == CELLS - 1) last else round(i * step + first) }
    }

    /** The next, narrower sheet: from the cell before the chosen [cell] (1 to 9) to the cell after it. */
    fun refine(frames: List<Int>, cell: Int, fps: Double, nFrames: Int): List<Int> {
        val i = minOf(maxOf(cell, 1), frames.size) - 1
        val lo = frames[maxOf(0, i - 1)]
        val hi = frames[minOf(frames.size - 1, i + 1)]
        return sheetFrames(lo / fps, hi / fps, fps, nFrames)
    }

    /** The frame numbers of the first sheet and of one more sheet for each picked cell. */
    fun narrow(startS: Double, endS: Double, fps: Double, nFrames: Int, picks: List<Int>): List<List<Int>> {
        val sheets = mutableListOf(sheetFrames(startS, endS, fps, nFrames))
        for (cell in picks) sheets += refine(sheets.last(), cell, fps, nFrames)
        return sheets
    }

    /** Where everything goes on a sheet of [count] frames of [width] by [height] (`layout` in Python). */
    class Layout(
        val cellW: Int, val cellH: Int, val rows: Int, val width: Int, val height: Int,
        val labelX: Int, val labelY: Int, val labelScale: Double, val labelThickness: Int,
    )

    fun layout(width: Int, height: Int, count: Int): Layout {
        val cellH = round(height * CELL_W.toDouble() / width)
        val rows = (count + COLS - 1) / COLS
        val scale = CELL_W / 300.0
        return Layout(CELL_W, cellH, rows, COLS * CELL_W, rows * cellH, 10, (14 + 30 * scale).toInt(), scale, maxOf(2, (scale * 3).toInt()))
    }
}
