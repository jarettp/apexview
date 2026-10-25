package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TyreTest {
    @Test
    fun currentTyreIsTheLastStint() {
        val stream = listOf(
            "00:00:01.000{\"Lines\":{\"44\":{\"Stints\":[{\"Compound\":\"MEDIUM\",\"TotalLaps\":0}]}}}",
            "00:10:00.000{\"Lines\":{\"44\":{\"Stints\":{\"0\":{\"TotalLaps\":12}}}}}",
            "00:20:00.000{\"Lines\":{\"44\":{\"Stints\":{\"1\":{\"Compound\":\"HARD\",\"TotalLaps\":0}}}}}",
        ).joinToString("\n")
        val log = TyreParser.parse(stream, 0L).getValue("44")
        assertNull(log.at(0L))
        assertEquals("MEDIUM", log.at(700_000L)!!.compound)
        assertEquals(12, log.at(700_000L)!!.laps)
        assertEquals("HARD", log.at(1_300_000L)!!.compound)
        assertEquals(0, log.at(1_300_000L)!!.laps)
    }
}
