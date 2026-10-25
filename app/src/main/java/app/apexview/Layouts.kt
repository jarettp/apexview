package app.apexview

import kotlin.math.ceil
import kotlin.math.min

/** A 16:9 tile's place on the canvas, in pixels. */
data class Slot(val x: Float, val y: Float, val w: Float) {
    val h get() = w * 9f / 16f
}

enum class LayoutMode(val label: String) {
    FEATURED("Main + strip"),
    GRID("Grid"),
    /** The arrangement saved with Save layout; falls back to main + strip where there's none. */
    CUSTOM("My layout"),
}

/**
 * Fits n 16:9 tiles into the canvas. In [LayoutMode.FEATURED], slot 0 is the big one and the
 * rest share a strip beside or below it, whichever leaves more picture on screen.
 */
object Layouts {
    fun place(mode: LayoutMode, n: Int, width: Float, height: Float): List<Slot> = when {
        n == 0 -> emptyList()
        mode == LayoutMode.GRID || n == 1 -> grid(n, 0f, 0f, width, height, center = true)
        else -> featured(n, width, height)
    }

    private class Fit(val cols: Int, val rows: Int, val w: Float)

    /** Column count that gives the widest tiles for n tiles in a rw x rh area. */
    private fun bestFit(n: Int, rw: Float, rh: Float): Fit {
        var best = Fit(1, n, 0f)
        for (cols in 1..n) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val w = min(rw / cols, rh / rows * 16f / 9f)
            if (w > best.w) best = Fit(cols, rows, w)
        }
        return best
    }

    /** Rows of equal tiles, each row centered, the block centered in the area when [center]. */
    private fun grid(n: Int, ax: Float, ay: Float, aw: Float, ah: Float, center: Boolean): List<Slot> {
        val fit = bestFit(n, aw, ah)
        val tileH = fit.w * 9f / 16f
        val blockH = fit.rows * tileH
        val top = ay + if (center) (ah - blockH) / 2 else 0f
        return (0 until n).map { i ->
            val row = i / fit.cols
            val inRow = min(fit.cols, n - row * fit.cols)
            val left = ax + (aw - inRow * fit.w) / 2
            Slot(left + (i % fit.cols) * fit.w, top + row * tileH, fit.w)
        }
    }

    private fun featured(n: Int, width: Float, height: Float): List<Slot> {
        val others = n - 1
        val maxMain = min(width, height * 16f / 9f)
        var best: List<Slot>? = null
        var bestScore = 0f

        var f = 1f
        while (f >= 0.5f) {
            val mainW = maxMain * f
            val mainH = mainW * 9f / 16f

            // Strip below the main tile.
            val below = bestFit(others, width, height - mainH)
            if (below.w >= mainW * 0.25f) {
                val stripH = below.rows * below.w * 9f / 16f
                val top = (height - mainH - stripH) / 2
                val slots = listOf(Slot((width - mainW) / 2, top, mainW)) +
                    grid(others, 0f, top + mainH, width, stripH, center = false)
                val score = score(mainW, others, below.w)
                if (score > bestScore) { bestScore = score; best = slots }
            }

            // Strip to the right of the main tile.
            val right = bestFit(others, width - mainW, height)
            if (right.w >= mainW * 0.25f) {
                val left = (width - mainW - right.cols * right.w) / 2
                val slots = listOf(Slot(left, (height - mainH) / 2, mainW)) +
                    grid(others, left + mainW, 0f, right.cols * right.w, height, center = true)
                val score = score(mainW, others, right.w)
                if (score > bestScore) { bestScore = score; best = slots }
            }
            f -= 0.02f
        }
        return best ?: grid(n, 0f, 0f, width, height, center = true)
    }

    /** Total picture area, with the main feed counting a little more than the strip. */
    private fun score(mainW: Float, others: Int, otherW: Float) =
        mainW * mainW + others * otherW * otherW * 0.6f

    class Arrangement(val slots: List<Slot>, val map: Area?)

    /**
     * Tiles plus room for the track map. Tries the plain layout (map in whatever gap is left) and
     * layouts squeezed to leave a band at the bottom or right, and keeps the one showing the most
     * picture, counting map area the same as video area.
     */
    fun withMap(mode: LayoutMode, n: Int, width: Float, height: Float): Arrangement {
        val minSide = min(width, height) * 0.22f
        fun score(slots: List<Slot>, map: Area?) =
            slots.sumOf { (it.w * it.h).toDouble() } + (map?.let { (it.w * it.h).toDouble() } ?: 0.0)

        val candidates = mutableListOf<Arrangement>()
        candidates += place(mode, n, width, height).let { Arrangement(it, largestFree(it, width, height)) }
        for (fraction in listOf(0.25f, 0.3f, 0.35f, 0.4f)) {
            candidates += place(mode, n, width, height * (1 - fraction))
                .let { Arrangement(it, largestFree(it, width, height)) }
            candidates += place(mode, n, width * (1 - fraction), height)
                .let { Arrangement(it, largestFree(it, width, height)) }
        }
        return candidates
            .filter { it.map != null && min(it.map.w, it.map.h) >= minSide }
            .maxByOrNull { score(it.slots, it.map) }
            ?: candidates.first()
    }

    /**
     * The biggest empty rectangle left between the tiles, for the map. Candidate edges are the
     * area's edges and every tile edge; with at most four tiles that's a small search.
     */
    fun largestFree(slots: List<Slot>, width: Float, height: Float): Area? {
        val xs = (listOf(0f, width) + slots.flatMap { listOf(it.x, it.x + it.w) })
            .map { it.coerceIn(0f, width) }.distinct().sorted()
        val ys = (listOf(0f, height) + slots.flatMap { listOf(it.y, it.y + it.h) })
            .map { it.coerceIn(0f, height) }.distinct().sorted()
        var best: Area? = null
        for (i in xs.indices) for (j in i + 1 until xs.size) for (k in ys.indices) for (l in k + 1 until ys.size) {
            val area = Area(xs[i], ys[k], xs[j] - xs[i], ys[l] - ys[k])
            if (area.w * area.h <= (best?.let { it.w * it.h } ?: 0f)) continue
            if (slots.none { area.overlaps(it) }) best = area
        }
        return best
    }
}

data class Area(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun overlaps(s: Slot) = x < s.x + s.w - 1 && s.x < x + w - 1 && y < s.y + s.h - 1 && s.y < y + h - 1
}
