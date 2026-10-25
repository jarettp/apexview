package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class RibbonTest {
    // A 1000 x 600 loop with 100 px corners, like a phone's rounded screen.
    private val ribbon = Ribbon(0f, 0f, 1000f, 600f, 100f)

    @Test
    fun perimeterCountsTheRoundedCorners() {
        val expected = 2 * 800f + 2 * 400f + (2 * PI * 100).toFloat()
        assertEquals(expected, ribbon.perimeter, 0.5f)
    }

    @Test
    fun startFinishIsTopCenter() {
        val p = ribbon.point(0f)
        assertEquals(500f, p.x, 0.5f)
        assertEquals(0f, p.y, 0.5f)
        assertEquals(1f, ribbon.inward(0f).y, 0.001f)
    }

    @Test
    fun everyPointSitsOnTheRoundedRectangle() {
        for (i in 0 until 1000) {
            val f = i / 1000f
            val p = ribbon.point(f)
            // Distance outside the rounded rect's inner core must equal the corner radius at corners,
            // and points on straight edges must lie on an edge.
            val cx = p.x.coerceIn(100f, 900f)
            val cy = p.y.coerceIn(100f, 500f)
            assertEquals("f=$f at $p", 100f, hypot(p.x - cx, p.y - cy), 0.5f)
            val n = ribbon.inward(f)
            assertEquals("normal at f=$f", 1f, hypot(n.x, n.y), 0.01f)
        }
    }

    @Test
    fun counterClockwiseRibbonRunsTheOtherWay() {
        val ccw = Ribbon(0f, 0f, 1000f, 600f, 100f, clockwise = false)
        // Both start at top center; a little way in, clockwise heads right and the other heads left.
        assertEquals(500f, ccw.point(0f).x, 0.5f)
        assertTrue(ribbon.point(0.05f).x > 500f)
        assertTrue(ccw.point(0.05f).x < 500f)
    }

    @Test
    fun trackDirectionComesFromTheOutline() {
        // y points up in archive coordinates: right, up, left, down is counter-clockwise.
        assertTrue(TrackGeometry(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)).counterClockwise)
        assertTrue(!TrackGeometry(floatArrayOf(0f, 0f, 0f, 100f, 100f, 100f, 100f, 0f)).counterClockwise)
    }

    @Test
    fun movesSmoothlyClockwise() {
        var last = ribbon.point(0f)
        for (i in 1..2000) {
            val p = ribbon.point(i / 2000f)
            val step = hypot(p.x - last.x, p.y - last.y)
            assertTrue("jump of $step at ${i / 2000f}", step < ribbon.perimeter / 2000 * 1.5f)
            last = p
        }
    }
}
