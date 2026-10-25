package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangeTest {
    private val arranger = Arranger(width = 2000f, height = 1600f, snap = 30f, breakThrough = 150f, minWidth = 240f)
    private val left = Box16(0f, 0f, 800f)   // 800 x 450

    @Test
    fun aTileDraggedIntoANeighbourStopsAgainstIt() {
        val moved = arranger.move(Box16(760f, 100f, 400f), listOf(left))
        assertEquals(800f, moved.x, 0.01f)
        assertFalse(moved.overlaps(left))
    }

    @Test
    fun pushingHardGoesThrough() {
        val moved = arranger.move(Box16(600f, 100f, 400f), listOf(left))
        assertEquals(600f, moved.x, 0.01f)
        assertTrue(moved.overlaps(left))
    }

    @Test
    fun edgesSnapToNeighboursAndTheScreen() {
        val moved = arranger.move(Box16(820f, 12f, 400f), listOf(left))
        assertEquals(800f, moved.x, 0.01f)
        assertEquals(0f, moved.y, 0.01f)
    }

    @Test
    fun growingPushesTheNeighbourIntoOpenSpace() {
        val right = Box16(800f, 0f, 600f)
        val (grown, pushed) = arranger.resize(left, 1000f, listOf(right))
        assertEquals(1000f, grown.w, 0.01f)
        assertEquals(1000f, pushed[0].x, 0.01f)
        assertEquals(600f, pushed[0].w, 0.01f)
    }

    @Test
    fun withNoRoomLeftTheNeighbourShrinks() {
        val right = Box16(800f, 0f, 1000f)
        val (_, pushed) = arranger.resize(left, 1200f, listOf(right))
        assertEquals(1200f, pushed[0].x, 0.01f)
        assertEquals(800f, pushed[0].w, 0.01f)
        assertTrue(pushed[0].r <= 2000.01f)
    }

    @Test
    fun savedLayoutsRoundTrip() {
        val saved = SavedLayout.of(listOf(Slot(100f, 200f, 800f)), 2000f, 1600f)
        val back = SavedLayout.fromJson(saved.toJson())!!
        assertTrue(back.fits(2000f, 1600f))
        assertFalse(back.fits(900f, 2000f))
        assertEquals(800f, back.slots(2000f, 1600f)[0].w, 0.01f)
    }
}
