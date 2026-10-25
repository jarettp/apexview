package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    // Roughly a foldable's inner screen held landscape, as in the 0.5 screenshot.
    private val w = 1890f
    private val h = 1960f

    @Test
    fun fourFeedsFitOnScreenWithoutOverlapping() {
        for (mode in LayoutMode.entries) {
            val slots = Layouts.place(mode, 4, w, h)
            assertEquals(4, slots.size)
            slots.forEach { s ->
                assertTrue("$mode $s off screen", s.x >= -0.5f && s.y >= -0.5f && s.x + s.w <= w + 0.5f && s.y + s.h <= h + 0.5f)
            }
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val a = slots[i]
                val b = slots[j]
                val overlap = a.x < b.x + b.w - 1 && b.x < a.x + a.w - 1 && a.y < b.y + b.h - 1 && b.y < a.y + a.h - 1
                assertTrue("$mode slots $i and $j overlap", !overlap)
            }
        }
    }

    @Test
    fun mapGetsRealSpaceNextToFourFeeds() {
        for (mode in LayoutMode.entries) for (size in listOf(w to h, 2208f to 1840f, 1840f to 2208f)) {
            val (sw, sh) = size
            val arrangement = Layouts.withMap(mode, 4, sw, sh)
            val map = arrangement.map!!
            assertTrue("$mode $size map too small: $map", map.w * map.h > sw * sh * 0.15f)
            assertTrue("$mode $size map overlaps a tile", arrangement.slots.none { map.overlaps(it) })
            assertEquals(4, arrangement.slots.size)
        }
    }
}
