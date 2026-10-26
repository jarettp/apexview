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

    @Test
    fun aRedFlagCountsDownToTheAnnouncedRestart() {
        // 2026 Belgian GP qualifying: red at 14:56:55 UTC, "Q3 WILL RESUME AT 17:04" (local, GMT+2).
        val spa = java.time.Instant.parse("2026-07-18T14:00:00Z").toEpochMilli()
        val rc = listOf(
            "00:56:55.000{\"Messages\":[{\"Utc\":\"2026-07-18T14:56:55\",\"Category\":\"Flag\",\"Flag\":\"RED\",\"Scope\":\"Track\",\"Message\":\"RED FLAG\"}]}",
            "01:00:16.000{\"Messages\":{\"30\":{\"Utc\":\"2026-07-18T15:00:16\",\"Category\":\"Other\",\"Message\":\"Q3 WILL RESUME AT 17:04\"}}}",
        ).joinToString("\r\n")
        val resumptions = archive.parseResumptions(rc, spa, "02:00:00")
        assertEquals(1, resumptions.size)
        assertEquals(java.time.Instant.parse("2026-07-18T15:04:00Z").toEpochMilli(), resumptions[0].atUtc)
        val flags = TrackFlags(emptyList(), listOf(spa + 3_415_000L to 5), resumptions = resumptions)
        val at = java.time.Instant.parse("2026-07-18T15:00:30Z").toEpochMilli()
        assertEquals("Red flag · Q3 resumes in 3:30", flags.at(at).caption(at))
        // Before it's announced there's no countdown; after the time, none either.
        val before = java.time.Instant.parse("2026-07-18T14:58:00Z").toEpochMilli()
        assertEquals("Red flag", flags.at(before).caption(before))
        val estimated = archive.parseResumptions(
            "00:54:38.000{\"Messages\":[{\"Utc\":\"2026-05-22T20:54:38\",\"Category\":\"Other\",\"Message\":\"ESTIMATED TIME OF SQ1 RESUMPTION - 16:59\"}]}",
            spa, "-04:00:00",
        )
        assertEquals("SQ1 resumes", estimated.single().what)
        assertEquals(java.time.Instant.parse("2026-05-22T20:59:00Z").toEpochMilli(), estimated.single().atUtc)
    }

    @Test
    fun aRedFlagLastsUntilTheGreenLightNotTrackClear() {
        // Spa 2026 Q3: "TRACK CLEAR" 31 s after the red, but the session stayed stopped until 15:04.
        val spa = java.time.Instant.parse("2026-07-18T14:00:00Z").toEpochMilli()
        val rc = listOf(
            "00:56:55.000{\"Messages\":[{\"Utc\":\"2026-07-18T14:56:55\",\"Category\":\"Flag\",\"Flag\":\"RED\",\"Scope\":\"Track\",\"Message\":\"RED FLAG\"}]}",
            "00:57:26.000{\"Messages\":{\"25\":{\"Utc\":\"2026-07-18T14:57:26\",\"Category\":\"Flag\",\"Flag\":\"CLEAR\",\"Scope\":\"Track\",\"Message\":\"TRACK CLEAR\"}}}",
            "01:04:00.000{\"Messages\":{\"31\":{\"Utc\":\"2026-07-18T15:04:00\",\"Category\":\"Flag\",\"Flag\":\"GREEN\",\"Scope\":\"Track\",\"Message\":\"GREEN LIGHT - PIT EXIT OPEN\"}}}",
        ).joinToString("\r\n")
        val flags = TrackFlags(emptyList(), emptyList(), redFlags = archive.parseRedFlags(rc, spa))
        fun at(s: String) = java.time.Instant.parse("2026-07-18T${s}Z").toEpochMilli()
        assertEquals(TrackCondition.RED, flags.at(at("15:00:00")).track)
        assertEquals(TrackCondition.CLEAR, flags.at(at("15:04:01")).track)
    }

    @Test
    fun theSafetyCarComingInIsItsOwnCondition() {
        val sc = TrackFlags(emptyList(), listOf(t0 to 4, t0 + 300_000 to 1), safetyCarIn = listOf(t0 + 200_000))
        assertEquals(TrackCondition.SAFETY_CAR, sc.at(t0 + 100_000).track)
        assertEquals(TrackCondition.SAFETY_CAR_ENDING, sc.at(t0 + 250_000).track)
        assertEquals(TrackCondition.CLEAR, sc.at(t0 + 310_000).track)
    }
}
