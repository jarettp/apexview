package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuilt map placement rules: a panel can never be dragged, zoomed
 * or restored to a spot where no part of it is on screen, and a panel
 * that is fully off-screen is recognised as lost, so the app can put it
 * back to its default spot instead of leaving it hidden.
 */
class MapPanelTest {
    private fun panel(x: Float, y: Float, w: Float, h: Float) = MapPanel().apply {
        place(Area(x, y, w, h))
        placed = true
    }

    @Test
    fun dragOffTheRightEdgeIsClampedBackOnScreen() {
        val p = panel(1800f, 100f, 800f, 800f)
        p.clampTo(2000f, 1800f)
        assertEquals(1200f, p.x, 0.001f)
        assertEquals(100f, p.y, 0.001f)
    }

    @Test
    fun dragOffTheBottomEdgeIsClampedBackOnScreen() {
        val p = panel(100f, 1700f, 800f, 800f)
        p.clampTo(2000f, 1800f)
        assertEquals(1000f, p.y, 0.001f)
    }

    @Test
    fun negativePositionIsClampedToZero() {
        val p = panel(-500f, -200f, 800f, 800f)
        p.clampTo(2000f, 1800f)
        assertEquals(0f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
    }

    @Test
    fun oversizedPanelIsShrunkToTheCanvas() {
        val p = panel(0f, 0f, 5000f, 5000f)
        p.clampTo(2000f, 1800f)
        assertEquals(2000f, p.w, 0.001f)
        assertEquals(1800f, p.h, 0.001f)
        assertEquals(0f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
    }

    @Test
    fun partiallyOffRightPanelIsPulledBackOnScreen() {
        // The Fold 8 case: a panel saved on a wider ratio, only a sliver
        // showing at the right edge, must come back whole, not stay
        // un-grabbable. Clamping is what placeMapIfNeeded now does for
        // an already-placed panel on every mode change and layout.
        val p = panel(1800f, 100f, 1200f, 800f)
        p.clampTo(2000f, 1800f)
        assertEquals(800f, p.x, 0.001f)
        assertEquals(2000f, p.x + p.w, 0.001f)
    }

    @Test
    fun fullyOffScreenPanelIsRecognisedAsLost() {
        assertTrue(panel(2000f, 0f, 800f, 800f).isOffScreen(2000f, 1800f))
        assertTrue(panel(0f, 1800f, 800f, 800f).isOffScreen(2000f, 1800f))
        assertTrue(panel(-800f, 0f, 800f, 800f).isOffScreen(2000f, 1800f))
        assertFalse(panel(1200f, 1000f, 800f, 800f).isOffScreen(2000f, 1800f))
        assertFalse(panel(1900f, 100f, 800f, 800f).isOffScreen(2000f, 1800f))
    }

    @Test
    fun defaultSpotIsBottomRightAt42PercentOfTheSmallerSide() {
        val p = MapPanel()
        p.placeDefault(2000f, 1800f)
        assertEquals(756f, p.w, 0.001f)
        assertEquals(756f, p.h, 0.001f)
        assertEquals(1244f, p.x, 0.001f)
        assertEquals(1044f, p.y, 0.001f)
        assertTrue(p.placed)
        assertFalse(p.isOffScreen(2000f, 1800f))
    }
}
