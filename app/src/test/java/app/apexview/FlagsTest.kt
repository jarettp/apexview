package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagsTest {
    private val archive = LiveTimingArchive("token", "test")
    private val t0 = java.time.Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()

    private fun utc(s: String) = java.time.Instant.parse("2026-10-25T${s}Z").toEpochMilli()

    // As the 2026 US GP sent them: a double yellow and a yellow behind it, cleared one by one,
    // then a VSC that ends with "TRACK CLEAR".
    private val rc = listOf(
        "00:04:42.000{\"Messages\":[{\"Utc\":\"2026-10-25T20:04:42\",\"Category\":\"Flag\",\"Flag\":\"DOUBLE YELLOW\",\"Scope\":\"Sector\",\"Sector\":2,\"Message\":\"DOUBLE YELLOW IN TRACK SECTOR 2\"},{\"Utc\":\"2026-10-25T20:04:42\",\"Category\":\"Flag\",\"Flag\":\"YELLOW\",\"Scope\":\"Sector\",\"Sector\":1,\"Message\":\"YELLOW IN TRACK SECTOR 1\"}]}",
        "00:04:53.000{\"Messages\":{\"7\":{\"Utc\":\"2026-10-25T20:04:53\",\"Category\":\"Flag\",\"Flag\":\"CLEAR\",\"Scope\":\"Sector\",\"Sector\":2,\"Message\":\"CLEAR IN TRACK SECTOR 2\"}}}",
        "00:05:43.000{\"Messages\":{\"9\":{\"Utc\":\"2026-10-25T20:05:43\",\"Category\":\"Flag\",\"Flag\":\"YELLOW\",\"Scope\":\"Sector\",\"Sector\":9,\"Message\":\"YELLOW IN TRACK SECTOR 9\"}}}",
        "00:05:50.000{\"Messages\":{\"10\":{\"Utc\":\"2026-10-25T20:05:50\",\"Category\":\"Other\",\"Message\":\"OVERTAKE DISABLED\"}}}",
        "00:06:30.000{\"Messages\":{\"12\":{\"Utc\":\"2026-10-25T20:06:30\",\"Category\":\"Flag\",\"Flag\":\"CLEAR\",\"Scope\":\"Track\",\"Message\":\"TRACK CLEAR\"}}}",
    ).joinToString("\r\n")

    private val status = listOf(
        "00:06:06.000{\"Status\":\"6\",\"Message\":\"VSCDeployed\"}",
        "00:06:20.000{\"Status\":\"7\",\"Message\":\"VSCEnding\"}",
        "00:06:30.000{\"Status\":\"1\",\"Message\":\"AllClear\"}",
    ).joinToString("\r\n")

    private val flags = TrackFlags(archive.parseSectorFlags(rc, t0), archive.parseTrackStatus(status, t0))

    @Test
    fun sectorYellowsComeAndGo() {
        val first = flags.at(utc("20:04:45"))
        assertEquals(TrackCondition.CLEAR, first.track)
        assertEquals(mapOf(2 to 2, 1 to 1), first.yellow)
        // Sector 2 cleared on its own; sector 1 stays yellow until race control clears the track.
        assertEquals(mapOf(1 to 1), flags.at(utc("20:05:00")).yellow)
        assertEquals(mapOf(1 to 1, 9 to 1), flags.at(utc("20:05:45")).yellow)
        assertTrue(flags.at(utc("20:06:31")).yellow.isEmpty())
    }

    @Test
    fun theWholeTrackFollowsTrackStatus() {
        assertEquals(TrackCondition.CLEAR, flags.at(utc("20:06:00")).track)
        assertEquals(TrackCondition.VSC, flags.at(utc("20:06:10")).track)
        assertEquals(TrackCondition.VSC_ENDING, flags.at(utc("20:06:25")).track)
        assertEquals(TrackCondition.CLEAR, flags.at(utc("20:06:31")).track)
        assertEquals(TrackCondition.RED, TrackFlags(emptyList(), listOf(t0 to 5)).at(t0 + 1).track)
    }
}
