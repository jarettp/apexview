package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TimingData shaped like F1's: a list-style snapshot first, then index-keyed updates. */
class DriverTimingTest {
    private val t0 = 1_000_000L

    private val stream = listOf(
        "00:00:01.000{\"Lines\":{\"16\":{\"Position\":\"3\",\"InPit\":true,\"NumberOfLaps\":0,\"Sectors\":[{\"Value\":\"\"},{\"Value\":\"\"},{\"Value\":\"\"}]}}}",
        "00:01:00.000{\"Lines\":{\"16\":{\"InPit\":false,\"PitOut\":true}}}",
        "00:02:00.000{\"Lines\":{\"16\":{\"NumberOfLaps\":1}}}",
        "00:02:30.000{\"Lines\":{\"16\":{\"Sectors\":{\"0\":{\"Value\":\"30.123\",\"PersonalFastest\":true}}}}}",
        "00:03:00.000{\"Lines\":{\"16\":{\"Sectors\":{\"1\":{\"Value\":\"28.900\",\"OverallFastest\":true,\"PersonalFastest\":true}}}}}",
        "00:03:20.000{\"Lines\":{\"16\":{\"Sectors\":{\"2\":{\"Value\":\"22.001\"}},\"NumberOfLaps\":2,\"LastLapTime\":{\"Value\":\"1:21.024\",\"PersonalFastest\":true},\"TimeDiffToFastest\":\"+0.289\",\"Position\":\"2\"}}}",
        "00:03:21.000{\"Lines\":{\"16\":{\"Sectors\":{\"0\":{\"Value\":\"\"},\"1\":{\"Value\":\"\"},\"2\":{\"Value\":\"\"}}}}}",
    ).joinToString("\r\n")

    private val laps = DriverTimingParser.parse(stream, t0).getValue("16")

    @Test
    fun sectorsFillInWithTheirColours() {
        val s = laps.at(t0 + 185_000)!!
        assertEquals("30.123", s.sectors[0]!!.time)
        assertEquals(Pace.GREEN, s.sectors[0]!!.pace)
        assertEquals(Pace.PURPLE, s.sectors[1]!!.pace)
        assertNull(s.sectors[2])
        assertEquals(3, s.position)
    }

    @Test
    fun runningTimeCountsFromTheLastCrossing() {
        val s = laps.at(t0 + 150_000)!!
        assertEquals(30_000L, s.runningMs)
        assertNull("in the pits at the start", laps.at(t0 + 30_000)!!.runningMs)
    }

    @Test
    fun lapCompleteBringsLapTimeGapAndPosition() {
        val s = laps.at(t0 + 200_500)!!
        assertEquals("1:21.024", s.lastLap!!.time)
        assertEquals(Pace.GREEN, s.lastLap!!.pace)
        assertEquals("+0.289", s.gap)
        assertEquals(2, s.position)
        assertEquals(500L, s.runningMs)
        // The finished lap's sectors move aside; the new lap starts empty.
        assertEquals(Pace.YELLOW, s.lapSectors[2]!!.pace)
        assertEquals(Pace.PURPLE, s.lapSectors[1]!!.pace)
        assertTrue(s.sectors.all { it == null })
    }

    @Test
    fun aLateFinalSectorStillCountsForTheLapItFinished() {
        val late = listOf(
            "00:00:01.000{\"Lines\":{\"4\":{\"NumberOfLaps\":3}}}",
            "00:01:00.000{\"Lines\":{\"4\":{\"NumberOfLaps\":4,\"LastLapTime\":{\"Value\":\"1:33.100\"}}}}",
            "00:01:01.000{\"Lines\":{\"4\":{\"Sectors\":{\"2\":{\"Value\":\"30.100\",\"OverallFastest\":true}}}}}",
        ).joinToString("\n")
        val s = DriverTimingParser.parse(late, t0).getValue("4").at(t0 + 62_000)!!
        assertEquals(Pace.PURPLE, s.lapSectors[2]!!.pace)
        assertNull(s.sectors[2])
    }

    @Test
    fun lapNumbersComeFromF1sCount() {
        assertNull("nothing known before the first count", laps.lapAt(t0))
        assertEquals(1, laps.lapAt(t0 + 60_000))
        assertEquals(2, laps.lapAt(t0 + 150_000))
        assertEquals(3, laps.lapAt(t0 + 202_000))
        // Joined mid-race: the first count seen is already 14.
        val joined = DriverTimingParser.parse("00:00:01.000{\"Lines\":{\"1\":{\"NumberOfLaps\":14}}}", t0).getValue("1")
        assertEquals(15, joined.lapAt(t0 + 5_000))
    }

    @Test
    fun newLapClearsTheSectors() {
        val s = laps.at(t0 + 202_000)!!
        assertTrue(s.sectors.all { it == null })
        assertEquals("1:21.024", s.lastLap!!.time)
    }
}
