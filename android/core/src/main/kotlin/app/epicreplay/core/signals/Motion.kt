package app.epicreplay.core.signals

/** A grey picture, one byte a pixel (0..255). */
class Gray(val width: Int, val height: Int, val data: ByteArray) {
    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

/** Per frame: flow left after the camera fit, pixel change left after it, how far the camera moved, and the share of the picture that still changed. Entry i is the change from frame i-1 to i; entry 0 is 0. */
class Curves(val flow: DoubleArray, val change: DoubleArray, val camera: DoubleArray, val share: DoubleArray)

/**
 * The motion half of the scan. The Python scan uses OpenCV's DIS flow and a RANSAC fit; this one finds the flow by matching
 * 8x8 blocks (a coarse search on half-size pictures, then one pixel finer) and fits the camera as a similarity transform
 * from the vectors that agree. The numbers differ from OpenCV's by small amounts; what the conformance cases compare is
 * when things happen (spec 004 plan, risks: "the signal scan differs between OpenCV builds").
 */
object Motion {
    private const val STEP = 8
    private const val BLOCK = 8
    private const val COARSE_RANGE = 5

    /**
     * The four curves of a clip. The change from frame i-1 to i depends on those two frames alone, so the frames are taken in batches and the pairs of a batch are worked out on [threads]
     * threads at once; the numbers are the same as one at a time, and arrive in order (a phone has eight cores, the scan was using one).
     */
    fun curves(frames: Sequence<Gray>, threads: Int = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()))): Curves {
        val flow = ArrayList<Double>(); val change = ArrayList<Double>(); val camera = ArrayList<Double>(); val share = ArrayList<Double>()
        val pool = if (threads > 1) java.util.concurrent.Executors.newFixedThreadPool(threads) else null
        try {
            val batchSize = threads * 3
            var carry: Gray? = null; var carryHalf: Gray? = null
            val batch = ArrayList<Gray>()
            fun flush() {
                if (batch.isEmpty()) return
                val cur = batch.toList(); batch.clear()
                val halves: List<Gray> = run(pool, cur.map { g -> { halve(g) } })
                val first = if (carry == null) 1 else 0 // the clip's first frame has no change to measure
                if (carry == null) { flow.add(0.0); change.add(0.0); camera.add(0.0); share.add(0.0) }
                val jobs = (first until cur.size).map { i ->
                    val p = if (i == 0) carry!! else cur[i - 1]; val ph = if (i == 0) carryHalf!! else halves[i - 1]
                    { step(p, ph, cur[i], halves[i]) }
                }
                for (r in run(pool, jobs)) { flow.add(r[0]); change.add(r[1]); camera.add(r[2]); share.add(r[3]) }
                carry = cur.last(); carryHalf = halves.last()
            }
            for (f in frames) { batch.add(f); if (batch.size >= batchSize) flush() }
            flush()
        } finally { pool?.shutdown() }
        return Curves(flow.toDoubleArray(), change.toDoubleArray(), camera.toDoubleArray(), share.toDoubleArray())
    }

    private fun <T> run(pool: java.util.concurrent.ExecutorService?, jobs: List<() -> T>): List<T> =
        if (pool == null) jobs.map { it() } else pool.invokeAll(jobs.map { j -> java.util.concurrent.Callable { j() } }).map { it.get() }

    private fun halve(g: Gray): Gray {
        val w = g.width / 2; val h = g.height / 2
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w)
            out[y * w + x] = ((g[2 * x, 2 * y] + g[2 * x + 1, 2 * y] + g[2 * x, 2 * y + 1] + g[2 * x + 1, 2 * y + 1] + 2) / 4).toByte()
        return Gray(w, h, out)
    }

    private fun sad(a: Gray, ax: Int, ay: Int, b: Gray, bx: Int, by: Int, size: Int, best: Int): Int {
        var s = 0
        for (j in 0 until size) {
            val ra = (ay + j) * a.width + ax; val rb = (by + j) * b.width + bx
            for (i in 0 until size) s += Math.abs((a.data[ra + i].toInt() and 0xFF) - (b.data[rb + i].toInt() and 0xFF))
            if (s >= best) return s
        }
        return s
    }

    /** The displacement (dx, dy) of the block whose top left is (x, y) in `a`, searched in `b` within `range` of (cx, cy). Ties go to the smaller move, then the smaller dy, then dx. */
    private fun match(a: Gray, x: Int, y: Int, b: Gray, size: Int, cx: Int, cy: Int, range: Int): IntArray {
        var bestS = Int.MAX_VALUE; var bx = cx; var by = cy
        for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
            val tx = x + dx; val ty = y + dy
            if (tx < 0 || ty < 0 || tx + size > b.width || ty + size > b.height) continue
            val s = sad(a, x, y, b, tx, ty, size, bestS)
            val better = s < bestS || (s == bestS && (dx * dx + dy * dy < bx * bx + by * by ||
                (dx * dx + dy * dy == bx * bx + by * by && (dy < by || (dy == by && dx < bx)))))
            if (better) { bestS = s; bx = dx; by = dy }
        }
        return intArrayOf(bx, by)
    }

    private fun step(prev: Gray, prevHalf: Gray, cur: Gray, half: Gray): DoubleArray {
        val w = cur.width; val h = cur.height
        val xs = (STEP / 2 until w step STEP).toList(); val ys = (STEP / 2 until h step STEP).toList()
        val n = xs.size * ys.size
        val px = DoubleArray(n); val py = DoubleArray(n); val dx = DoubleArray(n); val dy = DoubleArray(n)
        var k = 0
        for (gy in ys) for (gx in xs) {
            // coarse: a 4x4 block at half size, centred on the point
            val cxh = gx / 2 - 2; val cyh = gy / 2 - 2
            val ok = cxh >= 0 && cyh >= 0 && cxh + 4 <= half.width && cyh + 4 <= half.height
            val c = if (ok) match(prevHalf, cxh, cyh, half, 4, 0, 0, COARSE_RANGE) else intArrayOf(0, 0)
            val x0 = gx - BLOCK / 2; val y0 = gy - BLOCK / 2
            val f = if (x0 >= 0 && y0 >= 0 && x0 + BLOCK <= w && y0 + BLOCK <= h) match(prev, x0, y0, cur, BLOCK, 2 * c[0], 2 * c[1], 1) else intArrayOf(0, 0)
            px[k] = gx.toDouble(); py[k] = gy.toDouble(); dx[k] = f[0].toDouble(); dy[k] = f[1].toDouble(); k++
        }
        val m = fitCamera(px, py, dx, dy)
        val resid = DoubleArray(n) { i ->
            val pdx = m[0] * px[i] - m[1] * py[i] + m[2] - px[i]; val pdy = m[1] * px[i] + m[0] * py[i] + m[3] - py[i]
            Math.hypot(dx[i] - pdx, dy[i] - pdy)
        }
        val kTop = Math.max(1, resid.size / 50)
        val flowE = resid.sortedDescending().take(kTop).average()
        val cam = Math.hypot(m[2], m[3])
        // the previous picture moved the way the camera moved, then compared with this one
        val warped = IntArray(w * h); val valid = BooleanArray(w * h)
        val det = m[0] * m[0] + m[1] * m[1]
        for (y in 0 until h) for (x in 0 until w) {
            // inverse of x' = a x - b y + tx, y' = b x + a y + ty
            val ux = x - m[2]; val uy = y - m[3]
            val sx = (m[0] * ux + m[1] * uy) / det; val sy = (-m[1] * ux + m[0] * uy) / det
            if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) continue
            val x0 = Math.floor(sx).toInt(); val y0 = Math.floor(sy).toInt()
            val x1 = Math.min(w - 1, x0 + 1); val y1 = Math.min(h - 1, y0 + 1)
            val fx = sx - x0; val fy = sy - y0
            val v = (prev[x0, y0] * (1 - fx) + prev[x1, y0] * fx) * (1 - fy) + (prev[x0, y1] * (1 - fx) + prev[x1, y1] * fx) * fy
            warped[y * w + x] = Math.round(v).toInt(); valid[y * w + x] = true
        }
        // eroded by 5x5: every neighbour is valid (a row test, then a column test: the same set as testing the 25 neighbours)
        val rowOk = BooleanArray(w * h)
        for (y in 0 until h) for (x in 2 until w - 2) rowOk[y * w + x] = valid[y * w + x - 2] && valid[y * w + x - 1] && valid[y * w + x] && valid[y * w + x + 1] && valid[y * w + x + 2]
        val ok = BooleanArray(w * h)
        for (y in 2 until h - 2) for (x in 2 until w - 2) ok[y * w + x] = rowOk[(y - 2) * w + x] && rowOk[(y - 1) * w + x] && rowOk[y * w + x] && rowOk[(y + 1) * w + x] && rowOk[(y + 2) * w + x]
        val a = blur(IntArray(w * h) { cur.data[it].toInt() and 0xFF }, w, h)
        val b = blur(warped, w, h)
        // the differences are whole numbers from 0 to 255: counted, not sorted (the mean of the largest kd is the same)
        val hist = IntArray(256); var count = 0; var over = 0
        for (i in 0 until w * h) if (ok[i]) { val d = Math.abs(a[i] - b[i]); hist[d]++; count++; if (d > 6) over++ }
        if (count == 0) return doubleArrayOf(flowE, 0.0, cam, 1.0)
        val kd = Math.max(1, count / 500)
        var left = kd; var total = 0L; var v = 255
        while (left > 0 && v >= 0) { val take = Math.min(left, hist[v]); total += take.toLong() * v; left -= take; v-- }
        return doubleArrayOf(flowE, total.toDouble() / kd, cam, over.toDouble() / count)
    }

    /** `[a, b, tx, ty]` of x' = a x - b y + tx, y' = b x + a y + ty from the vectors that agree; the identity if too few do. */
    private fun fitCamera(px: DoubleArray, py: DoubleArray, dx: DoubleArray, dy: DoubleArray): DoubleArray {
        val n = px.size
        var m = doubleArrayOf(1.0, 0.0, Peaks.median(dx), Peaks.median(dy)) // start from the usual move
        var limit = 1.5
        repeat(4) {
            val inl = (0 until n).filter { i ->
                val pdx = m[0] * px[i] - m[1] * py[i] + m[2] - px[i]; val pdy = m[1] * px[i] + m[0] * py[i] + m[3] - py[i]
                Math.hypot(dx[i] - pdx, dy[i] - pdy) < limit
            }
            if (inl.size < 3) return doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            val xc = inl.sumOf { px[it] } / inl.size; val yc = inl.sumOf { py[it] } / inl.size
            val uc = inl.sumOf { px[it] + dx[it] } / inl.size; val vc = inl.sumOf { py[it] + dy[it] } / inl.size
            var num1 = 0.0; var num2 = 0.0; var den = 0.0
            for (i in inl) {
                val x = px[i] - xc; val y = py[i] - yc; val u = px[i] + dx[i] - uc; val v = py[i] + dy[i] - vc
                num1 += x * u + y * v; num2 += x * v - y * u; den += x * x + y * y
            }
            val a = if (den > 0) num1 / den else 1.0; val b = if (den > 0) num2 / den else 0.0
            m = doubleArrayOf(a, b, uc - a * xc + b * yc, vc - b * xc - a * yc)
            limit = 1.0
        }
        return m
    }

    /** A 3x3 Gaussian blur ([1 2 1] each way, the edge mirrored), rounded. */
    private fun blur(src: IntArray, w: Int, h: Int): IntArray {
        fun mirror(i: Int, n: Int) = if (i < 0) -i else if (i >= n) 2 * n - 2 - i else i
        val tmp = IntArray(w * h); val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w)
            tmp[y * w + x] = src[y * w + mirror(x - 1, w)] + 2 * src[y * w + x] + src[y * w + mirror(x + 1, w)]
        for (y in 0 until h) for (x in 0 until w)
            out[y * w + x] = (tmp[mirror(y - 1, h) * w + x] + 2 * tmp[y * w + x] + tmp[mirror(y + 1, h) * w + x] + 8) shr 4
        return out
    }
}
