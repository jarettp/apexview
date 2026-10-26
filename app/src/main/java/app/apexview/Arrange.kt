package app.apexview

import kotlin.math.abs

/** A tile's rectangle while arranging, in canvas pixels. Tiles keep 16:9, so h follows w. */
data class Box16(val x: Float, val y: Float, val w: Float) {
    val h get() = w * 9f / 16f
    val r get() = x + w
    val b get() = y + h

    fun overlaps(o: Box16) = x < o.r - 1 && o.x < r - 1 && y < o.b - 1 && o.y < b - 1
}

/**
 * Snap mode's rules for moving and resizing tiles: edges are magnetic (to the screen and to the
 * other tiles), tiles don't overlap unless pushed hard, and a growing tile shoves its neighbours
 * toward open space, shrinking them when there's no space left.
 *
 * Every call works from where things were when the gesture began plus how far the finger has
 * gone in total, so a push undoes itself as the finger comes back.
 */
class Arranger(
    private val width: Float,
    private val height: Float,
    /** How close an edge must come to another to snap to it. */
    private val snap: Float,
    /** How far a tile must be pushed into a neighbour or off the screen before it goes through. */
    private val breakThrough: Float,
    private val minWidth: Float,
) {
    /** Where a moved tile ends up, given where the finger has taken it ([raw]). */
    fun move(raw: Box16, others: List<Box16>): Box16 {
        var p = raw
        // Walls first: a neighbour or the screen edge stops the tile until pushed past.
        for (o in others) p = blockedBy(p, o)
        p = keptOnScreen(p)
        return snapPosition(p, others)
    }

    /**
     * A resize anchored at the top-left to width [rawW]. Returns the tile's new box and the
     * neighbours' boxes, pushed (and if need be shrunk) out of its way.
     */
    fun resize(tile: Box16, rawW: Float, others: List<Box16>): Pair<Box16, List<Box16>> {
        var w = rawW.coerceIn(minWidth, width)
        w = snapSize(tile.copy(w = w), others).w
        val grown = tile.copy(w = w)
        val pushed = others.map { o -> if (grown.overlaps(o)) pushAway(o, grown) else o }
        return grown to pushed
    }

    private fun blockedBy(p: Box16, o: Box16): Box16 {
        if (!p.overlaps(o)) return p
        // The shallowest way out, and how deep the tile has gone.
        val outs = listOf(
            o.x - p.r to 'L',   // push back left: negative
            o.r - p.x to 'R',   // push back right: positive
            o.y - p.b to 'U',
            o.b - p.y to 'D',
        )
        val (shift, dir) = outs.minBy { abs(it.first) }
        if (abs(shift) >= breakThrough) return p
        return when (dir) {
            'L', 'R' -> p.copy(x = p.x + shift)
            else -> p.copy(y = p.y + shift)
        }
    }

    private fun keptOnScreen(p: Box16): Box16 {
        fun clamp(v: Float, lo: Float, hi: Float) = when {
            v < lo && lo - v < breakThrough -> lo
            v > hi && v - hi < breakThrough -> hi
            else -> v
        }
        return p.copy(
            x = clamp(p.x, 0f, (width - p.w).coerceAtLeast(0f)),
            y = clamp(p.y, 0f, (height - p.h).coerceAtLeast(0f)),
        )
    }

    /** Lines up the tile's edges with the screen's and the other tiles' edges, when close. */
    private fun snapPosition(p: Box16, others: List<Box16>): Box16 {
        val xs = listOf(0f, width) + others.flatMap { listOf(it.x, it.r) }
        val ys = listOf(0f, height) + others.flatMap { listOf(it.y, it.b) }
        val dx = nearest(listOf(p.x, p.r), xs)
        val dy = nearest(listOf(p.y, p.b), ys)
        val snapped = p.copy(x = p.x + (dx ?: 0f), y = p.y + (dy ?: 0f))
        // Snapping must not pull the tile into a neighbour.
        return if (others.any { snapped.overlaps(it) } && others.none { p.overlaps(it) }) p else snapped
    }

    /** The right or bottom edge snaps; the width follows from whichever needed the smaller change. */
    private fun snapSize(p: Box16, others: List<Box16>): Box16 {
        val xs = listOf(width) + others.flatMap { listOf(it.x, it.r) }
        val ys = listOf(height) + others.flatMap { listOf(it.y, it.b) }
        val byRight = nearest(listOf(p.r), xs)?.let { p.w + it }
        val byBottom = nearest(listOf(p.b), ys)?.let { (p.h + it) * 16f / 9f }
        val w = listOfNotNull(byRight, byBottom).minByOrNull { abs(it - p.w) } ?: p.w
        return p.copy(w = w.coerceIn(minWidth, width))
    }

    private fun nearest(edges: List<Float>, targets: List<Float>): Float? {
        var best: Float? = null
        for (e in edges) for (t in targets) {
            val d = t - e
            if (abs(d) <= snap && (best == null || abs(d) < abs(best))) best = d
        }
        return best
    }

    /**
     * Moves [o] out from under [by], along whichever axis is the shorter trip, toward the side with
     * more open screen. If the screen edge gets in the way, [o] shrinks to fit.
     */
    private fun pushAway(o: Box16, by: Box16): Box16 {
        val toRight = by.r - o.x
        val toBottom = by.b - o.y
        val horizontal = toRight <= toBottom
        return if (horizontal) {
            val x = by.r
            val room = width - x
            if (room >= o.w) o.copy(x = x)
            else o.copy(x = x, w = room.coerceAtLeast(minWidth)).let { if (it.r > width) it.copy(x = width - it.w) else it }
        } else {
            val y = by.b
            val room = height - y
            if (room >= o.h) o.copy(y = y)
            else {
                val w = (room * 16f / 9f).coerceAtLeast(minWidth)
                o.copy(y = y, w = w).let { if (it.b > height) it.copy(y = height - it.h) else it }
            }
        }
    }
}

/**
 * A saved arrangement: each tile's box as fractions of the canvas, in tile order (the big or
 * audible feed first), and the canvas's shape, so a layout made unfolded isn't forced onto the
 * cover screen.
 */
class SavedLayout(val aspect: Float, val boxes: List<FloatArray>) {
    /**
     * Made for a screen this shape: the same way up, and within 10% of its proportions. The
     * inner screen is nearly square, so upright and sideways differ by under 10%; the way up
     * tells them apart.
     */
    fun fits(width: Float, height: Float) =
        (width > height) == (aspect > 1f) && abs(width / height - aspect) < aspect * 0.1f

    fun slots(width: Float, height: Float) = boxes.map { Slot(it[0] * width, it[1] * height, it[2] * width) }

    fun toJson(): String = org.json.JSONObject()
        .put("aspect", aspect.toDouble())
        .put("boxes", org.json.JSONArray(boxes.map { b -> org.json.JSONArray(b.map { it.toDouble() }) }))
        .toString()

    companion object {
        fun of(slots: List<Slot>, width: Float, height: Float) =
            SavedLayout(width / height, slots.map { floatArrayOf(it.x / width, it.y / height, it.w / width) })

        fun fromJson(text: String?): SavedLayout? = runCatching {
            val j = org.json.JSONObject(text ?: return null)
            val list = j.getJSONArray("boxes")
            SavedLayout(
                j.getDouble("aspect").toFloat(),
                (0 until list.length()).map { i ->
                    val b = list.getJSONArray(i)
                    floatArrayOf(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat())
                },
            )
        }.getOrNull()
    }
}
