package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A race start on a circular track (radius 10000, the finish line at angle 0): 20 cars wait on
 * the grid for 10 minutes, do a formation lap, wait on the grid again for 30 s, then race.
 */
class GridTest {
    private val archive = LiveTimingArchive("token", "test")
    private val radius = 10_000f
    private val lap = (2 * PI * radius).toFloat()
    private val t0 = 1_000_000_000L

    // Counter-clockwise from the finish line, so the outline starts at the line.
    private val outline = FloatArray(720) { i ->
        val a = 2 * PI * (i / 2) / 360
        if (i % 2 == 0) (radius * cos(a)).toFloat() else (radius * sin(a)).toFloat()
    }
    private val geometry = TrackGeometry(outline)

    /** Grid slot k (0 = pole) sits 63 + 80k units behind the line. */
    private fun gridAngle(k: Int) = -(63.0 + 80.0 * k) / radius

    private fun race(): Map<String, DriverTrack> = (0 until 20).associate { k ->
        val b = TrackBuilder()
        var t = 0L
        while (t <= 1_000_000L) {
            val a = when {
                t < 600_000 -> gridAngle(k)                                        // on the grid
                t < 700_000 -> gridAngle(k) + 2 * PI * (t - 600_000) / 100_000.0   // formation lap
                t < 730_000 -> gridAngle(k)                                        // back on the grid
                else -> gridAngle(k) + 2 * PI * (t - 730_000) / 90_000.0           // racing
            }
            b.add(t0 + t, (radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
            t += 250
        }
        "$k" to b.build()
    }

    @Test
    fun lightsOutIsTheEndOfTheSecondStopOnTheGrid() {
        val grid = archive.findGrid(geometry, race(), before = null)
        assertNotNull(grid)
        assertEquals((t0 + 730_000).toDouble(), grid!!.lightsOutUtc.toDouble(), 2_500.0)
    }

    @Test
    fun startLineIsJustAheadOfPole() {
        val grid = archive.findGrid(geometry, race(), before = null)!!
        // Pole is 63 units behind the finish; the start line 50 units ahead of pole: 13 behind.
        val expected = (1f - 13f / lap)
        val off = abs(grid.startLine - expected).let { minOf(it, 1f - it) } * lap
        assertEquals("start line off by $off units", 0.0, off.toDouble(), 15.0)
    }

    @Test
    fun noGridInAPracticeLikeSession() {
        val moving = (0 until 20).associate { k ->
            val b = TrackBuilder()
            var t = 0L
            while (t <= 300_000L) {
                val a = 2 * PI * (t + k * 4_000) / 90_000.0
                b.add(t0 + t, (radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
                t += 250
            }
            "$k" to b.build()
        }
        assertNull(archive.findGrid(geometry, moving, before = null))
    }

    @Test
    fun chequeredFlagAndSessionStartComeFromTheirFeeds() {
        val start = archive.parseSessionStarted(
            "00:00:01.000{\"Status\":\"Inactive\"}\r\n00:10:00.000{\"Status\":\"Started\"}\r\n",
            t0,
        )
        assertEquals(t0 + 600_000, start)
        val rc = listOf(
            "00:00:05.000{\"Messages\":[{\"Utc\":\"2026-10-25T20:00:00\",\"Category\":\"Flag\",\"Flag\":\"GREEN\"}]}",
            "01:40:00.000{\"Messages\":{\"40\":{\"Utc\":\"2026-10-25T21:40:00\",\"Category\":\"Flag\",\"Flag\":\"CHEQUERED\"}}}",
        ).joinToString("\r\n")
        assertEquals(java.time.Instant.parse("2026-10-25T21:40:00Z").toEpochMilli(), archive.parseChequered(rc, t0))
    }

    @Test
    fun pointAtWalksTheOutline() {
        val quarter = geometry.pointAt(0.25f)
        assertEquals(0.0, quarter[0].toDouble(), 120.0)
        assertEquals(radius.toDouble(), quarter[1].toDouble(), 120.0)
        // Heading at angle 90 degrees, counter-clockwise: towards -x.
        assertEquals(-1.0, quarter[2].toDouble(), 0.05)
    }
}
